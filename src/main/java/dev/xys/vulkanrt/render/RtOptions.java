package dev.xys.vulkanrt.render;

/** Startup-only experimental switches. RUNTIME VERIFIED: NO. */
public final class RtOptions {
    public static final boolean ENABLED = Boolean.getBoolean("nativevulkanrt.enabled");
    public static final boolean CHUNKS = "chunks".equals(System.getProperty("nativevulkanrt.scene", "triangle"));
    private RtOptions() {}
}
