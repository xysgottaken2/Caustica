package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.RtFrameStats;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.biome.Biome;

/**
 * Persistent, render-thread-only cache of per-section palette snapshots for RT tessellation, replacing
 * vanilla's per-pass {@link net.minecraft.client.renderer.chunk.RenderRegionCache}.
 *
 * <p>The vanilla cache dedupes {@code SectionCopy}s only within one dispatch pass, so during a streaming
 * fill a section's palette was re-copied ({@code SimpleBitStorage.copy}, the top CPU hotspot while
 * flying) by up to 27 neighbouring dispatches across passes — and every {@code SectionCopy} also cloned
 * the owning chunk's <em>entire</em> block-entity map, which the RT mesher never reads (block entities
 * render through {@code RtEntities}). Entries here are palette-only and live until the section is
 * edited (dirty hook), its column unloads or leaves the window, or LRU eviction — so a section is
 * copied once per load/edit instead of once per neighbouring dispatch.
 *
 * <p>A cached {@link PalettedContainer} copy is immutable after creation; concurrent reads from worker
 * threads (including one copy shared by several in-flight regions) are safe, matching how vanilla's
 * {@code SectionCopy} containers were already shared within a pass. Cache mutation stays on the render
 * thread.
 */
final class RtSectionSnapshots {
    // Bounds worst-case cache memory to ~16 MB (palette copies run ~2-4 KB). Dispatch is column-coherent
    // nearest-first, so the live working set (in-flight neighbourhoods) is far smaller than this.
    private static final int MAX_ENTRIES = 4096;
    // Radius of the heightmap apron around a chunk. It matches the largest live wetness-transition
    // setting, so a sheltered surface can blend toward a rain-exposed column across a chunk border.
    private static final int RAIN_TRANSITION_RADIUS = 12;
    /**
     * Shelter distance for geometry that has no snapshot (Distant Horizons LODs, non-Region meshers).
     * One block past the largest selectable transition radius, so the shader's smoothstep resolves it
     * to exactly zero and such terrain never picks up a wet film it cannot justify.
     */
    static final float RAIN_SHELTER_FALLBACK = RAIN_TRANSITION_RADIUS + 1.0f;
    private static final int RAIN_MAP_SIZE = 16 + 2 * RAIN_TRANSITION_RADIUS;
    private static final int MAX_RAIN_HEIGHTMAPS = 256;
    /** Cache/region marker for a section with no copyable states (all air, unloaded, or out of range). */
    private static final Object AIR = new Object();

    private final Long2ObjectLinkedOpenHashMap<Object> cache = new Long2ObjectLinkedOpenHashMap<>();
    // One immutable heightmap snapshot per center chunk, shared by every vertical section build. This
    // avoids repeating the neighbour-height queries for each of a chunk's up to 24 sections.
    private final Long2ObjectLinkedOpenHashMap<int[]> rainHeightmapCache =
            new Long2ObjectLinkedOpenHashMap<>();

