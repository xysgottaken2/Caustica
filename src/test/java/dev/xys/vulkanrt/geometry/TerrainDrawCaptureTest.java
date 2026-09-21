package dev.xys.vulkanrt.geometry;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

final class TerrainDrawCaptureTest {
    private static TerrainDrawCapture.Draw receipt(long section) {
        return new TerrainDrawCapture.Draw(-1,section,null,null,null,0,null);
    }
    @Test void collectsAllDifferentSectionsIncludingDrawsOutsideTheOldNeighborhood() {
        var draws = new TreeMap<Long, TerrainDrawCapture.Draw>();
        for (int x = -10; x < 10; x++) for (int z = -10; z < 10; z++) {
            long node = SectionPos.asLong(x,4,z);
            TerrainDrawCapture.retain(draws,receipt(node),null);
        }
        assertEquals(400,draws.size());
        assertTrue(draws.containsKey(SectionPos.asLong(-3,4,5)));
        assertTrue(draws.containsKey(SectionPos.asLong(-2,4,4)));
        assertTrue(draws.containsKey(SectionPos.asLong(-2,4,5)));
    }
    @Test void duplicateSectionIsNotAnotherInstanceAndPinRemainsExact() {
        var draws = new TreeMap<Long, TerrainDrawCapture.Draw>();
        long a = SectionPos.asLong(-2,4,4), b = SectionPos.asLong(-2,4,5);
        var latest = receipt(a);
        TerrainDrawCapture.retain(draws,receipt(a),null);
        TerrainDrawCapture.retain(draws,latest,null);
        TerrainDrawCapture.retain(draws,receipt(b),a);
        assertEquals(1,draws.size()); assertSame(latest,draws.get(a));
    }
    @Test void noHookIsNotMisreportedAsUnloadedGeometryAndFrameReceiptExpires() {
        TerrainDrawCapture.beginFrame();
        assertEquals("TERRAIN_EXTRACTION_HOOK_NOT_REACHED",TerrainDrawCapture.failure());
        assertTrue(TerrainDrawCapture.draws().isEmpty());
        assertEquals(0,TerrainDrawCapture.validSections());
        assertThrows(UnsupportedOperationException.class, () -> TerrainDrawCapture.draws().put(0L,receipt(0)));
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
