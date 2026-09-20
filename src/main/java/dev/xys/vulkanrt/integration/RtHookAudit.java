package dev.xys.vulkanrt.integration;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.xys.vulkanrt.mixin.FrontendGpuDeviceAccessor;
import net.minecraft.client.renderer.GameRenderer;
import org.slf4j.LoggerFactory;

/** Marker checks force target transformation, not class initialization or native allocation.
 * A bootstrap-only/wrongly packaged JAR must not silently pass as an integrated renderer. */
public final class RtHookAudit {
    public interface DeviceHook {}
    public interface InitHook {}
    public interface FrameHook {}
    public interface SubmitHook {}
    public static void verify() {
        verify(DeviceHook.class, VulkanBackend.class);
        verify(InitHook.class, RenderSystem.class);
        verify(FrameHook.class, GameRenderer.class);
        verify(SubmitHook.class, VulkanCommandEncoder.class);
        verify(FrontendGpuDeviceAccessor.class, FrontendGpuDevice.class);
    }
    private static void verify(Class<?> marker, Class<?> target) {
        var log = LoggerFactory.getLogger("native_vulkan_rt");
        if (marker.isAssignableFrom(target)) log.info("[RT] Mixin applied: {}", target.getName());
        else log.error("[RT] INTEGRATION FAILURE: Mixin missing on {}. Check native_vulkan_rt.mixins.json registration and installed JAR; RT cannot activate.", target.getName());
    }
    private RtHookAudit() {}
}
