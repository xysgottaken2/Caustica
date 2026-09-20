package dev.xys.vulkanrt.render;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

final class ShaderResourcesTest {
    @Test void everyPipelineStageHasBuildTimeSpirv() throws Exception {
        for (String stage : new String[]{"rgen", "rmiss", "rchit"}) {
            try (var stream = getClass().getResourceAsStream("/assets/native_vulkan_rt/spirv/primary." + stage + ".spv")) {
                assertNotNull(stream);
                byte[] bytes = stream.readAllBytes();
                assertTrue(bytes.length >= 20);
                assertEquals(0, bytes.length % 4);
                var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(0x07230203, header.getInt());
                assertEquals(0x00010400, header.getInt());
            }
        }
    }
}
