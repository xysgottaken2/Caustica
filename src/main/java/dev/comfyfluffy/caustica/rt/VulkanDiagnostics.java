package dev.comfyfluffy.caustica.rt;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import org.lwjgl.PointerBuffer;
import org.lwjgl.Version;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.EXTDeviceFault;
import org.lwjgl.vulkan.NVDeviceDiagnosticsConfig;
import org.lwjgl.vulkan.NVDeviceDiagnosticCheckpoints;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceDiagnosticsConfigCreateInfoNV;
import org.lwjgl.vulkan.VkDeviceFaultAddressInfoEXT;
import org.lwjgl.vulkan.VkDeviceFaultCountsEXT;
import org.lwjgl.vulkan.VkDeviceFaultInfoEXT;
import org.lwjgl.vulkan.VkDeviceFaultVendorInfoEXT;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFaultFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceDiagnosticsConfigFeaturesNV;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkCheckpointDataNV;
import org.lwjgl.vulkan.VkQueue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.lwjgl.vulkan.EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME;

/** Startup Vulkan inventory and best-effort {@code VK_EXT_device_fault} reporting. */
public final class VulkanDiagnostics {
    private static final int MAX_FAULT_RECORDS = 64;
    private static final int MAX_BREADCRUMBS = 96;
    private static final long MAX_VENDOR_BINARY_BYTES = 64L * 1024L * 1024L;
    private static final AtomicBoolean FAULT_REPORTED = new AtomicBoolean();
    private static final ArrayDeque<String> BREADCRUMBS = new ArrayDeque<>();
    private static final ConcurrentHashMap<String, String> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentSkipListMap<Long, BufferRange> BUFFERS =
            new ConcurrentSkipListMap<>(Long::compareUnsigned);
    private static volatile boolean deviceFaultRequested;
    private static volatile boolean deviceFaultVendorBinaryRequested;
    private static volatile boolean deviceFaultEnabled;
    private static volatile boolean nvDiagnosticsRequested;
    private static volatile VkQueue lastCausticaQueue;
    private static volatile String lastCausticaQueueLabel;
    private static int memoryHeapCount;
    private static boolean startupLogged;
    private static boolean instanceLayersLogged;

    private record FaultSupport(boolean fault, boolean vendorBinary) {
    }

    private record BufferRange(long address, long size, long handle, String label) {
        boolean contains(long value) {
            return Long.compareUnsigned(value, address) >= 0
                    && Long.compareUnsigned(value - address, size) < 0;
        }
    }

    private VulkanDiagnostics() {
    }

