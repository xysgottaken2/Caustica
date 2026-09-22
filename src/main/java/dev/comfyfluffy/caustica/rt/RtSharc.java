package dev.comfyfluffy.caustica.rt;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;

/**
 * Host-side state for the experimental SHaRC (Spatially Hashed Radiance Cache) integration.
 *
 * <p>SHaRC is shader-only by design (NVIDIA distributes it as shader sources), so the host does not
 * need a native library or an extra descriptor binding. Caustica allocates one persistent storage
 * buffer, zeroes it, and publishes its raw device address through {@code WorldPush.sharcCacheAddr}
 * every frame; {@code shaders/world/sharc.slang} owns the hash, temporal blend and the actual cache
 * entry format.
 *
 * <p>The buffer is device-local (the GPU is the only consumer) and is cleared with a
 * {@code vkCmdFillBuffer} on the same queue, so a drop-in reset does not need a host-visible mapping
 * or a coherent-memory fallback. The entry count and the per-entry layout are pinned by
 * {@code sharc.slang} ({@value #ENTRY_BYTES} bytes per entry).
 */
public final class RtSharc {
    /** Single current instance, shared by the renderer and the options UI. */
    public static final RtSharc INSTANCE = new RtSharc();

    /** Must match {@code SharcEntry} in {@code shaders/world/sharc.slang}. */
    public static final int ENTRY_BYTES = 48;
    public static final int MIN_ENTRIES = 2048;
    /**
     * Upper capacity bound (1 Mi entries = 48 MiB). NVIDIA recommends power-of-two capacities of
     * ~4M for their samples; single-slot hashing needs the headroom far more than their bucketed
     * grid does, but 48 MiB is already generous next to everything else Caustica allocates.
     */
    public static final int MAX_ENTRIES = 1 << 20;

    private RtBuffer cache;
    private int entryCount;
    private boolean clearRequested;

    private RtSharc() {
    }

    public boolean enabled() {
        return CausticaConfig.Rt.Sharc.ENABLED.value();
    }

    public int configuredEntryCount() {
        return Math.clamp(CausticaConfig.Rt.Sharc.CACHE_ENTRIES.value(), MIN_ENTRIES, MAX_ENTRIES);
    }

    /** Create (or resize/clear) the cache buffer for the current config. Called on the render thread. */
    public synchronized void ensure(RtContext ctx) {
        int wanted = configuredEntryCount();
        if (cache != null && entryCount != wanted) {
            rebuild(ctx, wanted);
            return;
        }
        if (cache == null) {
            rebuild(ctx, wanted);
        }
        // An explicit reset is handled by the caller after idling the device, so it is not raced
        // against a trace that may still point at the old contents.
    }

    public synchronized void requestClear() {
        clearRequested = true;
    }

    public synchronized boolean clearRequested() {
        return clearRequested;
    }

    public long address() {
        return cache != null ? cache.deviceAddress : 0L;
    }

    public int entryCount() {
        return cache != null ? entryCount : 0;
    }

    public synchronized void releaseIfDisabled(RtContext ctx) {
        if (!enabled() && cache != null) {
            destroyNow(ctx);
        }
    }

    public synchronized void destroy(RtContext ctx) {
        destroyNow(ctx);
    }

    private void rebuild(RtContext ctx, int wanted) {
        boolean resize = cache != null;
        if (resize) {
            // Wait for any in-flight trace that still points at the old buffer before freeing it.
            ctx.waitIdle();
            cache.destroy();
            logDebug("SHaRC cache buffer resized: new entries={}, total={} MiB", wanted, bytesMiB(wanted));
        } else {
            logDebug("SHaRC cache buffer allocated: entries={}, total={} MiB, entryBytes={}",
                    wanted, bytesMiB(wanted), ENTRY_BYTES);
        }
        long bytes = (long) wanted * ENTRY_BYTES;
        int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        cache = ctx.createBuffer(bytes, usage, false, "rt sharc radiance cache");
        entryCount = wanted;
        clearNow(ctx);
    }

    /** Clear the whole cache on the command queue; {@code submitSync} also waits for completion. */
    public synchronized void clearNow(RtContext ctx) {
        if (cache != null) {
            ctx.submitSync(cmd -> {
                VK10.vkCmdFillBuffer(cmd, cache.handle, 0L, cache.size, 0);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                }
            });
        }
        clearRequested = false;
    }

    private void destroyNow(RtContext ctx) {
        if (cache != null) {
            if (ctx != null) {
                ctx.waitIdle();
            }
            cache.destroy();
            cache = null;
            entryCount = 0;
            logDebug("SHaRC cache buffer released");
        }
        clearRequested = false;
    }

    private static double bytesMiB(int entries) {
        return (long) entries * ENTRY_BYTES / (1024.0 * 1024.0);
    }

    private static void logDebug(String message, Object... args) {
        CausticaMod.LOGGER.info("[SHaRC] " + message, args);
    }
}
