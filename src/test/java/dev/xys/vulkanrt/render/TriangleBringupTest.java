package dev.xys.vulkanrt.render;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

/** No Vulkan calls: tests pass gating and the reference pixel check, NOT actual GPU output. */
final class TriangleBringupTest {
    @Test void triangleDoesNotDependOnWorldProjectionOrCamera() {
        assertNull(RayTracingRenderer.blockedReason(false, true, false, false, 1920, 1080));
        assertNotNull(RayTracingRenderer.blockedReason(true, true, false, false, 1920, 1080));
    }
    @Test void menuAndMinimizedTargetHaveExplicitReasons() {
        assertTrue(RayTracingRenderer.blockedReason(false, false, false, false, 1920, 1080).contains("world"));
        assertTrue(RayTracingRenderer.blockedReason(false, true, false, false, 0, 0).contains("minimized"));
    }
    @Test void knownBarycentricAndMissSamplesAreAcceptedAcrossExtents() {
        for (int[] extent : new int[][]{{1920,1080}, {801,601}, {4,4}, {2560,1080}, {600,1000}}) {
            var pixels = ByteBuffer.allocate(8);
            for (int value : TriangleReadback.expectedHit(extent[0], extent[1])) pixels.put((byte)value);
            pixels.put(new byte[]{5,6,11,(byte)255}).flip();
            assertTrue(TriangleReadback.matches(pixels, extent[0], extent[1]));
        }
        assertArrayEquals(new int[]{43,43,71,255}, TriangleReadback.expectedHit(801,601));
    }
    @Test void clearOrMissOnlyImageIsNotProofOfTraversal() {
        assertFalse(TriangleReadback.matches(ByteBuffer.allocate(8), 1920, 1080));
        assertFalse(TriangleReadback.matches(ByteBuffer.wrap(new byte[]{5,6,11,(byte)255,5,6,11,(byte)255}),1920,1080));
        assertFalse(TriangleReadback.matches(ByteBuffer.wrap(new byte[]{43,43,71,(byte)255,43,43,71,(byte)255}),1920,1080));
    }
}
