package dev.xys.vulkanrt.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SbtAlignmentTest {
    @Test void alignsAbsoluteAddressesAndStrideSeparately() {
        assertEquals(0x1040, GpuBuffer.alignUp(0x1008, 64));
        assertEquals(32, GpuBuffer.alignUp(24, 32));
        assertEquals(64, GpuBuffer.alignUp(32, 64));
        assertEquals(64, GpuBuffer.alignUp(64, 64));
        assertThrows(IllegalArgumentException.class, () -> GpuBuffer.alignUp(1, 3));
        assertThrows(ArithmeticException.class, () -> GpuBuffer.alignUp(Long.MAX_VALUE, 64));
    }
}
