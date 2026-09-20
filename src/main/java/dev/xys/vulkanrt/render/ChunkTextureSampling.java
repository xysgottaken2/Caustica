package dev.xys.vulkanrt.render;

import java.util.Locale;

/** Restart-selected A/B modes on the SAME borrowed vanilla atlas. No sampler/texture allocation. */
public enum ChunkTextureSampling {
    TEXEL(0), LINEAR(1);
    public static final int PROBE_BYTES = 160; // ten std430 16-byte vectors in chunks.rgen
    public final int shaderId;
    ChunkTextureSampling(int shaderId) { this.shaderId = shaderId; }
    public static ChunkTextureSampling parse(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "texel" -> TEXEL;
            case "linear" -> LINEAR;
            default -> throw new IllegalArgumentException("nativevulkanrt.textureSampling must be texel or linear: " + value);
        };
    }
    public static ChunkTextureSampling configured() {
        return parse(System.getProperty("nativevulkanrt.textureSampling", "texel"));
    }
}
