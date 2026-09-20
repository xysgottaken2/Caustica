package dev.xys.vulkanrt.geometry;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TerrainDrawCaptureTest {
    @Test void acceptsActualDrawOutsideTheOldNeighborhood() {
        long farDraw = SectionPos.asLong(0,2,8);
        assertTrue(TerrainDrawCapture.prefer(farDraw,10000,null,Double.POSITIVE_INFINITY,null));
        assertTrue(TerrainDrawCapture.prefer(farDraw,10000,SectionPos.asLong(20,2,8),100000,null));
    }
    @Test void preservesPreferredEligibleDrawButCanReplaceItWhenAbsent() {
        assertFalse(TerrainDrawCapture.prefer(2,1,1L,100,1L));
        assertTrue(TerrainDrawCapture.prefer(1,100,2L,1,1L));
        assertTrue(TerrainDrawCapture.prefer(2,1,null,100,1L));
    }
    @Test void noHookIsNotMisreportedAsUnloadedGeometryAndFrameReceiptExpires() {
        TerrainDrawCapture.beginFrame();
        assertEquals("TERRAIN_EXTRACTION_HOOK_NOT_REACHED",TerrainDrawCapture.failure());
        assertNull(TerrainDrawCapture.selected());
        assertFalse(TerrainDrawCapture.current(new TerrainDrawCapture.Draw(-1,0,null,null,null,0,null)));
        assertFalse(TerrainDrawCapture.ready(null,null));
    }
    @Test void reportedPlayerAndPinAreNotTheSameNeighborhood() {
        long camera = SectionPos.asLong((int)Math.floor(11.869/16),(int)Math.floor(79.0/16),(int)Math.floor(79.863/16));
        assertEquals(0,SectionPos.x(camera)); assertEquals(4,SectionPos.y(camera)); assertEquals(4,SectionPos.z(camera));
        long pin = ChunkCoordinates.parseSection("0,5,0");
        assertEquals(-4,SectionPos.z(pin)-SectionPos.z(camera));
    }
}
