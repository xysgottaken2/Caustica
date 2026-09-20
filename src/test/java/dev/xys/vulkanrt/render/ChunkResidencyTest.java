package dev.xys.vulkanrt.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChunkResidencyTest {
    @Test void unchangedAcceptedAllocationDoesNotRebuild() {
        Object owner=new Object(), mesh=new Object(), buffer=new Object();
        assertTrue(WorldGeometryManager.sameSource(owner,mesh,buffer,112,owner,mesh,buffer,112));
    }
    @Test void rebuildUnloadRecycledOwnerAndHeapChangesInvalidateSnapshot() {
        Object owner=new Object(), mesh=new Object(), buffer=new Object();
        assertFalse(WorldGeometryManager.sameSource(owner,mesh,buffer,112,owner,new Object(),buffer,112));
        assertFalse(WorldGeometryManager.sameSource(owner,mesh,buffer,112,new Object(),mesh,buffer,112));
        assertFalse(WorldGeometryManager.sameSource(owner,mesh,buffer,112,owner,mesh,new Object(),112));
        assertFalse(WorldGeometryManager.sameSource(owner,mesh,buffer,112,owner,mesh,buffer,224));
        assertFalse(WorldGeometryManager.sameSource(null,null,null,-1,owner,mesh,buffer,112));
    }
}
