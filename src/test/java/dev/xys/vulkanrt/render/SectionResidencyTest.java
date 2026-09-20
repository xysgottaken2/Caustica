package dev.xys.vulkanrt.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.xys.vulkanrt.render.SectionResidency.TlasAction.*;

final class SectionResidencyTest {
    private static SectionResidency.Diff<Long> diff(Map<Long,Object> old, Map<Long,Object> draws) {
        return SectionResidency.diff(old,draws,(a,b) -> a == b);
    }
    @Test void initialMultipleSectionsAreAllBuiltThenStableFrameReusesEverything() {
        var draws = new TreeMap<Long,Object>();
        for (long i=0;i<400;i++) draws.put(i,new Object());
        var initial = diff(Map.of(),draws);
        assertEquals(400,initial.build().size()); assertTrue(initial.reuse().isEmpty());
        assertEquals(BUILD,SectionResidency.tlasAction(0,400,initial.changed(),false));
        var stable = diff(draws,draws);
        assertEquals(400,stable.reuse().size()); assertFalse(stable.changed());
        assertEquals(REUSE,SectionResidency.tlasAction(400,400,stable.changed(),false));
    }
    @Test void oneBlockEditRebuildsOnlyItsMeshAndUpdatesSameCountTlas() {
        var a=new Object(); var b=new Object(); var edited=new Object();
        var old=Map.of(1L,a,2L,b); var next=Map.of(1L,edited,2L,b);
        var diff=diff(old,next);
        assertEquals(java.util.List.of(1L),diff.build());
        assertEquals(java.util.List.of(2L),diff.reuse());
        assertEquals(java.util.List.of(1L),diff.retire());
        assertEquals(UPDATE,SectionResidency.tlasAction(2,2,diff.changed(),false));
        // Planning/failed recording has no side effects on the committed resources.
        assertSame(a,old.get(1L)); assertSame(b,old.get(2L));
    }
    @Test void enterLeaveAndCompleteUnloadHaveCorrectTlasModes() {
        Object a=new Object(), b=new Object(), c=new Object();
        var initial=Map.of(1L,a,2L,b); var entered=Map.of(1L,a,2L,b,3L,c);
        assertEquals(java.util.List.of(3L),diff(initial,entered).build());
        assertEquals(BUILD,SectionResidency.tlasAction(2,3,true,false));
        var left=Map.of(1L,a,3L,c);
        assertEquals(java.util.List.of(2L),diff(entered,left).retire());
        assertEquals(BUILD,SectionResidency.tlasAction(3,2,true,false));
        assertEquals(2,diff(left,Map.of()).retire().size());
        assertEquals(EMPTY,SectionResidency.tlasAction(2,0,true,false));
        // One section exits while another enters: same count still permits UPDATE.
        assertEquals(UPDATE,SectionResidency.tlasAction(2,2,diff(initial,left).changed(),false));
    }
    @Test void drawReorderingDoesNotRebuildAndAnchorChangeOnlyUpdatesTlas() {
        var a=new Object(); var b=new Object();
        var original=new LinkedHashMap<Long,Object>(); original.put(1L,a); original.put(2L,b);
        var reversed=new LinkedHashMap<Long,Object>(); reversed.put(2L,b); reversed.put(1L,a);
        var diff=diff(original,reversed);
        assertFalse(diff.changed()); assertEquals(2,diff.reuse().size());
        assertEquals(REUSE,SectionResidency.tlasAction(2,2,false,false));
        assertEquals(UPDATE,SectionResidency.tlasAction(2,2,false,true));
    }
    @Test void onlyRemovedOrReplacedResourcesAreDeferredAfterCommit() {
        Object kept=new Object(), removed=new Object(), replaced=new Object(), replacement=new Object();
        var old=Map.of(1L,kept,2L,removed,3L,replaced);
        var next=Map.of(1L,kept,3L,replacement);
        var queue=new ArrayList<Object>();
        diff(old,next); // A plan alone, including a rollback before enqueue, retires nothing.
        assertTrue(queue.isEmpty());
        assertEquals(2,SectionResidency.retireReplaced(old,next,queue::add));
        assertTrue(queue.contains(removed)); assertTrue(queue.contains(replaced));
        assertFalse(queue.contains(kept)); assertFalse(queue.contains(replacement));
        assertEquals(0,SectionResidency.retireReplaced(next,next,queue::add));
        assertEquals(2,SectionResidency.retireReplaced(next,Map.of(),queue::add));
    }
}
