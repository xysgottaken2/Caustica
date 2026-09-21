package dev.xys.vulkanrt.geometry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.xys.vulkanrt.geometry.SectionGeometrySanity.Failure.*;

final class SectionGeometrySanityTest {
    @Test void acceptsNonzeroRangeOfOneRealFormatSection() {
        assertEquals(OK, SectionGeometrySanity.rangeFailure(36,false,28,0,true,112,4096,0x1000,false,true));
    }
    @Test void eachBadRangeHasAnExplicitReason() {
        assertEquals(INVALID_INDEX_COUNT, SectionGeometrySanity.rangeFailure(0,false,28,0,true,112,4096,1,false,true));
        assertEquals(INVALID_INDEX_COUNT, SectionGeometrySanity.rangeFailure(5,false,28,0,true,112,4096,1,false,true));
        assertEquals(ZERO_BUFFER_HANDLE, SectionGeometrySanity.rangeFailure(36,false,28,0,true,112,4096,0,false,true));
        assertEquals(BUFFER_CLOSED, SectionGeometrySanity.rangeFailure(36,false,28,0,true,112,4096,1,true,true));
        assertEquals(COPY_SRC_MISSING, SectionGeometrySanity.rangeFailure(36,false,28,0,true,112,4096,1,false,false));
        assertEquals(RANGE_OUT_OF_BOUNDS, SectionGeometrySanity.rangeFailure(36,false,28,0,true,4000,4096,1,false,true));
        assertEquals(RANGE_OUT_OF_BOUNDS, SectionGeometrySanity.rangeFailure(36,false,28,0,true,-4,4096,1,false,true));
        assertEquals(MISALIGNED_OFFSET, SectionGeometrySanity.rangeFailure(36,false,28,0,true,113,4096,1,false,true));
        assertEquals(POSITION_FORMAT_UNSUPPORTED, SectionGeometrySanity.rangeFailure(36,false,28,0,false,112,4096,1,false,true));
        assertEquals(INVALID_VERTEX_LAYOUT, SectionGeometrySanity.rangeFailure(36,false,28,24,true,112,4096,1,false,true));
        assertEquals(CUSTOM_INDICES_UNSUPPORTED, SectionGeometrySanity.rangeFailure(36,true,28,0,true,112,4096,1,false,true));
        assertEquals(SECTION_TOO_LARGE, SectionGeometrySanity.rangeFailure(6_000_000,false,28,0,true,0,Long.MAX_VALUE,1,false,true));
    }
    @Test void absentSectionCannotPassGeometrySanity() {
        assertEquals(SECTION_NOT_FOUND, SectionGeometrySanity.inspect(null,0,null,null,null));
    }
}
