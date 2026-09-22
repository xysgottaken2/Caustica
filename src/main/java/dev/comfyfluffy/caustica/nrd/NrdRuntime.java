package dev.comfyfluffy.caustica.nrd;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;

import net.fabricmc.loader.api.FabricLoader;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Shared NRD lifetime for the mod: locates + loads the NRD shim and runs {@code nrdshim_init}
 * (which wraps the renderer's Vulkan device through NRI and creates the REBLUR instance) exactly
 * once per size, mirroring {@code FsrRuntime}/{@code NgxRuntime}.
 *
 * <p>Windows-only for now, same reasoning as the FSR runtime: CI builds {@code nrdshim} against
 * the NRD source (statically linked, SPIR-V shaders embedded) and bundles it; other platforms
 * report unsupported and the NRD toggle stays hidden.
 */
public final class NrdRuntime {
    public static final NrdRuntime INSTANCE = new NrdRuntime();

    private static final String SHIM_NAME = "nrdshim.dll";
    private static final String PLATFORM_DIR = "windows-x64";

    private NrdLibrary lib;
    private boolean initialized;
    private boolean failed;
    private int initWidth;
    private int initHeight;

    private NrdRuntime() {
    }

    /**
     * Whether bundled NRD natives exist for this host (the UI gate for the NRD toggle). Cached:
     * featureFlags() asks every frame, and the answer cannot change within a session.
     */
    public static boolean platformSupported() {
        Boolean cached = PLATFORM_SUPPORTED_CACHE;
        if (cached != null) {
            return cached;
        }
        boolean supported = computePlatformSupported();
        PLATFORM_SUPPORTED_CACHE = supported;
        return supported;
    }

    private static volatile Boolean PLATFORM_SUPPORTED_CACHE;

    private static boolean computePlatformSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
        if (!(os.contains("win") && x64)) {
            return false;
        }
        try (InputStream probe = NrdRuntime.class.getResourceAsStream(
                "/caustica/natives/" + PLATFORM_DIR + "/" + SHIM_NAME)) {
            return probe != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Ensure the shim is loaded and a REBLUR instance exists at {@code width}x{@code height}.
     * Re-init on size change is cheap enough for the rare resize path (NRD recreates its pools);
     * returns {@code null} when unavailable, and callers fall back to the undenoised image.
     */
    /** True once init or a denoise has failed and the integration has latched off for the session. */
    public synchronized boolean hasFailed() {
        return failed;
    }

    public synchronized NrdLibrary acquire(VulkanDevice device, int width, int height) {
        if (failed) {
            return null;
        }
        try {
            if (!initialized) {
                init(device, width, height);
                initialized = true;
                return lib;
            }
            if (initWidth != width || initHeight != height) {
                // Re-init destroys and rebuilds NRD's internal resources. With
                // autoWaitForIdle = false NRD does not synchronize that for us, so a frame still
                // referencing the old pools would read freed memory.
                waitDeviceIdle();
                init(device, width, height);
            }
            return lib;
        } catch (Throwable t) {
            failed = true;
            lib = null;
            CausticaMod.LOGGER.error("NRD init failed; NRD denoising disabled", t);
            return null;
        }
    }

    public synchronized void shutdown() {
        if (lib != null && initialized) {
            try {
                // The integration is created with autoWaitForIdle = false, so NRD will NOT stall the
                // device for us: destroying its pipelines/descriptors while a frame that references
                // them is still in flight is a use-after-free. The renderer's own teardown path waits
                // idle, but shutdown() is also reachable from a resource reload, so wait here too —
                // it costs nothing on a path that runs at most a handful of times per session.
                waitDeviceIdle();
                lib.destroy();
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("NRD shutdown failed", t);
            }
        }
        initialized = false;
        failed = false;
        lib = null;
        initWidth = 0;
        initHeight = 0;
    }

    /** Best-effort {@code vkDeviceWaitIdle} on the renderer's device; ignored if it is unavailable. */
    private static void waitDeviceIdle() {
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            VK10.vkDeviceWaitIdle(device.vkDevice());
        }
    }

    private void init(VulkanDevice device, int width, int height) {
        if (!platformSupported()) {
            throw new IllegalStateException("NRD natives are not bundled for this platform");
        }
        Path shim = extractBundledNative();
        if (shim == null) {
            throw new IllegalStateException("bundled NRD shim missing");
        }
        lib = NrdLibrary.load(shim);

        VkInstance instance = device.vkDevice().getPhysicalDevice().getInstance();
        long gipa;
        int apiVersion;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            gipa = VK10.vkGetInstanceProcAddr(instance, stack.ASCII("vkGetInstanceProcAddr"));
            // LWJGL's VkPhysicalDevice carries no cached properties accessor (the Blaze3D wrapper
            // does, but this path only has the LWJGL handle), so query the struct directly.
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), props);
            apiVersion = props.apiVersion();
        }
        // VK_API_VERSION minor field (a Vulkan 1.x device; NRD needs x >= 2).
        int minor = (apiVersion >> 12) & 0x3FF;
        int rc = lib.init(instance.address(), device.vkDevice().getPhysicalDevice().address(),
                device.vkDevice().address(), gipa,
                device.graphicsQueue().queueFamilyIndex(), Math.max(2, minor), width, height);
        if (rc != 0) {
            throw new IllegalStateException("nrdshim_init failed: " + rc + " last=" + lib.lastResult());
        }
        initWidth = width;
        initHeight = height;
        CausticaMod.LOGGER.info("NRD {} initialized at {}x{} ({})", lib.versionString(), width, height, shim);
    }

    private static Path extractBundledNative() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("caustica-nrd")
                .resolve("natives").resolve(PLATFORM_DIR);
        try {
            Files.createDirectories(dir);
            Path dst = dir.resolve(SHIM_NAME);
            String resource = "/caustica/natives/" + PLATFORM_DIR + "/" + SHIM_NAME;
            try (InputStream in = NrdRuntime.class.getResourceAsStream(resource)) {
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
            CausticaMod.LOGGER.warn("Could not extract bundled NRD shim to {}", dir, e);
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
