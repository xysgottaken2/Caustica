package dev.xys.vulkanrt.integration;

import dev.xys.vulkanrt.render.RayTracingCapabilities;
import org.lwjgl.vulkan.VkDevice;
import java.util.concurrent.ConcurrentHashMap;

/** Physical support is not logical-device enablement. Entries belong to the actual borrowed device. */
public final class DeviceNegotiation {
    private record Enabled(VkDevice device, RayTracingCapabilities capabilities) {}
    private record Rejected(VkDevice device, String reason) {}
    private static final ConcurrentHashMap<Long, Enabled> ENABLED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Rejected> REJECTED = new ConcurrentHashMap<>();
    public static void enabled(VkDevice device, RayTracingCapabilities caps) {
        REJECTED.remove(device.address());
        ENABLED.put(device.address(), new Enabled(device, caps));
    }
    public static void rejected(VkDevice device, String reason) {
        ENABLED.remove(device.address());
        REJECTED.put(device.address(), new Rejected(device, reason));
    }
    public static RayTracingCapabilities enabledFor(VkDevice device) {
        Enabled enabled = ENABLED.get(device.address());
        return enabled != null && enabled.device() == device ? enabled.capabilities() : null;
    }
    public static String rejectionFor(VkDevice device) {
        Rejected rejected = REJECTED.get(device.address());
        return rejected != null && rejected.device() == device ? rejected.reason()
                : "No negotiation record for this VkDevice: VulkanBackend.createDevice hook was not reached for it. "
                + "Physical support cannot enable features retroactively; check Mixins and the installed JAR, then restart.";
    }
    public static boolean hasEnabledDevice() { return !ENABLED.isEmpty(); }
    public static void forget(VkDevice device) { ENABLED.remove(device.address()); REJECTED.remove(device.address()); }
    private DeviceNegotiation() {}
}
