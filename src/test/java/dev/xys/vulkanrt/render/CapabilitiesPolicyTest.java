package dev.xys.vulkanrt.render;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class CapabilitiesPolicyTest {
    private RayTracingCapabilities capabilities(Set<String> extensions, boolean bda, List<String> missing) {
        return new RayTracingCapabilities(extensions, missing, true, true, bda,
                false, false, false, 32, 32, 64, 4096, 256, 1_000_000, 1_000_000, 1_000_000);
    }
    @Test void rayQueryInt64AndBindlessAreNotArtificialRequirements() {
        assertTrue(capabilities(RayTracingCapabilities.REQUIRED_EXTENSIONS, true, List.of()).supported());
    }
    @Test void extensionNamesAloneDoNotEstablishSupport() {
        assertFalse(capabilities(RayTracingCapabilities.REQUIRED_EXTENSIONS, false, List.of()).supported());
        assertFalse(capabilities(Set.of(), true, List.of()).supported());
        assertFalse(capabilities(RayTracingCapabilities.REQUIRED_EXTENSIONS, true, List.of("bad format")).supported());
    }
}