    /** Log loader-visible layers and the explicit layer list passed to {@code vkCreateInstance}. */
    public static synchronized void logInstanceLayers(VkInstanceCreateInfo createInfo) {
        if (instanceLayersLogged) {
            return;
        }
        instanceLayersLogged = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer count = stack.callocInt(1);
            int result = VK10.vkEnumerateInstanceLayerProperties(count, null);
            if (result != VK10.VK_SUCCESS) {
                CausticaMod.LOGGER.warn("vkEnumerateInstanceLayerProperties(count) failed: {}", result);
            } else {
                VkLayerProperties.Buffer layers = VkLayerProperties.calloc(count.get(0), stack);
                result = VK10.vkEnumerateInstanceLayerProperties(count, layers);
                if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) {
                    CausticaMod.LOGGER.warn("vkEnumerateInstanceLayerProperties(data) failed: {}", result);
                } else {
                    int layerCount = Math.min(count.get(0), layers.capacity());
                    CausticaMod.LOGGER.info("Vulkan loader-visible instance layers ({}):", layerCount);
                    for (int i = 0; i < layerCount; i++) {
                        VkLayerProperties layer = layers.get(i);
                        CausticaMod.LOGGER.info(
                                "Vulkan instance layer[{}]: name='{}', spec={}, implementation={} (0x{}), description='{}'",
                                i, layer.layerNameString(), version(layer.specVersion()),
                                Integer.toUnsignedLong(layer.implementationVersion()),
                                Integer.toUnsignedString(layer.implementationVersion(), 16),
                                layer.descriptionString());
                    }
                }
            }
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("Failed to enumerate Vulkan instance layers", t);
        }

        List<String> requested = new ArrayList<>();
        PointerBuffer names = createInfo.ppEnabledLayerNames();
        if (names != null) {
            for (int i = names.position(); i < names.limit(); i++) {
                requested.add(MemoryUtil.memUTF8(names.get(i)));
            }
        }
        CausticaMod.LOGGER.info(
                "Vulkan application-requested instance layers ({}): {} (implicit loader layers may still activate)",
                requested.size(), requested.isEmpty() ? "<none>" : requested);

        List<String> loaderEnvironment = new ArrayList<>();
        for (String key : List.of("VK_INSTANCE_LAYERS", "VK_LOADER_LAYERS_ENABLE", "VK_LOADER_LAYERS_DISABLE",
                "VK_LOADER_LAYERS_ALLOW", "VK_LOADER_DEBUG", "SteamAppId", "SteamGameId")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                loaderEnvironment.add(key + "=" + value);
            }
        }
        CausticaMod.LOGGER.info("Vulkan layer/overlay environment: {}",
                loaderEnvironment.isEmpty() ? "<none>" : loaderEnvironment);
    }

    /** Add device-fault only when both the extension and its required feature bit are supported. */
    public static void addDeviceFaultExtension(Collection<String> extensions, VulkanPhysicalDevice physicalDevice) {
        logStartup(physicalDevice);
        FaultSupport faultSupport = physicalDevice.hasDeviceExtension(VK_EXT_DEVICE_FAULT_EXTENSION_NAME)
                ? queryDeviceFaultSupport(physicalDevice) : new FaultSupport(false, false);
        deviceFaultRequested = faultSupport.fault();
        // The vendor binary is an Aftermath-format dump on NVIDIA; producing one may require the driver
        // to run crash-dump tracking from device creation, so it shares the heavy-diagnostics toggle.
        // Plain deviceFault (fault addresses + vendor records) stays on: it reports MMU fault state the
        // hardware captures regardless.
        deviceFaultVendorBinaryRequested = faultSupport.vendorBinary()
                && CausticaConfig.Rt.Diagnostics.HEAVY_CRASH_DIAGNOSTICS.value();
        if (deviceFaultRequested) {
            if (!extensions.contains(VK_EXT_DEVICE_FAULT_EXTENSION_NAME)) {
                extensions.add(VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
            }
            CausticaMod.LOGGER.info("Vulkan device-fault diagnostics requested ({}, vendorBinary={})",
                    VK_EXT_DEVICE_FAULT_EXTENSION_NAME, deviceFaultVendorBinaryRequested);
        } else {
            CausticaMod.LOGGER.warn("Vulkan device-fault diagnostics unavailable on [{}]",
                    physicalDevice.deviceName());
        }
        nvDiagnosticsRequested = CausticaConfig.Rt.Diagnostics.HEAVY_CRASH_DIAGNOSTICS.value()
                && physicalDevice.hasDeviceExtension(
                        NVDeviceDiagnosticsConfig.VK_NV_DEVICE_DIAGNOSTICS_CONFIG_EXTENSION_NAME)
                && supportsNvDiagnostics(physicalDevice);
        if (nvDiagnosticsRequested) {
            extensions.add(NVDeviceDiagnosticsConfig.VK_NV_DEVICE_DIAGNOSTICS_CONFIG_EXTENSION_NAME);
            CausticaMod.LOGGER.info("NVIDIA device diagnostics config requested");
        }
    }

    public static void addDeviceFaultFeature(Set<VulkanFeature> features) {
        if (!deviceFaultRequested && !nvDiagnosticsRequested) {
            return;
        }
        if (deviceFaultRequested) {
            VulkanPNextStruct faultStruct = new VulkanPNextStruct(VkPhysicalDeviceFaultFeaturesEXT.class);
            features.add(new VulkanFeature(faultStruct, "deviceFault"));
            if (deviceFaultVendorBinaryRequested) {
                features.add(new VulkanFeature(faultStruct, "deviceFaultVendorBinary"));
            }
        }
        if (nvDiagnosticsRequested) {
            VulkanPNextStruct diagnosticsStruct =
                    new VulkanPNextStruct(VkPhysicalDeviceDiagnosticsConfigFeaturesNV.class);
            features.add(new VulkanFeature(diagnosticsStruct, "diagnosticsConfig"));
        }
    }

    /** Prepend NVIDIA's device-create diagnostics flags while vanilla's creation stack is alive. */
    public static void attachNvDiagnosticsConfig(VkDeviceCreateInfo deviceCreateInfo, MemoryStack stack) {
        if (!nvDiagnosticsRequested) {
            return;
        }
        int flags = NVDeviceDiagnosticsConfig.VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_SHADER_DEBUG_INFO_BIT_NV
                | NVDeviceDiagnosticsConfig.VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_RESOURCE_TRACKING_BIT_NV
                | NVDeviceDiagnosticsConfig.VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_AUTOMATIC_CHECKPOINTS_BIT_NV
                | NVDeviceDiagnosticsConfig.VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_SHADER_ERROR_REPORTING_BIT_NV;
        VkDeviceDiagnosticsConfigCreateInfoNV config = VkDeviceDiagnosticsConfigCreateInfoNV.calloc(stack)
                .sType$Default().flags(flags).pNext(deviceCreateInfo.pNext());
        deviceCreateInfo.pNext(config.address());
    }

    public static void logEnabledExtensions(Collection<String> extensions) {
        List<String> sorted = new ArrayList<>(extensions);
        sorted.sort(Comparator.naturalOrder());
        CausticaMod.LOGGER.info("Vulkan device extensions requested ({}): {}", sorted.size(), sorted);
    }

    /** Verify the entry point after logical-device creation. */
    public static void probe(VkDevice device) {
        deviceFaultEnabled = deviceFaultRequested && device.getCapabilities().vkGetDeviceFaultInfoEXT != 0L;
        if (deviceFaultRequested) {
            CausticaMod.LOGGER.info("Vulkan device-fault diagnostics {}",
                    deviceFaultEnabled ? "enabled" : "FAILED: entry point missing");
        }
        if (nvDiagnosticsRequested) {
            CausticaMod.LOGGER.info("NVIDIA diagnostics config enabled (shader debug, resource tracking, automatic checkpoints, shader errors)");
        }
    }

    public static void breadcrumb(String label) {
        String entry = Instant.now() + " [" + Thread.currentThread().getName() + "] " + label;
        synchronized (BREADCRUMBS) {
            if (BREADCRUMBS.size() == MAX_BREADCRUMBS) {
                BREADCRUMBS.removeFirst();
            }
            BREADCRUMBS.addLast(entry);
        }
    }

    public static void setInFlight(String lane, String state) {
        if (state == null) {
            IN_FLIGHT.remove(lane);
        } else {
            IN_FLIGHT.put(lane, state);
            breadcrumb(lane + ": " + state);
        }
    }

    /** Remember the queue before submission so a later device-loss report queries the relevant queue. */
    public static void noteQueueSubmission(VkQueue queue, String label) {
        lastCausticaQueue = queue;
        lastCausticaQueueLabel = label;
    }

    public static void registerBuffer(long address, long size, long handle, String label) {
        if (address != 0L && size > 0L) {
            BUFFERS.put(address, new BufferRange(address, size, handle, label));
        }
    }

    public static void unregisterBuffer(long address, long handle) {
        BUFFERS.computeIfPresent(address, (ignored, range) -> range.handle == handle ? null : range);
    }

    /** Query fault details once, immediately after a device-loss result is observed. */
    public static void reportDeviceLost(VulkanDevice device, String operation) {
        if (FAULT_REPORTED.get()) {
            return;
        }
        VkQueue queue = lastCausticaQueue;
        if (queue != null) {
            logNvQueueCheckpoints(queue, lastCausticaQueueLabel);
        }
        try {
            String checkpoints = VulkanUtils.formatCheckpoints(
                    device.checkpointExtension().retrieveCheckpoints(true));
            CausticaMod.LOGGER.error("Vulkan queue checkpoints:\n{}",
                    checkpoints.isBlank() ? "<none>" : checkpoints);
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Failed to retrieve Vulkan queue checkpoints", t);
        }
        reportDeviceLost(device.vkDevice(), operation);
    }

    /** Query fault details once, immediately after a device-loss result is observed. */
    public static void reportDeviceLost(VkDevice device, String operation) {
        if (!FAULT_REPORTED.compareAndSet(false, true)) {
            return;
        }
        CausticaMod.LOGGER.error("Vulkan device lost while {}", operation);
        logRuntimeSnapshot();
        if (!deviceFaultEnabled || device == null || device.getCapabilities().vkGetDeviceFaultInfoEXT == 0L) {
            CausticaMod.LOGGER.error("VK_EXT_device_fault is unavailable; no driver fault details can be queried");
            return;
        }

        ByteBuffer vendorBinary = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceFaultCountsEXT counts = VkDeviceFaultCountsEXT.calloc(stack).sType$Default();
            int result = EXTDeviceFault.vkGetDeviceFaultInfoEXT(device, counts, null);
            if (result != VK10.VK_SUCCESS) {
                CausticaMod.LOGGER.error("vkGetDeviceFaultInfoEXT(counts) failed: {}", result);
                return;
            }

            int addressCount = Math.min(counts.addressInfoCount(), MAX_FAULT_RECORDS);
            int vendorCount = Math.min(counts.vendorInfoCount(), MAX_FAULT_RECORDS);
            if (addressCount != counts.addressInfoCount() || vendorCount != counts.vendorInfoCount()) {
                CausticaMod.LOGGER.warn("Capping Vulkan fault records to {} (driver reported address={}, vendor={})",
                        MAX_FAULT_RECORDS, counts.addressInfoCount(), counts.vendorInfoCount());
            }
            VkDeviceFaultAddressInfoEXT.Buffer addresses = addressCount == 0
                    ? null : VkDeviceFaultAddressInfoEXT.calloc(addressCount, stack);
            VkDeviceFaultVendorInfoEXT.Buffer vendors = vendorCount == 0
                    ? null : VkDeviceFaultVendorInfoEXT.calloc(vendorCount, stack);
            long requestedVendorBytes = deviceFaultVendorBinaryRequested
                    && counts.vendorBinarySize() <= MAX_VENDOR_BINARY_BYTES ? counts.vendorBinarySize() : 0L;
            if (counts.vendorBinarySize() > MAX_VENDOR_BINARY_BYTES) {
                CausticaMod.LOGGER.warn("Skipping oversized Vulkan vendor fault binary: {}", formatBytes(counts.vendorBinarySize()));
            }
            if (requestedVendorBytes > 0L) {
                vendorBinary = MemoryUtil.memAlloc(Math.toIntExact(requestedVendorBytes));
            }
            VkDeviceFaultInfoEXT info = VkDeviceFaultInfoEXT.calloc(stack).sType$Default();
            MemoryUtil.memPutAddress(info.address() + VkDeviceFaultInfoEXT.PADDRESSINFOS,
                    addresses == null ? 0L : addresses.address());
            MemoryUtil.memPutAddress(info.address() + VkDeviceFaultInfoEXT.PVENDORINFOS,
                    vendors == null ? 0L : vendors.address());
            MemoryUtil.memPutAddress(info.address() + VkDeviceFaultInfoEXT.PVENDORBINARYDATA,
                    vendorBinary == null ? 0L : MemoryUtil.memAddress(vendorBinary));
            counts.addressInfoCount(addressCount).vendorInfoCount(vendorCount)
                    .vendorBinarySize(vendorBinary == null ? 0L : vendorBinary.capacity());

            result = EXTDeviceFault.vkGetDeviceFaultInfoEXT(device, counts, info);
            if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) {
                CausticaMod.LOGGER.error("vkGetDeviceFaultInfoEXT(info) failed: {}", result);
                return;
            }
            CausticaMod.LOGGER.error("Vulkan device fault: description='{}', addresses={}, vendorRecords={}, vendorBinaryBytes={}",
                    info.descriptionString(), counts.addressInfoCount(), counts.vendorInfoCount(), counts.vendorBinarySize());
            if (addresses != null) {
                for (int i = 0; i < Math.min(addressCount, counts.addressInfoCount()); i++) {
                    VkDeviceFaultAddressInfoEXT address = addresses.get(i);
                    String resource = resolveBuffer(address.reportedAddress());
                    CausticaMod.LOGGER.error("Vulkan fault address[{}]: type={}, address=0x{}, precision=0x{}, resource={}",
                            i, addressType(address.addressType()), Long.toUnsignedString(address.reportedAddress(), 16),
                            Long.toUnsignedString(address.addressPrecision(), 16), resource);
                }
            }
            if (vendors != null) {
                for (int i = 0; i < Math.min(vendorCount, counts.vendorInfoCount()); i++) {
                    VkDeviceFaultVendorInfoEXT vendor = vendors.get(i);
                    CausticaMod.LOGGER.error("Vulkan vendor fault[{}]: description='{}', code=0x{}, data=0x{}",
                            i, vendor.descriptionString(), Long.toUnsignedString(vendor.vendorFaultCode(), 16),
                            Long.toUnsignedString(vendor.vendorFaultData(), 16));
                }
            }
            if (vendorBinary != null && counts.vendorBinarySize() > 0L) {
                writeVendorBinary(vendorBinary, counts.vendorBinarySize());
            }
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Failed to query VK_EXT_device_fault after device loss", t);
        } finally {
            if (vendorBinary != null) {
                MemoryUtil.memFree(vendorBinary);
            }
        }
    }

    private static void logRuntimeSnapshot() {
        List<String> breadcrumbs;
        synchronized (BREADCRUMBS) {
            breadcrumbs = List.copyOf(BREADCRUMBS);
        }
        CausticaMod.LOGGER.error("Vulkan in-flight state: {}", IN_FLIGHT);
        CausticaMod.LOGGER.error("Vulkan recent breadcrumbs (oldest to newest, {}):", breadcrumbs.size());
        for (String breadcrumb : breadcrumbs) {
            CausticaMod.LOGGER.error("  {}", breadcrumb);
        }
        long totalBytes = BUFFERS.values().stream().mapToLong(BufferRange::size).sum();
        CausticaMod.LOGGER.error("Caustica live BDA buffers: count={}, bytes={}", BUFFERS.size(), formatBytes(totalBytes));
        RtContext context = RtContext.currentOrNull();
        if (context != null && memoryHeapCount > 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VmaBudget.Buffer budgets = VmaBudget.calloc(memoryHeapCount, stack);
                Vma.vmaGetHeapBudgets(context.vma(), budgets);
                for (int i = 0; i < memoryHeapCount; i++) {
                    VmaBudget budget = budgets.get(i);
                    CausticaMod.LOGGER.error(
                            "VMA heap[{}]: usage={}, budget={}, blocks={}, allocations={}, blockBytes={}, allocationBytes={}",
                            i, formatBytes(budget.usage()), formatBytes(budget.budget()),
                            budget.statistics().blockCount(), budget.statistics().allocationCount(),
                            formatBytes(budget.statistics().blockBytes()), formatBytes(budget.statistics().allocationBytes()));
                }
            } catch (Throwable t) {
                CausticaMod.LOGGER.error("Failed to collect VMA budgets after device loss", t);
            }
        }
    }

    private static void logNvQueueCheckpoints(VkQueue queue, String label) {
        if (queue.getCapabilities().vkGetQueueCheckpointDataNV == 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer count = stack.callocInt(1);
            NVDeviceDiagnosticCheckpoints.vkGetQueueCheckpointDataNV(queue, count, null);
            int checkpointCount = Math.min(count.get(0), MAX_FAULT_RECORDS);
            if (checkpointCount == 0) {
                CausticaMod.LOGGER.error("NVIDIA checkpoints for {} queue 0x{}: <none>", label,
                        Long.toUnsignedString(queue.address(), 16));
                return;
            }
            VkCheckpointDataNV.Buffer checkpoints = VkCheckpointDataNV.calloc(checkpointCount, stack);
            for (int i = 0; i < checkpointCount; i++) {
                checkpoints.get(i).sType$Default();
            }
            count.put(0, checkpointCount);
            NVDeviceDiagnosticCheckpoints.vkGetQueueCheckpointDataNV(queue, count, checkpoints);
            CausticaMod.LOGGER.error("NVIDIA checkpoints for {} queue 0x{} ({}):", label,
                    Long.toUnsignedString(queue.address(), 16), count.get(0));
            for (int i = 0; i < Math.min(checkpointCount, count.get(0)); i++) {
                VkCheckpointDataNV checkpoint = checkpoints.get(i);
                long marker = checkpoint.pCheckpointMarker();
                CausticaMod.LOGGER.error("  stage={} (0x{}), marker=0x{}",
                        pipelineStage(checkpoint.stage()), Integer.toUnsignedString(checkpoint.stage(), 16),
                        Long.toUnsignedString(marker, 16));
            }
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Failed to retrieve NVIDIA checkpoints for " + label, t);
        }
    }

    private static String resolveBuffer(long address) {
        var entry = BUFFERS.floorEntry(address);
        if (entry != null && entry.getValue().contains(address)) {
            BufferRange range = entry.getValue();
            return "'" + range.label + "' handle=0x" + Long.toUnsignedString(range.handle, 16)
                    + " range=0x" + Long.toUnsignedString(range.address, 16) + "+" + range.size
                    + " offset=" + Long.toUnsignedString(address - range.address);
        }
        var next = BUFFERS.ceilingEntry(address);
        String nearest = entry == null ? "none" : "prev='" + entry.getValue().label + "'@0x"
                + Long.toUnsignedString(entry.getKey(), 16);
        if (next != null) {
            nearest += ", next='" + next.getValue().label + "'@0x" + Long.toUnsignedString(next.getKey(), 16);
        }
        return "unresolved (" + nearest + ")";
    }

    private static void writeVendorBinary(ByteBuffer binary, long reportedSize) {
        int size = (int) Math.min(binary.capacity(), reportedSize);
        Path path = Path.of("caustica-device-fault-" + Instant.now().toString().replace(':', '-') + ".bin")
                .toAbsolutePath();
        ByteBuffer data = binary.duplicate().position(0).limit(size);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            while (data.hasRemaining()) {
                channel.write(data);
            }
            CausticaMod.LOGGER.error("Wrote Vulkan vendor fault binary ({} bytes) to {}", size, path);
        } catch (IOException e) {
            CausticaMod.LOGGER.error("Failed to write Vulkan vendor fault binary to {}", path, e);
        }
    }

    private static FaultSupport queryDeviceFaultSupport(VulkanPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFaultFeaturesEXT fault = VkPhysicalDeviceFaultFeaturesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(fault.address());
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features);
            return new FaultSupport(fault.deviceFault(), fault.deviceFaultVendorBinary());
        }
    }

    private static boolean supportsNvDiagnostics(VulkanPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceDiagnosticsConfigFeaturesNV diagnostics =
                    VkPhysicalDeviceDiagnosticsConfigFeaturesNV.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                    .pNext(diagnostics.address());
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features);
            return diagnostics.diagnosticsConfig();
        }
    }

    private static synchronized void logStartup(VulkanPhysicalDevice physicalDevice) {
        if (startupLogged) {
            return;
        }
        startupLogged = true;
        Runtime runtime = Runtime.getRuntime();
        long physicalMemory = -1L;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            physicalMemory = os.getTotalMemorySize();
        }
        String loaderVersion = "unknown";
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer version = stack.mallocInt(1);
            if (VK11.vkEnumerateInstanceVersion(version) == VK10.VK_SUCCESS) {
                loaderVersion = version(version.get(0));
            }
        }
        CausticaMod.LOGGER.info(
                "System: os='{} {}' arch={}, cpu='{}', logicalProcessors={}, physicalMemory={}, java='{} {}' vm='{}', heapMax={}, LWJGL={}, VulkanLoader={}",
                System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
                System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown"), runtime.availableProcessors(),
                formatBytes(physicalMemory), System.getProperty("java.vendor"), System.getProperty("java.version"),
                System.getProperty("java.vm.name"), formatBytes(runtime.maxMemory()), Version.getVersion(), loaderVersion);

        VkPhysicalDeviceProperties properties = physicalDevice.vkPhysicalDeviceProperties();
        var driver = physicalDevice.vkPhysicalDeviceDriverProperties();
        CausticaMod.LOGGER.info(
                "Vulkan GPU: name='{}', vendor={} (0x{}), deviceId=0x{}, type={}, api={}, driver='{}' info='{}' driverId={}, driverVersion=0x{}",
                physicalDevice.deviceName(), physicalDevice.vendorName(), Integer.toHexString(properties.vendorID()),
                Integer.toHexString(properties.deviceID()), physicalDevice.deviceType(), version(properties.apiVersion()),
                driver.driverNameString(), driver.driverInfoString(), driver.driverID(),
                Integer.toHexString(properties.driverVersion()));
        var vk11 = physicalDevice.vkPhysicalDeviceVulkan11Properties();
        var conformance = driver.conformanceVersion();
        CausticaMod.LOGGER.info(
                "Vulkan IDs: deviceUUID={}, driverUUID={}, conformance={}.{}.{}.{}",
                hex(vk11.deviceUUID()), hex(vk11.driverUUID()), conformance.major(), conformance.minor(),
                conformance.subminor(), conformance.patch());
        CausticaMod.LOGGER.info(
                "Vulkan limits: maxAllocationCount={}, nonCoherentAtomSize={}, bufferImageGranularity={}, maxStorageBufferRange={}, maxImage2D={}x{}",
                Integer.toUnsignedLong(properties.limits().maxMemoryAllocationCount()), formatBytesExact(properties.limits().nonCoherentAtomSize()),
                formatBytesExact(properties.limits().bufferImageGranularity()),
                formatBytes(Integer.toUnsignedLong(properties.limits().maxStorageBufferRange())),
                properties.limits().maxImageDimension2D(), properties.limits().maxImageDimension2D());
        logMemoryAndQueues(physicalDevice);
        CausticaMod.LOGGER.info("Vulkan selected queues: graphics={}, compute={}, transfer={}",
                physicalDevice.graphicsQueueFamilyAndIndex(), physicalDevice.computeQueueFamilyAndIndex(),
                physicalDevice.transferQueueFamilyAndIndex());
    }

    private static void logMemoryAndQueues(VulkanPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice.vkPhysicalDevice(), memory);
            memoryHeapCount = memory.memoryHeapCount();
            for (int i = 0; i < memory.memoryHeapCount(); i++) {
                var heap = memory.memoryHeaps(i);
                CausticaMod.LOGGER.info("Vulkan memory heap[{}]: size={}, flags={}", i, formatBytes(heap.size()),
                        memoryHeapFlags(heap.flags()));
            }
            for (int i = 0; i < memory.memoryTypeCount(); i++) {
                var type = memory.memoryTypes(i);
                CausticaMod.LOGGER.info("Vulkan memory type[{}]: heap={}, flags={}", i, type.heapIndex(),
                        memoryPropertyFlags(type.propertyFlags()));
            }

            java.nio.IntBuffer count = stack.callocInt(1);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), count, null);
            VkQueueFamilyProperties.Buffer queues = VkQueueFamilyProperties.calloc(count.get(0), stack);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), count, queues);
            for (int i = 0; i < queues.capacity(); i++) {
                VkQueueFamilyProperties queue = queues.get(i);
                CausticaMod.LOGGER.info("Vulkan queue family[{}]: count={}, flags={}, timestampBits={}",
                        i, queue.queueCount(), queueFlags(queue.queueFlags()), queue.timestampValidBits());
            }
        }
    }

    private static String version(int packed) {
        return String.format(Locale.ROOT, "%d.%d.%d", VK10.VK_VERSION_MAJOR(packed),
                VK10.VK_VERSION_MINOR(packed), VK10.VK_VERSION_PATCH(packed));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) return "unknown";
        return String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0));
    }

    private static String formatBytesExact(long bytes) {
        return bytes + " B (" + formatBytes(bytes) + ")";
    }

    private static String hex(ByteBuffer bytes) {
        StringBuilder result = new StringBuilder(bytes.remaining() * 2);
        for (int i = bytes.position(); i < bytes.limit(); i++) {
            result.append(String.format(Locale.ROOT, "%02x", bytes.get(i) & 0xFF));
        }
        return result.toString();
    }

    private static String memoryHeapFlags(int flags) {
        List<String> names = new ArrayList<>();
        if ((flags & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) names.add("DEVICE_LOCAL");
        if ((flags & VK11.VK_MEMORY_HEAP_MULTI_INSTANCE_BIT) != 0) names.add("MULTI_INSTANCE");
        return names.isEmpty() ? "0" : String.join("|", names);
    }

    private static String memoryPropertyFlags(int flags) {
        List<String> names = new ArrayList<>();
        if ((flags & VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) names.add("DEVICE_LOCAL");
        if ((flags & VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) names.add("HOST_VISIBLE");
        if ((flags & VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0) names.add("HOST_COHERENT");
        if ((flags & VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT) != 0) names.add("HOST_CACHED");
        if ((flags & VK10.VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT) != 0) names.add("LAZY");
        if ((flags & VK11.VK_MEMORY_PROPERTY_PROTECTED_BIT) != 0) names.add("PROTECTED");
        return names.isEmpty() ? "0" : String.join("|", names);
    }

    private static String queueFlags(int flags) {
        List<String> names = new ArrayList<>();
        if ((flags & VK10.VK_QUEUE_GRAPHICS_BIT) != 0) names.add("GRAPHICS");
        if ((flags & VK10.VK_QUEUE_COMPUTE_BIT) != 0) names.add("COMPUTE");
        if ((flags & VK10.VK_QUEUE_TRANSFER_BIT) != 0) names.add("TRANSFER");
        if ((flags & VK10.VK_QUEUE_SPARSE_BINDING_BIT) != 0) names.add("SPARSE");
        if ((flags & VK11.VK_QUEUE_PROTECTED_BIT) != 0) names.add("PROTECTED");
        return names.isEmpty() ? "0" : String.join("|", names);
    }

    private static String addressType(int type) {
        return switch (type) {
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_NONE_EXT -> "NONE";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_READ_INVALID_EXT -> "READ_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_WRITE_INVALID_EXT -> "WRITE_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_EXECUTE_INVALID_EXT -> "EXECUTE_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_UNKNOWN_EXT -> "IP_UNKNOWN";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_INVALID_EXT -> "IP_INVALID";
            case EXTDeviceFault.VK_DEVICE_FAULT_ADDRESS_TYPE_INSTRUCTION_POINTER_FAULT_EXT -> "IP_FAULT";
            default -> "UNKNOWN(" + type + ")";
        };
    }

    private static String pipelineStage(int stage) {
        return switch (stage) {
            case VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT -> "TOP_OF_PIPE";
            case VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT -> "DRAW_INDIRECT";
            case VK10.VK_PIPELINE_STAGE_VERTEX_INPUT_BIT -> "VERTEX_INPUT";
            case VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT -> "VERTEX_SHADER";
            case VK10.VK_PIPELINE_STAGE_TESSELLATION_CONTROL_SHADER_BIT -> "TESSELLATION_CONTROL";
            case VK10.VK_PIPELINE_STAGE_TESSELLATION_EVALUATION_SHADER_BIT -> "TESSELLATION_EVALUATION";
            case VK10.VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT -> "GEOMETRY_SHADER";
            case VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT -> "FRAGMENT_SHADER";
            case VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT -> "EARLY_FRAGMENT_TESTS";
            case VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT -> "LATE_FRAGMENT_TESTS";
            case VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT -> "COLOR_ATTACHMENT_OUTPUT";
            case VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT -> "COMPUTE_SHADER";
            case VK10.VK_PIPELINE_STAGE_TRANSFER_BIT -> "TRANSFER";
            case VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT -> "BOTTOM_OF_PIPE";
            case VK10.VK_PIPELINE_STAGE_HOST_BIT -> "HOST";
            case VK10.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT -> "ALL_GRAPHICS";
            case VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT -> "ALL_COMMANDS";
            default -> "UNKNOWN";
        };
    }
}
