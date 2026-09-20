package dev.xys.vulkanrt.render;

/** Startup-only experimental switches. RUNTIME VERIFIED: NO. */
public final class RtOptions {
    public static final boolean ENABLED = Boolean.getBoolean("nativevulkanrt.enabled");
    public static final boolean CHUNKS = "chunks".equals(System.getProperty("nativevulkanrt.scene", "triangle"));
    public static final int MAX_SECTIONS = Math.clamp(Integer.getInteger("nativevulkanrt.maxSections", 128), 1, 512);
    public static final int BUILDS_PER_FRAME = Math.clamp(Integer.getInteger("nativevulkanrt.buildsPerFrame", 2), 1, 8);
    private RtOptions() {}
}
