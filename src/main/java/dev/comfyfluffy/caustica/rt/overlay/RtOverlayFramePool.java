package dev.comfyfluffy.caustica.rt.overlay;

import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.List;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;

/**
 * Per-frame host-visible vertex/index scratch for overlay passes, shared by every {@link RtOverlayFeature}.
 * Buffers acquired during a frame retire against that frame's exact graphics completion token, so a buffer
 * is never destroyed while the GPU can still read it.
 */
public final class RtOverlayFramePool {
    // Vulkan requires buffer size > 0; a few zero-length overlay draws could otherwise reach acquire() with
    // bytes == 0.
    private static final long MIN_SIZE = 256;

    private final List<RtBuffer> acquiredThisFrame = new ArrayList<>();

    /** A host-visible vertex buffer of at least {@code bytes}, valid for this frame only. */
    public RtBuffer acquireVertex(RtContext ctx, long bytes, String label) {
        return acquire(ctx, bytes, VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, label);
    }

    /** A host-visible index buffer of at least {@code bytes}, valid for this frame only. */
    public RtBuffer acquireIndex(RtContext ctx, long bytes, String label) {
        return acquire(ctx, bytes, VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT, label);
    }

    private RtBuffer acquire(RtContext ctx, long bytes, int usage, String label) {
        RtBuffer b = ctx.createBuffer(Math.max(bytes, MIN_SIZE), usage, true, label);
        acquiredThisFrame.add(b);
        return b;
    }

    /** Retire everything acquired this frame once its overlay commands have completed. */
    public void endFrame(RtContext ctx, RtGpuExecutor.GraphicsUse graphicsUse) {
        if (acquiredThisFrame.isEmpty()) {
            return;
        }
        List<RtBuffer> retired = List.copyOf(acquiredThisFrame);
        ctx.gpuExecutor().retireAfterGraphics(graphicsUse, () -> retired.forEach(RtBuffer::destroy));
        acquiredThisFrame.clear();
    }

    /** Immediate teardown of unpublished buffers; queued buffers are owned by the GPU executor. */
    public void destroy() {
        for (RtBuffer b : acquiredThisFrame) {
            b.destroy();
        }
        acquiredThisFrame.clear();
    }
}
