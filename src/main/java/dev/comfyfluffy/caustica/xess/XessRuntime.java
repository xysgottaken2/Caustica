package dev.comfyfluffy.caustica.xess;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaMod;

import net.fabricmc.loader.api.FabricLoader;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Shared Intel XeSS lifetime for the mod: locates + loads the XeSS shim (with Intel's prebuilt
 * {@code libxess.dll} runtime next to it), runs {@code xessshim_init} exactly once per Vulkan
 * device, and creates the XeSS VK context on demand. Modeled on {@code FsrRuntime} — the upscaler
 * feature owns its upscaler init/destroy; teardown ordering (feature first, runtime second, device
 * last) mirrors the FSR/NGX paths.
 *
 * <p>Windows-only: Intel ships {@code libxess.dll} for Windows only (the XeSS VK backend's runtime
 * is a closed prebuilt binary). On other platforms {@link #platformSupported()} reports false and
 * the upscaler selector simply does not offer XeSS.
 */
public final class XessRuntime {
    public static final XessRuntime INSTANCE = new XessRuntime();

    private static final String SHIM_NAME = "xessshim.dll";
    private static final String RUNTIME_NAME = "libxess.dll";
    private static final String PLATFORM_DIR = "windows-x64";

    private XessLibrary lib;
    private boolean initialized;
    private boolean failed;

    private XessRuntime() {
    }

    /** Whether bundled XeSS natives exist for this host (the selector's availability gate). */
    public static boolean platformSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
        if (!(os.contains("win") && x64)) {
            return false;
        }
        // The natives are only bundled when the shim was built (see bundleXessNatives in build.gradle);
        // probe the mod's resources rather than promising an upscaler the runtime cannot deliver.
        try (InputStream probe = XessRuntime.class.getResourceAsStream(
                "/caustica/natives/" + PLATFORM_DIR + "/" + SHIM_NAME)) {
            return probe != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Ensure the shim is loaded and initialized for {@code device}, returning the shared
     * {@link XessLibrary}, or {@code null} if it is unavailable. Idempotent; latches failure so it
     * isn't retried every frame (cleared by {@link #shutdown()} so a fresh device can re-init).
     */
    public synchronized XessLibrary acquire(VulkanDevice device) {
        if (initialized) {
            return lib;
        }
        if (failed) {
            return null;
        }
        try {
            init(device);
            initialized = true;
            return lib;
        } catch (Throwable t) {
            failed = true;
            lib = null;
            CausticaMod.LOGGER.error("XeSS shim init failed; XeSS upscaling disabled", t);
            return null;
        }
    }

    /**
     * Create the XeSS VK context on the current device. The shim is idempotent here (a live context
     * returns success without recreating), so callers can invoke this unconditionally before every
     * upscaler init — including right after {@code xessshim_destroy_upscaler} dropped the context.
     * The device must already carry XeSS's required features/extensions (injected at vkCreateDevice
     * time by {@code RtDeviceBringup}).
     */
    public synchronized void ensureContext(VulkanDevice device) {
        if (lib == null) {
            throw new IllegalStateException("XeSS runtime not initialized");
        }
        VkInstance instance = device.vkDevice().getPhysicalDevice().getInstance();
        int rc = lib.createContext(instance.address(), device.vkDevice().getPhysicalDevice().address());
        if (rc != 0) {
            throw new IllegalStateException("xessshim_create_context failed: " + rc
                    + " last=" + lib.lastResult());
        }
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    /** The shared library once {@link #acquire} has succeeded, else {@code null}. */
    public XessLibrary library() {
        return lib;
    }

    /**
     * Reset the runtime after device teardown. The XeSS context itself must already be destroyed by
     * {@code RtXessUpscaler.destroy()} (it holds device resources), the same ordering the FSR/NGX
     * features use.
     */
    public synchronized void shutdown() {
        initialized = false;
        failed = false;
        lib = null;
    }

    private void init(VulkanDevice device) {
        if (!platformSupported()) {
            throw new IllegalStateException("XeSS natives are not bundled for this platform");
        }
        Path shim = extractBundledNative(SHIM_NAME);
        Path runtime = extractBundledNative(RUNTIME_NAME);
        if (shim == null || runtime == null) {
            throw new IllegalStateException("bundled XeSS natives missing (shim=" + shim + " runtime=" + runtime + ")");
        }

        lib = XessLibrary.load(shim);

        VkInstance instance = device.vkDevice().getPhysicalDevice().getInstance();
        long gdpa;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            gdpa = VK10.vkGetInstanceProcAddr(instance, stack.ASCII("vkGetDeviceProcAddr"));
        }
        // The shim loads (not links) Intel's runtime, so it needs its extracted path.
        try (Arena arena = Arena.ofConfined()) {
            int rc = lib.init(device.vkDevice().address(), gdpa, wideString(arena, runtime.toString()));
            if (rc != 0) {
                throw new IllegalStateException("xessshim_init failed: " + rc);
            }
        }
        CausticaMod.LOGGER.info("XeSS shim initialized ({})", shim);
    }

    // Native wchar_t is 2 bytes (UTF-16) on Windows, the only supported platform (same convention
    // FsrRuntime uses for its wchar_t paths).
    private static MemorySegment wideString(Arena arena, String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate((long) data.length + 2);
        MemorySegment.copy(data, 0, seg, ValueLayout.JAVA_BYTE, 0, data.length);
        seg.set(ValueLayout.JAVA_BYTE, data.length, (byte) 0);
        seg.set(ValueLayout.JAVA_BYTE, data.length + 1, (byte) 0);
        return seg;
    }

    /** Extract one bundled native into the per-user natives dir, reusing it when unchanged. */
    private static Path extractBundledNative(String name) {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("caustica-xess")
                .resolve("natives").resolve(PLATFORM_DIR);
        try {
            Files.createDirectories(dir);
            Path dst = dir.resolve(name);
            String resource = "/caustica/natives/" + PLATFORM_DIR + "/" + name;
            try (InputStream in = XessRuntime.class.getResourceAsStream(resource)) {
                if (in == null) {
                    return null;
                }
                byte[] bytes = in.readAllBytes();
                if (!sameBytes(dst, bytes)) {
                    Files.write(dst, bytes);
                }
            }
            return dst;
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("Could not extract bundled XeSS native {} to {}", name, dir, e);
            return null;
        }
    }

    private static boolean sameBytes(Path dst, byte[] bytes) {
        try {
            return Files.size(dst) == bytes.length && Arrays.equals(Files.readAllBytes(dst), bytes);
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
