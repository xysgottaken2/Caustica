package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.SpriteContentsAccessor;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ARGB;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opacity micromap (VK_EXT_opacity_micromap) classification for terrain cutout geometry.
 * Classifies each triangle's micro-triangles as fully-opaque/transparent/unknown based on
 * the sprite's pixel data, producing an {@link RtAccel.OpacityMicromapInput} the BLAS builder
 * can attach so the driver skips the any-hit shader on provably-opaque micro-triangles.
 *
 * <p>All methods are static; results are cached per (sprite, level, UV triple) since the same
 * cutout face repeats across many sections and re-extracts. Call {@link #clearCache()} on
 * resource reload when sprite identities or pixels may have changed.
 */
final class RtTerrainOmm {
    // 4-state encoding: unknown-opaque still invokes the any-hit alpha test; fully-opaque
    // micro-triangles skip it in traversal. Level 4 → a 16×16 micro-grid aligning 1:1 with
    // MC's 16×16 texel grid so each micro-triangle covers (half of) a single texel.
    private static final int OMM_FULLY_TRANSPARENT = 0;
    private static final int OMM_FULLY_OPAQUE = 1;
    private static final int OMM_UNKNOWN_OPAQUE = 3;
    private static final int OMM_CLASS_MIXED = -1;
    private static final int OMM_CLASS_UNSAFE = -2;
    private static final int OMM_ALPHA_CUTOFF = 128; // any-hit uses alpha >= 0.5 as visible
    private static final byte OMM_UNKNOWN_OPAQUE_BYTE = (byte) (OMM_UNKNOWN_OPAQUE | (OMM_UNKNOWN_OPAQUE << 2)
            | (OMM_UNKNOWN_OPAQUE << 4) | (OMM_UNKNOWN_OPAQUE << 6));
    // Position-independent key: same cutout face across any section/re-extract reuses one result.
    // Cleared on resource reload (markAllDirty) when the atlas changes sprite identities/pixels.
    private static final Map<OmmTriangleKey, OmmTriangleResult> CACHE = new ConcurrentHashMap<>();
    private static final long OMM_STATS_INTERVAL_NANOS = 5_000_000_000L;
    private static final AtomicLong OMM_STATS_SECTIONS = new AtomicLong();
    private static final AtomicLong OMM_STATS_CUTOUT_SECTIONS = new AtomicLong();
    private static final AtomicLong OMM_STATS_MICROMAP_SECTIONS = new AtomicLong();
    private static final AtomicLong OMM_STATS_CUTOUT_TRIS = new AtomicLong();
    private static final AtomicLong OMM_STATS_OPAQUE_MICROS = new AtomicLong();
    private static final AtomicLong OMM_STATS_TRANSPARENT_MICROS = new AtomicLong();
    private static final AtomicLong OMM_STATS_MIXED_MICROS = new AtomicLong();
    private static final AtomicLong OMM_STATS_UNSAFE_MICROS = new AtomicLong();
    private static final AtomicLong OMM_STATS_NULL_SPRITE_MICROS = new AtomicLong();
    private static final AtomicLong OMM_STATS_TOTAL_MICROS = new AtomicLong();
    private static final AtomicLong OMM_STATS_ANIMATED_TRIS = new AtomicLong();
    private static final AtomicLong OMM_STATS_NULL_SPRITE_TRIS = new AtomicLong();
    private static final AtomicLong OMM_STATS_DISABLED_SECTIONS = new AtomicLong();
    private static final AtomicLong OMM_STATS_LAST_LOG = new AtomicLong();

    private RtTerrainOmm() {}

    private static int ommSubdivision() {
        return CausticaConfig.Rt.Omm.SUBDIVISION.value();
    }

    private static boolean ommStats() {
        return CausticaConfig.Rt.Omm.STATS.value();
    }

    /** Drop all cached triangle classifications; call on resource reload when sprite pixels may change. */
    static void clearCache() {
        CACHE.clear();
    }

    /**
     * Classify every triangle in the cutout bucket and build an {@link RtAccel.OpacityMicromapInput}.
     * Returns {@code null} if OMM is disabled, the bucket is empty, or every triangle is UNKNOWN
     * (no useful classification to attach).
     *
     * @param triCount   number of triangles ({@code idx.size() / 3})
     * @param cornerUv   per-triangle corner UVs in primitive order (6 floats/triangle)
     * @param ommSprites one sprite per triangle (null = unknown/fluid, uses UNKNOWN_OPAQUE), valid up to
     *                   {@code ommSpriteCount}
     */
    static RtAccel.OpacityMicromapInput buildInput(int triCount, float[] cornerUv,
                                                   TextureAtlasSprite[] ommSprites, int ommSpriteCount) {
        // Two gates, deliberately separate: ommEnabled() is the DEVICE capability latched at RT
        // bring-up (the Vulkan feature cannot be enabled after device creation), while the config
        // value is read LIVE here — that is what lets the in-game toggle take effect at the next
        // section build without a restart (the UI follows up with a full terrain rebuild so existing
        // sections re-mesh under the new state).
        if (!RtDeviceBringup.ommEnabled() || !CausticaConfig.Rt.Omm.ENABLED.value()) {
            recordOmmStatsDisabled();
            return null;
        }
        if (triCount == 0 || ommSpriteCount != triCount) {
            recordOmmStats(triCount, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
            return null;
        }
        int level = opacityMicromapSubdivisionLevel();
        int microCount = 1 << (level * 2);
        int bytesPerTriangle = Math.max(1, (microCount * 2 + 7) >>> 3);
        byte[] data = new byte[triCount * bytesPerTriangle];
        java.util.Arrays.fill(data, OMM_UNKNOWN_OPAQUE_BYTE);

        int opaqueMicroTriangles = 0;
        int transparentMicroTriangles = 0;
        int mixedMicroTriangles = 0;
        int unsafeMicroTriangles = 0;
        int nullSpriteMicroTriangles = 0;
        int animatedTris = 0;
        int nullSpriteTris = 0;
        for (int t = 0; t < triCount; t++) {
            TextureAtlasSprite sprite = ommSprites[t];
            if (sprite == null) {
                nullSpriteTris++;
                nullSpriteMicroTriangles += microCount;
                continue;
            }
            if (sprite.transparency().isOpaque()) {
                for (int m = 0; m < microCount; m++) {
                    writeOmmValue(data, t, bytesPerTriangle, m, OMM_FULLY_OPAQUE);
                }
                opaqueMicroTriangles += microCount;
                continue;
            }
            if (sprite.isAnimated()) {
                animatedTris++;
            }
            OmmTriangleResult result = classifyTriangleCached(level, bytesPerTriangle, sprite, cornerUv, t);
            System.arraycopy(result.data(), 0, data, t * bytesPerTriangle, bytesPerTriangle);
            OmmMicroCounts counts = result.counts();
            opaqueMicroTriangles += counts.opaque();
            transparentMicroTriangles += counts.transparent();
            mixedMicroTriangles += counts.mixed();
            unsafeMicroTriangles += counts.unsafe();
        }
        int classifiedMicroTriangles = opaqueMicroTriangles + transparentMicroTriangles;
        recordOmmStats(triCount, level, microCount, opaqueMicroTriangles, transparentMicroTriangles,
                mixedMicroTriangles, unsafeMicroTriangles, nullSpriteMicroTriangles, animatedTris, nullSpriteTris,
                classifiedMicroTriangles > 0);
        if (classifiedMicroTriangles == 0) {
            return null;
        }
        byte[] triangles = RtAccel.opacityMicromapTriangles(triCount, level, bytesPerTriangle);
        return new RtAccel.OpacityMicromapInput(data, triangles, triCount, level, bytesPerTriangle);
    }

    private static void recordOmmStatsDisabled() {
        if (!ommStats()) {
            return;
        }
        OMM_STATS_SECTIONS.incrementAndGet();
        OMM_STATS_DISABLED_SECTIONS.incrementAndGet();
        maybeLogOmmStats(0);
    }

    private static void recordOmmStats(int triCount, int level, int microCount, int opaqueMicroTriangles,
                                       int transparentMicroTriangles, int mixedMicroTriangles,
                                       int unsafeMicroTriangles, int nullSpriteMicroTriangles,
                                       int animatedTris, int nullSpriteTris, boolean emitted) {
        if (!ommStats()) {
            return;
        }
        OMM_STATS_SECTIONS.incrementAndGet();
        if (triCount > 0) {
            OMM_STATS_CUTOUT_SECTIONS.incrementAndGet();
            OMM_STATS_CUTOUT_TRIS.addAndGet(triCount);
            OMM_STATS_TOTAL_MICROS.addAndGet((long) triCount * microCount);
            OMM_STATS_OPAQUE_MICROS.addAndGet(opaqueMicroTriangles);
            OMM_STATS_TRANSPARENT_MICROS.addAndGet(transparentMicroTriangles);
            OMM_STATS_MIXED_MICROS.addAndGet(mixedMicroTriangles);
            OMM_STATS_UNSAFE_MICROS.addAndGet(unsafeMicroTriangles);
            OMM_STATS_NULL_SPRITE_MICROS.addAndGet(nullSpriteMicroTriangles);
            OMM_STATS_ANIMATED_TRIS.addAndGet(animatedTris);
            OMM_STATS_NULL_SPRITE_TRIS.addAndGet(nullSpriteTris);
        }
        if (emitted) {
            OMM_STATS_MICROMAP_SECTIONS.incrementAndGet();
        }
        maybeLogOmmStats(level);
    }

    private static void maybeLogOmmStats(int level) {
        long now = System.nanoTime();
        long last = OMM_STATS_LAST_LOG.get();
        if (last != 0L && now - last < OMM_STATS_INTERVAL_NANOS) {
            return;
        }
        if (!OMM_STATS_LAST_LOG.compareAndSet(last, now)) {
            return;
        }
        long totalMicros = OMM_STATS_TOTAL_MICROS.get();
        long opaqueMicros = OMM_STATS_OPAQUE_MICROS.get();
        long transparentMicros = OMM_STATS_TRANSPARENT_MICROS.get();
        long mixedMicros = OMM_STATS_MIXED_MICROS.get();
        long unsafeMicros = OMM_STATS_UNSAFE_MICROS.get();
        long nullSpriteMicros = OMM_STATS_NULL_SPRITE_MICROS.get();
        long unknownMicros = Math.max(0L, totalMicros - opaqueMicros - transparentMicros);
        double opaquePct = totalMicros == 0 ? 0.0 : (opaqueMicros * 100.0) / totalMicros;
        double transparentPct = totalMicros == 0 ? 0.0 : (transparentMicros * 100.0) / totalMicros;
        double unknownPct = totalMicros == 0 ? 0.0 : (unknownMicros * 100.0) / totalMicros;
        CausticaMod.LOGGER.info(
                "RT terrain OMM stats: enabled={}, level={}, sections={}, cutoutSections={}, micromapSections={}, "
                        + "cutoutTris={}, opaqueMicros={}/{} ({}%), transparentMicros={}/{} ({}%), "
                        + "unknownMicros={}/{} ({}%; mixed={}, unsafe={}, nullSprite={}), "
                        + "animatedTris={}, nullSpriteTris={}, disabledSections={}",
                RtDeviceBringup.ommEnabled(), level, OMM_STATS_SECTIONS.get(), OMM_STATS_CUTOUT_SECTIONS.get(),
                OMM_STATS_MICROMAP_SECTIONS.get(), OMM_STATS_CUTOUT_TRIS.get(), opaqueMicros, totalMicros,
                String.format(java.util.Locale.ROOT, "%.1f", opaquePct), transparentMicros, totalMicros,
                String.format(java.util.Locale.ROOT, "%.1f", transparentPct), unknownMicros, totalMicros,
                String.format(java.util.Locale.ROOT, "%.1f", unknownPct), mixedMicros, unsafeMicros, nullSpriteMicros,
                OMM_STATS_ANIMATED_TRIS.get(), OMM_STATS_NULL_SPRITE_TRIS.get(), OMM_STATS_DISABLED_SECTIONS.get());
    }

    private static int opacityMicromapSubdivisionLevel() {
        int max = Math.max(0, RtDeviceBringup.maxOpacity4StateSubdivisionLevel());
        return Math.min(ommSubdivision(), max);
    }

    private record OmmMicroCounts(int opaque, int transparent, int mixed, int unsafe) {}

    /** Cache key: a triangle's classification depends only on its sprite, UV triple, and subdivision level. */
    private record OmmTriangleKey(TextureAtlasSprite sprite, int level,
                                  float u0, float v0, float u1, float v1, float u2, float v2) {}

    /** Cached classification of one triangle: its {@code bytesPerTriangle}-sized micromap block + tallies. */
    private record OmmTriangleResult(byte[] data, OmmMicroCounts counts) {}

    private static OmmTriangleResult classifyTriangleCached(int level, int bytesPerTriangle,
                                                            TextureAtlasSprite sprite,
                                                            float[] cornerUv, int tri) {
        int o = tri * 6; // 6 floats/triangle: (u0,v0, u1,v1, u2,v2) in primitive order
        OmmTriangleKey key = new OmmTriangleKey(sprite, level,
                cornerUv[o], cornerUv[o + 1], cornerUv[o + 2], cornerUv[o + 3], cornerUv[o + 4], cornerUv[o + 5]);
        return CACHE.computeIfAbsent(key, k -> classifyTriangle(level, bytesPerTriangle, k.sprite(),
                k.u0(), k.v0(), k.u1(), k.v1(), k.u2(), k.v2()));
    }

    private static OmmTriangleResult classifyTriangle(int level, int bytesPerTriangle, TextureAtlasSprite sprite,
                                                      float u0, float v0, float u1, float v1, float u2, float v2) {
        byte[] data = new byte[bytesPerTriangle];
        java.util.Arrays.fill(data, OMM_UNKNOWN_OPAQUE_BYTE);
        int grid = 1 << level;
        float invGrid = 1.0f / grid;
        // counts[0..3] = opaque, transparent, mixed, unsafe. Triangle index 0 -> block-local offset 0.
        int[] counts = new int[4];
        for (int i = 0; i < grid; i++) {
            for (int j = 0; i + j < grid; j++) {
                // Upright micro-triangle: corners (i,j) (i+1,j) (i,j+1).
                classifyMicroTriangle(data, 0, bytesPerTriangle, level, sprite, u0, v0, u1, v1, u2, v2,
                        i, j, i + 1, j, i, j + 1, invGrid,
                        (i + 1.0f / 3.0f) * invGrid, (j + 1.0f / 3.0f) * invGrid, counts);
                // Inverted micro-triangle: corners (i+1,j) (i+1,j+1) (i,j+1).
                if (i + j < grid - 1) {
                    classifyMicroTriangle(data, 0, bytesPerTriangle, level, sprite, u0, v0, u1, v1, u2, v2,
                            i + 1, j, i + 1, j + 1, i, j + 1, invGrid,
                            (i + 2.0f / 3.0f) * invGrid, (j + 2.0f / 3.0f) * invGrid, counts);
                }
            }
        }
        return new OmmTriangleResult(data, new OmmMicroCounts(counts[0], counts[1], counts[2], counts[3]));
    }

    /**
     * Classify one micro-triangle (corners given in integer grid coordinates), write its OMM state
     * when it is uniformly opaque/transparent, and tally the outcome into {@code counts}
     * (opaque, transparent, mixed, unsafe).
     */
    private static void classifyMicroTriangle(byte[] data, int tri, int bytesPerTriangle, int level,
                                              TextureAtlasSprite sprite, float u0, float v0, float u1, float v1,
                                              float u2, float v2, int gi0, int gj0, int gi1, int gj1, int gi2, int gj2,
                                              float invGrid, float centroidB, float centroidC, int[] counts) {
        int state = microTriangleOmmState(sprite, u0, v0, u1, v1, u2, v2,
                gi0 * invGrid, gj0 * invGrid, gi1 * invGrid, gj1 * invGrid, gi2 * invGrid, gj2 * invGrid);
        switch (state) {
            case OMM_FULLY_OPAQUE -> {
                writeClassifiedOmmValue(data, tri, bytesPerTriangle, level, centroidB, centroidC, state);
                counts[0]++;
            }
            case OMM_FULLY_TRANSPARENT -> {
                writeClassifiedOmmValue(data, tri, bytesPerTriangle, level, centroidB, centroidC, state);
                counts[1]++;
            }
            case OMM_CLASS_MIXED -> counts[2]++;
            default -> counts[3]++;
        }
    }

    private static void writeClassifiedOmmValue(byte[] data, int tri, int bytesPerTriangle, int level,
                                                float b, float c, int state) {
        int microIndex = barycentricsToOmmIndex(b, c, level);
        writeOmmValue(data, tri, bytesPerTriangle, microIndex, state);
    }

    private static int microTriangleOmmState(TextureAtlasSprite sprite, float u0, float v0, float u1, float v1,
                                             float u2, float v2, float b0, float c0, float b1, float c1,
                                             float b2, float c2) {
        float tu0 = triangleUv(u0, u1, u2, b0, c0);
        float tv0 = triangleUv(v0, v1, v2, b0, c0);
        float tu1 = triangleUv(u0, u1, u2, b1, c1);
        float tv1 = triangleUv(v0, v1, v2, b1, c1);
        float tu2 = triangleUv(u0, u1, u2, b2, c2);
        float tv2 = triangleUv(v0, v1, v2, b2, c2);
        float minU = Math.min(tu0, Math.min(tu1, tu2));
        float maxU = Math.max(tu0, Math.max(tu1, tu2));
        float minV = Math.min(tv0, Math.min(tv1, tv2));
        float maxV = Math.max(tv0, Math.max(tv1, tv2));
        return spriteRegionOmmState(sprite, minU, minV, maxU, maxV);
    }

    private static float triangleUv(float v0, float v1, float v2, float b, float c) {
        return v0 + (v1 - v0) * b + (v2 - v0) * c;
    }

    private static int spriteRegionOmmState(TextureAtlasSprite sprite, float minU, float minV, float maxU, float maxV) {
        if (!Float.isFinite(minU) || !Float.isFinite(minV) || !Float.isFinite(maxU) || !Float.isFinite(maxV)) {
            return OMM_CLASS_UNSAFE;
        }
        float spanU = sprite.getU1() - sprite.getU0();
        float spanV = sprite.getV1() - sprite.getV0();
        if (Math.abs(spanU) < 1.0e-12f || Math.abs(spanV) < 1.0e-12f) {
            return OMM_CLASS_UNSAFE;
        }
        float localMinU = (minU - sprite.getU0()) / spanU;
        float localMaxU = (maxU - sprite.getU0()) / spanU;
        float localMinV = (minV - sprite.getV0()) / spanV;
        float localMaxV = (maxV - sprite.getV0()) / spanV;
        if (localMinU > localMaxU) {
            float tmp = localMinU;
            localMinU = localMaxU;
            localMaxU = tmp;
        }
        if (localMinV > localMaxV) {
            float tmp = localMinV;
            localMinV = localMaxV;
            localMaxV = tmp;
        }
        var contents = sprite.contents();
        // Tolerance is for the out-of-range UNSAFE check ONLY. Do NOT add it to the sampled
        // region: a half-texel pad makes every micro-triangle's footprint floor()/ceil() into
        // its neighbour texels on all four sides, so any cutout texture classifies MIXED.
        float tolU = 0.5f / Math.max(1, contents.width());
        float tolV = 0.5f / Math.max(1, contents.height());
        if (localMinU < -tolU || localMaxU > 1.0f + tolU || localMinV < -tolV || localMaxV > 1.0f + tolV) {
            return OMM_CLASS_UNSAFE;
        }
        localMinU = clamp01(localMinU);
        localMaxU = clamp01(localMaxU);
        localMinV = clamp01(localMinV);
        localMaxV = clamp01(localMaxV);
        return spriteRegionCutoutState(contents, localMinU, localMinV, localMaxU, localMaxV);
    }

    private static int spriteRegionCutoutState(SpriteContents contents,
                                               float minU, float minV, float maxU, float maxV) {
        int width = contents.width();
        int height = contents.height();
        // Inset by a sub-texel epsilon so a footprint whose edges sit exactly on texel
        // boundaries (the common case once the micro-grid aligns to the texture grid)
        // resolves to the texels it truly covers, instead of bleeding into the neighbour
        // texel through floor()/ceil() floating-point noise at the boundary.
        final float eps = 1.0e-3f;
        int x0 = Math.max(0, Math.min(width, (int) Math.floor(minU * width + eps)));
        int y0 = Math.max(0, Math.min(height, (int) Math.floor(minV * height + eps)));
        int x1 = Math.max(0, Math.min(width, (int) Math.ceil(maxU * width - eps)));
        int y1 = Math.max(0, Math.min(height, (int) Math.ceil(maxV * height - eps)));
        // A footprint narrower than one texel still has to sample the texel containing it.
        if (x1 <= x0) {
            x1 = Math.min(width, x0 + 1);
        }
        if (y1 <= y0) {
            y1 = Math.min(height, y0 + 1);
        }
        if (x0 >= x1 || y0 >= y1) {
            return OMM_CLASS_UNSAFE;
        }
        var image = ((SpriteContentsAccessor) contents).caustica$originalImage();
        int frameRowSize = Math.max(1, image.getWidth() / Math.max(1, width));
        var frames = contents.getUniqueFrames();
        boolean anyPass = false;
        boolean anyFail = false;
        int frameCount = contents.isAnimated() ? frames.size() : 1;
        for (int f = 0; f < frameCount; f++) {
            int frame = contents.isAnimated() ? frames.getInt(f) : 0;
            int frameX = (frame % frameRowSize) * width;
            int frameY = (frame / frameRowSize) * height;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int alpha = ARGB.alpha(image.getPixel(frameX + x, frameY + y));
                    if (alpha >= OMM_ALPHA_CUTOFF) {
                        anyPass = true;
                    } else {
                        anyFail = true;
                    }
                    if (anyPass && anyFail) {
                        return OMM_CLASS_MIXED;
                    }
                }
            }
        }
        if (anyPass) {
            return OMM_FULLY_OPAQUE;
        }
        return anyFail ? OMM_FULLY_TRANSPARENT : OMM_CLASS_UNSAFE;
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    private static void writeOmmValue(byte[] data, int triangle, int bytesPerTriangle, int microIndex, int value) {
        int bit = (triangle * bytesPerTriangle << 3) + microIndex * 2;
        int byteIndex = bit >>> 3;
        int shift = bit & 7;
        int mask = 0x3 << shift;
        int cur = data[byteIndex] & 0xFF;
        data[byteIndex] = (byte) ((cur & ~mask) | ((value & 0x3) << shift));
    }

    private static int barycentricsToOmmIndex(float u, float v, int level) {
        u = clamp01(u);
        v = clamp01(v);
        int dim = 1 << level;
        float fu = u * dim;
        float fv = v * dim;
        int iu = (int) fu;
        int iv = (int) fv;
        float uf = fu - iu;
        float vf = fv - iv;
        if (iu >= dim) {
            iu = dim - 1;
        }
        if (iv >= dim) {
            iv = dim - 1;
        }
        int iuv = iu + iv;
        if (iuv >= dim) {
            iu -= iuv - dim + 1;
        }
        int iw = ~(iu + iv);
        if (uf + vf >= 1.0f && iuv < dim - 1) {
            --iw;
        }
        int b0 = ~(iu ^ iw);
        b0 &= dim - 1;
        int t = (iu ^ iv) & b0;
        int f = t;
        f ^= f >>> 1;
        f ^= f >>> 2;
        f ^= f >>> 4;
        f ^= f >>> 8;
        int b1 = ((f ^ iu) & ~b0) | t;
        b0 = (b0 | (b0 << 8)) & 0x00ff00ff;
        b0 = (b0 | (b0 << 4)) & 0x0f0f0f0f;
        b0 = (b0 | (b0 << 2)) & 0x33333333;
        b0 = (b0 | (b0 << 1)) & 0x55555555;
        b1 = (b1 | (b1 << 8)) & 0x00ff00ff;
        b1 = (b1 | (b1 << 4)) & 0x0f0f0f0f;
        b1 = (b1 | (b1 << 2)) & 0x33333333;
        b1 = (b1 | (b1 << 1)) & 0x55555555;
        return b0 | (b1 << 1);
    }
}
