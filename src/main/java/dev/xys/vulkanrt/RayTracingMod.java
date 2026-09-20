package dev.xys.vulkanrt;

import dev.xys.vulkanrt.render.RtOptions;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.LoggerFactory;

public final class RayTracingMod implements ClientModInitializer {
    @Override public void onInitializeClient() {
        var log = LoggerFactory.getLogger("native_vulkan_rt");
        log.info("[RT] Native Vulkan KHR ray tracing implementation loaded. RUNTIME VERIFIED: NO");
        log.info("[RT] Requested={}, scene={}; activation additionally requires successful device negotiation", RtOptions.ENABLED, RtOptions.CHUNKS ? "opaque chunks" : "test triangle");
        if (!RtOptions.ENABLED) log.info("[RT] Opt in with -Dnativevulkanrt.enabled=true; select -Dnativevulkanrt.scene=triangle|chunks before starting Minecraft");
    }
}
