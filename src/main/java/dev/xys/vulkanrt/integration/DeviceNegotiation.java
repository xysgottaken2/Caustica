package dev.xys.vulkanrt.integration;

import dev.xys.vulkanrt.render.RayTracingCapabilities;
import org.lwjgl.vulkan.VkDevice;
import java.util.concurrent.ConcurrentHashMap;

/** Records only successfully enabled features, not merely reported physical-device support. */
public final class DeviceNegotiation {
    private record Enabled(VkDevice device, RayTracingCapabilities capabilities) {}
    private static final ConcurrentHashMap<Long, Enabled> ENABLED = new ConcurrentHashMap<>();
    public static void enabled(VkDevice device, RayTracingCapabilities caps) { ENABLED.put(device.address(), new Enabled(device, caps)); }
    public static RayTracingCapabilities enabledFor(VkDevice device) {
        Enabled enabled = ENABLED.get(device.address());
        // Native handles can be reused after failed vanilla bring-up. Match the actual Java device too.
        return enabled != null && enabled.device() == device ? enabled.capabilities() : null;
    }
    public static boolean hasEnabledDevice() { return !ENABLED.isEmpty(); }
    public static void forget(VkDevice device) { ENABLED.remove(device.address()); }
    private DeviceNegotiation() {}
}
