package dev.xys.vulkanrt.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.3 concrete frontend, NOT the 26.2 GpuDevice class. */
@Mixin(FrontendGpuDevice.class)
public interface FrontendGpuDeviceAccessor {
    @Accessor("backend") GpuDeviceBackend nativeVulkanRt$backend();
}
