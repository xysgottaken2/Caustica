package dev.xys.vulkanrt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** JVM-only checks. Passing these tests does not establish that Minecraft or Vulkan starts. */
final class BootstrapTest {
    @Test
    void initializerDoesNotRequireNativeGraphics() {
        assertDoesNotThrow(() -> new RayTracingMod().onInitializeClient());
    }

    @Test
    void requiredMixinsAreRegisteredAndPackaged() throws Exception {
        try (var input = getClass().getResourceAsStream("/native_vulkan_rt.mixins.json")) {
            assertNotNull(input);
            var config = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(config.get("required").getAsBoolean());
            assertEquals(1, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
            var names = new java.util.HashSet<String>();
            for (var name : config.getAsJsonArray("client")) {
                names.add(name.getAsString());
                assertNotNull(getClass().getResource("/" + config.get("package").getAsString().replace('.', '/')
                        + "/" + name.getAsString() + ".class"));
            }
            assertTrue(names.containsAll(java.util.Set.of("VulkanBackendMixin", "FrontendGpuDeviceAccessor", "RenderSystemMixin",
                    "GameRendererMixin", "FirstPersonHandsAndItemsRendererMixin", "VulkanCommandEncoderMixin", "VulkanDeviceMixin", "LevelRendererMixin", "ChunkSectionsToRenderMixin")));
        }
    }

    @Test
    void metadataIsExpandedAndRestrictedToTheResearchedRelease() throws Exception {
        try (var input = getClass().getResourceAsStream("/fabric.mod.json")) {
            assertNotNull(input);
            JsonObject metadata = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("client", metadata.get("environment").getAsString());
            assertEquals("26.3", metadata.getAsJsonObject("depends").get("minecraft").getAsString());
            assertEquals(">=25", metadata.getAsJsonObject("depends").get("java").getAsString());
            assertFalse(metadata.get("version").getAsString().contains("${"));
            assertEquals(RayTracingMod.class.getName(), metadata.getAsJsonObject("entrypoints")
                    .getAsJsonArray("client").get(0).getAsString());
            assertEquals("native_vulkan_rt.mixins.json", metadata.getAsJsonArray("mixins").get(0).getAsString());
        }
    }
}
