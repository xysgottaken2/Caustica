package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtBindlessSlotTableTest {
    private static final int LIMIT = 8;

    /** Stand-in for a GpuTextureView: an object identity paired with the Vulkan handle it currently owns. */
    private static final class FakeView {
        final long handle;

        FakeView(long handle) {
            this.handle = handle;
        }
    }

    private static RtBindlessSlotTable table() {
        return new RtBindlessSlotTable(() -> LIMIT);
    }

    @Test
    void sharedTexturesDedupeByHandle() {
        RtBindlessSlotTable slots = table();

        int first = slots.shared(0x1000L);
        int again = slots.shared(0x1000L);
        int other = slots.shared(0x2000L);

        assertEquals(first, again, "one image must not consume two slots");
        assertNotEquals(first, other);
        assertEquals(List.of(new RtBindlessSlotTable.Write(first, 0x1000L),
                new RtBindlessSlotTable.Write(other, 0x2000L)), slots.drainPending());
    }

    @Test
    void unresolvedHandleFallsBackToSlotZero() {
        RtBindlessSlotTable slots = table();

        assertEquals(0, slots.shared(0L));
        assertEquals(0, slots.perOwner(new FakeView(0L), 0L));
        assertEquals(0, slots.perOwner(null, 0x1000L));
        assertTrue(slots.drainPending().isEmpty(), "a fallback must never queue a descriptor write");
    }

    @Test
    void perOwnerReusesOneSlotWhileTheOwnerStaysAlive() {
        RtBindlessSlotTable slots = table();
        FakeView page = new FakeView(0x1000L);

        int first = slots.perOwner(page, page.handle);
        slots.drainPending();
        int again = slots.perOwner(page, page.handle);

        assertEquals(first, again, "a live page must keep its slot across frames");
        assertTrue(slots.drainPending().isEmpty(), "no rewrite is owed while the image is unchanged");
    }

    /**
     * The Force-Unicode-Font regression. Toggling the option makes the font system destroy its atlas pages
     * and stitch new ones; a driver is free to hand the new page the handle the dead page had. Keying by
     * handle alone reports a cache hit, queues no descriptor write, and leaves the slot describing freed
     * memory — sign text then samples garbage. A page is therefore identified by its owning object.
     */
    @Test
    void recreatedPageReusingADeadHandleGetsAFreshDescriptorWrite() {
        RtBindlessSlotTable slots = table();
        FakeView before = new FakeView(0x1000L);
        int oldSlot = slots.perOwner(before, before.handle);
        slots.drainPending(); // descriptor for the original image is now live

        // Font re-selected: the old page is destroyed and the replacement inherits its handle value.
        FakeView after = new FakeView(0x1000L);
        int newSlot = slots.perOwner(after, after.handle);

        assertNotEquals(oldSlot, newSlot, "a replaced page must not silently reuse the dead page's slot");
        assertEquals(List.of(new RtBindlessSlotTable.Write(newSlot, 0x1000L)), slots.drainPending(),
                "the replacement image must be written into its slot before the next trace");
    }

    /** Toggling the option back off must recover too, rather than staying corrupted. */
    @Test
    void repeatedFontTogglesKeepResolvingToAFreshlyWrittenSlot() {
        RtBindlessSlotTable slots = table();
        int previous = -1;
        for (int toggle = 0; toggle < 4; toggle++) {
            FakeView page = new FakeView(0x1000L); // same recycled handle every time
            int slot = slots.perOwner(page, page.handle);

            assertNotEquals(previous, slot, "each re-created page needs its own slot");
            assertEquals(List.of(new RtBindlessSlotTable.Write(slot, 0x1000L)), slots.drainPending());
            previous = slot;
        }
    }

    @Test
    void sharedLookupsFollowTheNewestSlotForARecycledHandle() {
        RtBindlessSlotTable slots = table();
        FakeView page = new FakeView(0x1000L);
        int stale = slots.shared(0x1000L);      // handle first seen through the shared path
        int fresh = slots.perOwner(page, page.handle); // then re-created and claimed by an owner

        assertNotEquals(stale, fresh);
        assertEquals(fresh, slots.shared(0x1000L),
                "the handle must resolve to the slot describing the live image, not the superseded one");
    }

    @Test
    void exhaustedArrayFallsBackWithoutCachingSoAResetCanRetry() {
        RtBindlessSlotTable slots = table();
        for (int i = 1; i < LIMIT; i++) {
            assertEquals(i, slots.shared(0x1000L + i));
        }
        assertEquals(LIMIT - 1, slots.allocatedSlots());

        FakeView overflow = new FakeView(0xDEAD00L);
        assertEquals(0, slots.shared(0xBEEF00L), "over capacity must fall back to slot 0");
        assertEquals(0, slots.perOwner(overflow, overflow.handle));

        slots.reset();

        assertEquals(0, slots.allocatedSlots());
        assertEquals(1, slots.perOwner(overflow, overflow.handle), "reset must let a rejected view retry");
    }

    @Test
    void resetClearsPendingWritesAndRestartsNumbering() {
        RtBindlessSlotTable slots = table();
        slots.shared(0x1000L);
        assertTrue(slots.hasPending());

        slots.reset();

        assertTrue(slots.drainPending().isEmpty(), "writes for a torn-down pipeline must not be replayed");
        assertEquals(1, slots.shared(0x2000L));
    }
}
