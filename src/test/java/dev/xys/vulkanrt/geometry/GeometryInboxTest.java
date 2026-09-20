package dev.xys.vulkanrt.geometry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GeometryInboxTest {
    @BeforeEach void reset() { GeometryInbox.reset(); }
    @Test void rejectsPreviousWorldAndRecycledSection() {
        Object owner = new Object();
        var stale = new GeometryInbox.Capture(GeometryInbox.epoch(), 42, TriangleMesh.testTriangle());
        GeometryInbox.reset();
        GeometryInbox.publish(owner, 42, stale);
        assertTrue(GeometryInbox.snapshot().sections().isEmpty());
        GeometryInbox.publish(owner, 43, new GeometryInbox.Capture(GeometryInbox.epoch(), 42, TriangleMesh.testTriangle()));
        assertTrue(GeometryInbox.snapshot().sections().isEmpty());
    }
    @Test void staleRemovalDoesNotEraseReplacement() {
        Object old = new Object(), current = new Object();
        GeometryInbox.publish(old, 42, new GeometryInbox.Capture(GeometryInbox.epoch(), 42, TriangleMesh.testTriangle()));
        GeometryInbox.publish(current, 42, new GeometryInbox.Capture(GeometryInbox.epoch(), 42, TriangleMesh.testTriangle()));
        GeometryInbox.remove(old, 42);
        assertEquals(1, GeometryInbox.snapshot().sections().size());
        GeometryInbox.remove(current, 42);
        assertTrue(GeometryInbox.snapshot().sections().isEmpty());
    }
    @Test void handoffIsConsumedExactlyOnce() {
        Object results = new Object();
        var capture = new GeometryInbox.Capture(GeometryInbox.epoch(), 1, TriangleMesh.testTriangle());
        GeometryInbox.attachResult(results, capture);
        assertSame(capture, GeometryInbox.takeResult(results));
        assertNull(GeometryInbox.takeResult(results));
    }
    @Test void residencyIsBounded() {
        for (int i = 0; i < 1024; i++) GeometryInbox.publish(new Object(), i,
                new GeometryInbox.Capture(GeometryInbox.epoch(), i, TriangleMesh.testTriangle()));
        assertTrue(GeometryInbox.snapshot().sections().size() <= dev.xys.vulkanrt.render.RtOptions.MAX_SECTIONS);
    }
}
