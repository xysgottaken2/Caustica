package dev.comfyfluffy.caustica.fsr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;

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
 * Shared AMD FidelityFX lifetime for the mod: locates + loads the FSR shim (with the signed
 * {@code amd_fidelityfx_vk.dll} runtime next to it), and runs {@code fsrshim_init} exactly once per
 * Vulkan device. Modeled on {@code NgxRuntime} — the upscaler feature owns only its create/dispatch/
 * release; teardown ordering (feature first, runtime second, device last) mirrors the NGX path.
 *
 * <p>Windows-only for now: the FidelityFX SDK ships signed prebuilt runtimes for Windows; a Linux
 * build means compiling the ffx-api VK backend from source (follow-up work). On other platforms
 * {@link #platformSupported()} reports false and the upscaler selector simply does not offer FSR 3.
 */
public final class FsrRuntime {
    public static final FsrRuntime INSTANCE = new FsrRuntime();

    private static final String SHIM_NAME = "fsrshim.dll";
    private static final String RUNTIME_NAME = "amd_fidelityfx_vk.dll";
    private static final String PLATFORM_DIR = "windows-x64";

    private FsrLibrary lib;
    private boolean initialized;
    private boolean failed;

    private FsrRuntime() {
    }

    /** Whether bundled FSR natives exist for this host (the selector's availability gate). */
    public static boolean platformSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
        if (!(os.contains("win") && x64)) {
            return false;
        }
        // The natives are only bundled when the shim was built (see bundleFsrNatives in build.gradle);
        // probe the mod's resources rather than promising an upscaler the runtime cannot deliver.
        try (InputStream probe = FsrRuntime.class.getResourceAsStream(
                "/caustica/natives/" + PLATFORM_DIR + "/" + SHIM_NAME)) {
            return probe != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Ensure the shim is loaded and initialized for {@code device}, returning the shared
     * {@link FsrLibrary}, or {@code null} if it is unavailable. Idempotent; latches failure so it
     * isn't retried every frame (cleared by {@link #shutdown()} so a fresh device can re-init).
     */
    public synchronized FsrLibrary acquire(VulkanDevice device) {
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
            CausticaMod.LOGGER.error("FSR shim init failed; FSR upscaling disabled", t);
            return null;
        }
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    /** The shared library once {@link #acquire} has succeeded, else {@code null}. */
    public FsrLibrary library() {
        return lib;
    }

    /**
     * Reset the runtime after device teardown. The upscale context itself must already be destroyed
     * by {@code RtFsrUpscaler.destroy()} (it holds device resources), the same ordering the NGX
     * features use.
     */
    public synchronized void shutdown() {
        initialized = false;
        failed = false;
        lib = null;
    }

    private void init(VulkanDevice device) {
        if (!platformSupported()) {
            throw new IllegalStateException("FSR natives are not bundled for this platform");
        }
        Path shim = extractBundledNative(SHIM_NAME);
        Path runtime = extractBundledNative(RUNTIME_NAME);
        if (shim == null || runtime == null) {
            throw new IllegalStateException("bundled FSR natives missing (shim=" + shim + " runtime=" + runtime + ")");
        }

        lib = FsrLibrary.load(shim);

        VkInstance instance = device.vkDevice().getPhysicalDevice().getInstance();
        long gdpa;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            gdpa = VK10.vkGetInstanceProcAddr(instance, stack.ASCII("vkGetDeviceProcAddr"));
        }
        // The shim loads (not links) the AMD runtime, so it needs its extracted path.
        try (Arena arena = Arena.ofConfined()) {
            int rc = lib.init(device.vkDevice().address(), device.vkDevice().getPhysicalDevice().address(),
                    gdpa, wideString(arena, runtime.toString()));
            if (rc != 0) {
                throw new IllegalStateException("fsrshim_init failed: " + rc);
            }
        }
        CausticaMod.LOGGER.info("FSR shim initialized ({})", shim);
    }

    // Native wchar_t is 2 bytes (UTF-16) on Windows, the only supported platform (same convention
    // NgxRuntime uses for its wchar_t paths).
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
        Path dir = FabricLoader.getInstance().getGameDir().resolve("caustica-fsr")
                .resolve("natives").resolve(PLATFORM_DIR);
        try {
            Files.createDirectories(dir);
            Path dst = dir.resolve(name);
            String resource = "/caustica/natives/" + PLATFORM_DIR + "/" + name;
            try (InputStream in = FsrRuntime.class.getResourceAsStream(resource)) {
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
            CausticaMod.LOGGER.warn("Could not extract bundled FSR native {} to {}", name, dir, e);
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