    /** Snapshot the 3×3×3 neighbourhood of a section, reusing cached palette copies (render thread). */
    Region createRegion(ClientLevel level, int scx, int scy, int scz) {
        Object[] sections = new Object[27];
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    sections[(x + 1) + (y + 1) * 3 + (z + 1) * 9] = section(level, scx + x, scy + y, scz + z);
                }
            }
        }
        return new Region(level, scx - 1, scy - 1, scz - 1, scx, scz, sections,
                rainHeightmap(level, scx, scz));
    }

    /** Drop a stale entry (edited section, or its column unloaded / left the window). Render thread. */
    void invalidate(long sectionKey) {
        cache.remove(sectionKey);
        int centerX = (int) (sectionKey << 38 >> 38);
        int centerZ = (int) (sectionKey << 12 >> 38);
        // Heightmap snapshots include a 12-block apron, so a changed column can affect shelter
        // distances in its own chunk and the eight neighbouring center chunks.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                rainHeightmapCache.remove(RtTerrain.sectionKey(centerX + dx, 0, centerZ + dz));
            }
        }
    }

    void clear() {
        cache.clear();
        rainHeightmapCache.clear();
    }

    private int[] rainHeightmap(ClientLevel level, int centerSectionX, int centerSectionZ) {
        long key = RtTerrain.sectionKey(centerSectionX, 0, centerSectionZ);
        int[] cached = rainHeightmapCache.getAndMoveToLast(key);
        if (cached != null) {
            return cached;
        }
        int[] snapshot = new int[RAIN_MAP_SIZE * RAIN_MAP_SIZE];
        for (int z = -RAIN_TRANSITION_RADIUS; z < 16 + RAIN_TRANSITION_RADIUS; z++) {
            for (int x = -RAIN_TRANSITION_RADIUS; x < 16 + RAIN_TRANSITION_RADIUS; x++) {
                snapshot[(z + RAIN_TRANSITION_RADIUS) * RAIN_MAP_SIZE
                        + x + RAIN_TRANSITION_RADIUS] = level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                        (centerSectionX << 4) + x, (centerSectionZ << 4) + z);
            }
        }
        rainHeightmapCache.putAndMoveToLast(key, snapshot);
        while (rainHeightmapCache.size() > MAX_RAIN_HEIGHTMAPS) {
            rainHeightmapCache.removeFirst();
        }
        return snapshot;
    }

    private Object section(ClientLevel level, int scx, int scy, int scz) {
        long key = RtTerrain.sectionKey(scx, scy, scz);
        Object cached = cache.getAndMoveToLast(key);
        if (cached != null) {
            return cached;
        }
        Object copy = copySection(level, scx, scy, scz);
        cache.putAndMoveToLast(key, copy);
        if (cache.size() > MAX_ENTRIES) {
            cache.removeFirst();
        }
        return copy;
    }

    private static Object copySection(ClientLevel level, int scx, int scy, int scz) {
        LevelChunk chunk = level.getChunk(scx, scz);
        if (chunk instanceof EmptyLevelChunk) {
            return AIR;
        }
        LevelChunkSection[] sections = chunk.getSections();
        int index = chunk.getSectionIndexFromSectionY(scy);
        if (index < 0 || index >= sections.length) {
            return AIR;
        }
        LevelChunkSection section = sections[index];
        if (section.hasOnlyAir()) {
            return AIR;
        }
        RtFrameStats.FRAME.count("sectionCopies", 1);
        return section.getStates().copy();
    }

    /**
     * The immutable 3×3×3 view a tessellation job reads. Mirrors vanilla's
     * {@code RenderSectionRegion} except that {@link #getBlockEntity} always returns null: the RT
     * mesher never queries block entities (they render through {@code RtEntities}), which is what lets
     * the snapshot skip {@code SectionCopy}'s per-copy clone of the whole chunk's block-entity map.
     */
    static final class Region implements BlockAndTintGetter {
        private final ClientLevel level;
        private final int minSectionX;
        private final int minSectionY;
        private final int minSectionZ;
        private final Object[] sections; // PalettedContainer<BlockState> or AIR, x-then-y-then-z minor
        private final CardinalLighting cardinalLighting;
        private final LevelLightEngine lightEngine;
        private final boolean debug;
        private final int centerMinBlockX;
        private final int centerMinBlockY;
        private final int centerMinBlockZ;
        // Heightmap top per column, including a 12-block apron so sheltered surfaces can blend from the
        // nearest rain-exposed column even when that column sits across a chunk boundary.
        private static final float RAIN_DISTANCE_INFINITY = 1.0e4f;
        private static final float RAIN_DISTANCE_DIAGONAL = 1.41421356f;
        private static final float RAIN_DISTANCE_CUTOFF = RAIN_TRANSITION_RADIUS + 1.0f;
        private final int[] motionBlockingTopY;
        // One baked shelter-distance value per block in the target section. The shader applies the live
        // transition-distance slider to these values, so dragging the setting needs no mesh rebuild.
        private final float[] rainShelterDistances = new float[16 * 16 * 16];

        Region(ClientLevel level, int minSectionX, int minSectionY, int minSectionZ,
               int centerSectionX, int centerSectionZ, Object[] sections, int[] motionBlockingTopY) {
            this.level = level;
            this.minSectionX = minSectionX;
            this.minSectionY = minSectionY;
            this.minSectionZ = minSectionZ;
            this.sections = sections;
            this.motionBlockingTopY = motionBlockingTopY;
            this.cardinalLighting = level.cardinalLighting();
            this.lightEngine = level.getLightEngine();
            this.debug = level.isDebug();
            this.centerMinBlockX = centerSectionX << 4;
            this.centerMinBlockY = (minSectionY + 1) << 4;
            this.centerMinBlockZ = centerSectionZ << 4;
            bakeRainShelterDistances();
        }

        /** True for the topmost motion-blocking block in this column, which vanilla rain reaches. */
        boolean isRainExposed(BlockPos pos) {
            if (debug) {
                return false;
            }
            int x = pos.getX() - centerMinBlockX;
            int z = pos.getZ() - centerMinBlockZ;
            if (x < 0 || x >= 16 || z < 0 || z >= 16) {
                return false;
            }
            int mapIndex = (z + RAIN_TRANSITION_RADIUS) * RAIN_MAP_SIZE + x + RAIN_TRANSITION_RADIUS;
            return pos.getY() >= motionBlockingTopY[mapIndex] - 1;
        }

        /**
         * Horizontal distance to the nearest column where rain reaches this surface height. This is
         * independent of the slider: the shader turns distance into a smooth wet-to-dry transition
         * using the live radius configured by the user. A value beyond the largest radius means dry.
         */
        float rainShelterDistance(BlockPos pos) {
            if (debug) {
                return RAIN_DISTANCE_CUTOFF;
            }
            int x = pos.getX() - centerMinBlockX;
            int y = pos.getY() - centerMinBlockY;
            int z = pos.getZ() - centerMinBlockZ;
            if (x < 0 || x >= 16 || y < 0 || y >= 16 || z < 0 || z >= 16) {
                return RAIN_DISTANCE_CUTOFF;
            }
            return rainShelterDistances[y * 16 * 16 + z * 16 + x];
        }

        /** Builds a chamfer-distance field for each Y slice of the section on the render thread. */
        private void bakeRainShelterDistances() {
            if (debug) {
                java.util.Arrays.fill(rainShelterDistances, RAIN_DISTANCE_CUTOFF);
                return;
            }
            float[] distance = new float[RAIN_MAP_SIZE * RAIN_MAP_SIZE];
            for (int localY = 0; localY < 16; localY++) {
                int surfaceY = centerMinBlockY + localY;
                for (int i = 0; i < distance.length; i++) {
                    // Heightmap values point just above the highest motion-blocking block. This is
                    // equivalent to the direct-exposure test blockY >= height - 1.
                    distance[i] = surfaceY >= motionBlockingTopY[i] - 1
                            ? 0.0f : RAIN_DISTANCE_INFINITY;
                }

                // Two-pass 8-neighbour chamfer transform approximates Euclidean distance without a
                // per-block scan. The 12-block border captures neighbouring chunks for all slider values.
                for (int z = 0; z < RAIN_MAP_SIZE; z++) {
                    for (int x = 0; x < RAIN_MAP_SIZE; x++) {
                        int i = z * RAIN_MAP_SIZE + x;
                        float d = distance[i];
                        if (x > 0) {
                            d = Math.min(d, distance[i - 1] + 1.0f);
                        }
                        if (z > 0) {
                            d = Math.min(d, distance[i - RAIN_MAP_SIZE] + 1.0f);
                            if (x > 0) {
                                d = Math.min(d, distance[i - RAIN_MAP_SIZE - 1] + RAIN_DISTANCE_DIAGONAL);
                            }
                            if (x + 1 < RAIN_MAP_SIZE) {
                                d = Math.min(d, distance[i - RAIN_MAP_SIZE + 1] + RAIN_DISTANCE_DIAGONAL);
                            }
                        }
                        distance[i] = d;
                    }
                }
                for (int z = RAIN_MAP_SIZE - 1; z >= 0; z--) {
                    for (int x = RAIN_MAP_SIZE - 1; x >= 0; x--) {
                        int i = z * RAIN_MAP_SIZE + x;
                        float d = distance[i];
                        if (x + 1 < RAIN_MAP_SIZE) {
                            d = Math.min(d, distance[i + 1] + 1.0f);
                        }
                        if (z + 1 < RAIN_MAP_SIZE) {
                            d = Math.min(d, distance[i + RAIN_MAP_SIZE] + 1.0f);
                            if (x + 1 < RAIN_MAP_SIZE) {
                                d = Math.min(d, distance[i + RAIN_MAP_SIZE + 1] + RAIN_DISTANCE_DIAGONAL);
                            }
                            if (x > 0) {
                                d = Math.min(d, distance[i + RAIN_MAP_SIZE - 1] + RAIN_DISTANCE_DIAGONAL);
                            }
                        }
                        distance[i] = d;
                    }
                }

                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        float d = distance[(z + RAIN_TRANSITION_RADIUS) * RAIN_MAP_SIZE
                                + x + RAIN_TRANSITION_RADIUS];
                        rainShelterDistances[localY * 16 * 16 + z * 16 + x] =
                                d <= RAIN_TRANSITION_RADIUS ? d : RAIN_DISTANCE_CUTOFF;
                    }
                }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public BlockState getBlockState(BlockPos pos) {
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            if (debug) {
                BlockState state = null;
                if (y == 60) {
                    state = Blocks.BARRIER.defaultBlockState();
                }
                if (y == 70) {
                    state = DebugLevelSource.getBlockStateFor(x, z);
                }
                return state == null ? Blocks.AIR.defaultBlockState() : state;
            }
            int index = (SectionPos.blockToSectionCoord(x) - minSectionX)
                    + (SectionPos.blockToSectionCoord(y) - minSectionY) * 3
                    + (SectionPos.blockToSectionCoord(z) - minSectionZ) * 9;
            Object section = sections[index];
            if (section == AIR) {
                return Blocks.AIR.defaultBlockState();
            }
            return ((PalettedContainer<BlockState>) section).get(x & 15, y & 15, z & 15);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return cardinalLighting;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return lightEngine;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null; // never queried by the RT mesher — see class javadoc
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return level.getBlockTint(pos, resolver);
        }

        @Override
        public boolean hasBiomes() {
            return level.hasBiomes();
        }

        @Override
        public Holder<Biome> getBiomeFabric(BlockPos pos) {
            return level.getBiomeFabric(pos);
        }

        @Override
        public int getMinY() {
            return level.getMinY();
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }
    }
}
