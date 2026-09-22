package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.compat.DistantHorizonsCompat;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.material.RtMaterials;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionBuilder.PreparedSection;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.PackedSection;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Experimental ray-traced coarse geometry sourced from DH's native render buffers. */
public final class RtDistantHorizonsTerrain {
    public static final RtDistantHorizonsTerrain INSTANCE = new RtDistantHorizonsTerrain();
    /** ENTITY_BIT | PARTICLE_BIT is otherwise unused and still fits Vulkan's 24-bit custom index. */
    public static final int DH_INSTANCE_KIND = 0xC00000;
    /** Camera, shadow, GI and reflection rays all see native DH LOD triangles. */
    private static final int RAY_MASK = 0x03;
    /** Rebuilding at every chunk crossing is too expensive for multi-million-triangle DH meshes. */
    private static final int ANCHOR_BLOCKS = 256;
    /** Keep each DH section wholly outside vanilla terrain to avoid overlap at the transition. */
    private static final int VANILLA_SEAM_GUARD_BLOCKS = 64;
    private static final int CHUNK_BLOCKS = 16;
    private static final int MAX_PROXY_DISTANCE_CHUNKS = 512;
    private static final long REFRESH_NANOS = 8_000_000_000L;
    private static final long QUALITY_REFRESH_NANOS = 1_000_000_000L;
    private static final long QUALITY_SETTLE_NANOS = 15_000_000_000L;
    private static final long QUALITY_POLL_NANOS = 500_000_000L;
    private static final long RETRY_NANOS = 5_000_000_000L;
    /** Bound transient upload/BLAS scratch residency during a DH rebuild. */
    private static final int MAX_BUILD_QUADS = 131_072;
    /** Publish a useful near-field proxy quickly, then checkpoint larger groups while the rest builds. */
    private static final int INITIAL_PROGRESS_BATCHES = 1;
    private static final int STEADY_PROGRESS_BATCHES = 32;
    private static final long PROGRESS_PUBLISH_NANOS = 1_000_000_000L;
    // EDhApiBlockMaterial shader indices. Keep these explicit rather than depending on the optional
    // DH API at compile time: the native VBO stores the material as this single byte. Preserve the full
    // mini-material instead of collapsing every opaque LOD face to the generic terrain material.
    private static final int DH_MATERIAL_UNKNOWN = 0;
    private static final int DH_MATERIAL_LEAVES = 1;
    private static final int DH_MATERIAL_STONE = 2;
    private static final int DH_MATERIAL_WOOD = 3;
    private static final int DH_MATERIAL_METAL = 4;
    private static final int DH_MATERIAL_DIRT = 5;
    private static final int DH_MATERIAL_LAVA = 6;
    private static final int DH_MATERIAL_DEEPSLATE = 7;
    private static final int DH_MATERIAL_SNOW = 8;
    private static final int DH_MATERIAL_SAND = 9;
    private static final int DH_MATERIAL_TERRACOTTA = 10;
    private static final int DH_MATERIAL_NETHER_STONE = 11;
    private static final int DH_MATERIAL_WATER = 12;
    private static final int DH_MATERIAL_GRASS = 13;
    private static final int DH_MATERIAL_AIR = 14;
    private static final int DH_MATERIAL_ILLUMINATED = 15;
    private static final float DH_LAVA_EMISSION = 1.30f;
    private static final float DH_ILLUMINATED_EMISSION = 1.05f;
    private static final float[] SRGB_TO_LINEAR = makeSrgbLut();
    private static final float[] EMPTY_LIGHTS = new float[0];

    private final ConcurrentLinkedQueue<Completed> completed = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CpuCompleted> cpuCompleted = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PackCompleted> packCompleted = new ConcurrentLinkedQueue<>();
    private final Object activeLock = new Object();
    private final ExecutorService cpuWorker = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "Caustica DH CPU worker");
        thread.setDaemon(true);
        // MIN_PRIORITY can be starved for minutes by DH generation and chunk workers. Keep this below
        // normal game work, but high enough to stream visible RT batches continuously.
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    // Reused frame-local composite view. Each DH BLAS keeps its own stable world-space origin, so
    // unchanged LOD sections can survive camera-anchor changes and be reused by later proxy revisions.
    private final AppendedInstanceList frameInstances = new AppendedInstanceList();
    private Proxy instanceProxy;
    private RtAccel.Instance[] frameDhInstances = new RtAccel.Instance[0];

    private Proxy current;
    private Object world;
    private int anchorX = Integer.MIN_VALUE;
    private int anchorZ = Integer.MIN_VALUE;
    private long nextRefresh;
    private long earliestBuildNanos;
    private volatile long epoch = 1L;
    private long nextRevision = 1L;
    private long capturedLodRevision = -1L;
    private long nextQualityPollNanos;
    private long qualitySettleUntilNanos;
    private DistantHorizonsCompat.LodQuality lodQuality =
            new DistantHorizonsCompat.LodQuality(0L, 16, 2, "UNKNOWN", "UNKNOWN");
    private int activeTasks;
    private boolean pending;
    private boolean cpuPending;
    private boolean packPending;
    private boolean bootstrapComplete;
    private final AtomicBoolean manualRefreshRequested = new AtomicBoolean();
    private boolean forceSourceRebuild;
    // Refreshes are incremental and atomic: unchanged DH source meshes reuse their existing compacted
    // BLAS, changed/new meshes are built one bounded batch at a time, and the old proxy remains the sole
    // visible RT source until the replacement table is complete. This removes the raster-only hand-off
    // (which lost RT shadows/materials) without recreating the old full-proxy VRAM doubling spike.
    private BuildSession buildSession;

    private RtDistantHorizonsTerrain() {
    }

    /**
     * Rebuild the RT proxy from DH's current source meshes on the next render frame. The published proxy
     * remains visible until its replacement is ready, and no world-save or persistent DH cache is deleted.
     */
    public void requestFullRefresh() {
        manualRefreshRequested.set(true);
    }

    public void frame(RtContext ctx, int rebaseX, int rebaseY, int rebaseZ) {
        Minecraft minecraft = Minecraft.getInstance();
        DistantHorizonsCompat.tickOptionalSources();
        Object newWorld = minecraft.level;
        if (DistantHorizonsCompat.enabled() && newWorld != null
                && manualRefreshRequested.getAndSet(false)) {
            // Invalidate unpublished work before draining completions, so an old result cannot publish or
            // schedule another batch between the button click and the epoch change.
            epoch++;
            abortBuildSession();
            cpuPending = false;
            anchorX = anchorZ = Integer.MIN_VALUE;
            capturedLodRevision = -1L;
            nextRefresh = 0L;
            earliestBuildNanos = 0L;
            forceSourceRebuild = true;
            CausticaMod.LOGGER.info("Manual Distant Horizons RT proxy refresh requested");
        }
        drainCpuCompleted(ctx);
        drainPackCompleted(ctx);
        drainCompleted(ctx);
        serviceBuildSession(ctx);

        if (!DistantHorizonsCompat.enabled() || newWorld == null) {
            if (current != null || world != null) reset(ctx, true);
            return;
        }
        if (world != newWorld) {
            // The DH upload hook may already have captured VBOs for this new ClientLevel. Reset only GPU
            // proxy/build state here; DistantHorizonsCompat scopes and clears old CPU captures by level
            // identity, so deleting the map again would erase fresh data that DH may never re-upload.
            reset(ctx, false);
            world = newWorld;
            // DH starts populating its repository after LevelRenderer. Avoid querying during world load.
            earliestBuildNanos = System.nanoTime() + 1_000_000_000L;
        }
        var camera = minecraft.gameRenderer.mainCamera().position();
        int ax = Math.floorDiv((int) Math.floor(camera.x), ANCHOR_BLOCKS) * ANCHOR_BLOCKS;
        int az = Math.floorDiv((int) Math.floor(camera.z), ANCHOR_BLOCKS) * ANCHOR_BLOCKS;
        long now = System.nanoTime();
        if (now >= nextQualityPollNanos) {
            nextQualityPollNanos = now + QUALITY_POLL_NANOS;
            DistantHorizonsCompat.LodQuality updatedQuality = DistantHorizonsCompat.lodQuality();
            if (updatedQuality.signature() != lodQuality.signature()) {
                if (lodQuality.signature() != 0L
                        && (cpuPending || packPending || pending || buildSession != null)) {
                    // Do not finish a minutes-long plan made for the previous DH quality. Keep the currently
                    // published proxy, cancel only unpublished work, and re-plan from the new active LOD tree.
                    epoch++;
                    abortBuildSession();
                    cpuPending = false;
                }
                lodQuality = updatedQuality;
                qualitySettleUntilNanos = now + QUALITY_SETTLE_NANOS;
                capturedLodRevision = -1L;
                nextRefresh = 0L;
                CausticaMod.LOGGER.info(
                        "DH RT quality changed: max resolution {}, dropoff {}; prioritizing refined LODs",
                        updatedQuality.maxHorizontalResolution(), updatedQuality.horizontalQuality());
            } else {
                lodQuality = updatedQuality;
            }
        }
        long lodRevision = DistantHorizonsCompat.lodRevision();
        boolean lodDataChanged = lodRevision != capturedLodRevision;
        boolean cameraCellChanged = ax != anchorX || az != anchorZ;
        // Do not wait for a global DH "quiet" period: world generation can upload continuously for minutes.
        // Incremental source-version reuse makes periodic live snapshots cheap and lets refined LODs replace
        // coarse batches while DH is still streaming.
        boolean refreshUploadedLods = current != null && lodDataChanged && now >= nextRefresh;

        // Never withdraw the published RT proxy while a replacement is prepared. DH uploads can arrive in
        // bursts for many seconds; wait for a quiet window, then rebuild only changed source meshes.
        boolean replacementBusy = buildSession != null || packPending;
        if (!pending && !cpuPending && !replacementBusy && now >= earliestBuildNanos
                && RtMaterialRegistry.INSTANCE.isReady()
                && (current == null || cameraCellChanged || refreshUploadedLods)) {
            anchorX = ax;
            anchorZ = az;
            capturedLodRevision = lodRevision;
            nextRefresh = now + refreshDelayNanos(now);
            // Match the shader hand-off: DH starts one chunk closer than the old +CHUNK_BLOCKS seam.
            int vanillaRadius = minecraft.options.renderDistance().get() * CHUNK_BLOCKS
                    + VANILLA_SEAM_GUARD_BLOCKS;
            int dhChunks = Math.min(MAX_PROXY_DISTANCE_CHUNKS,
                    Math.max(1, DistantHorizonsCompat.renderDistanceChunks()));
            int proxyRadius = Math.max(vanillaRadius + CHUNK_BLOCKS, dhChunks * CHUNK_BLOCKS);
            long revision = nextRevision;
            nextRevision += 2L;
            Proxy reuseBase = forceSourceRebuild ? null : current;
            forceSourceRebuild = false;
            startCpuBuild(ax, az, proxyRadius, epoch, revision, reuseBase,
                    RtMaterialRegistry.INSTANCE.requireSnapshot(), lodQuality);
        }
    }

    public List<RtAccel.Instance> appendInstances(List<RtAccel.Instance> terrain,
                                                   int rebaseX, int rebaseY, int rebaseZ) {
        Proxy proxy = current;
        if (proxy == null) return terrain;

        if (instanceProxy != proxy) {
            instanceProxy = proxy;
            frameDhInstances = new RtAccel.Instance[proxy.entries.size()];
            for (int i = 0; i < frameDhInstances.length; i++) {
                RtSectionTable.SectionGeom geom = proxy.entries.get(i).geom;
                float[] transform = {1, 0, 0, geom.sx - rebaseX,
                        0, 1, 0, geom.sy - rebaseY,
                        0, 0, 1, geom.sz - rebaseZ};
                frameDhInstances[i] = new RtAccel.Instance(transform,
                        geom.blas.deviceAddress, DH_INSTANCE_KIND | i, RAY_MASK);
            }
        } else {
            for (int i = 0; i < frameDhInstances.length; i++) {
                RtSectionTable.SectionGeom geom = proxy.entries.get(i).geom;
                float[] transform = frameDhInstances[i].transform3x4();
                transform[3] = geom.sx - rebaseX;
                transform[7] = geom.sy - rebaseY;
                transform[11] = geom.sz - rebaseZ;
            }
        }
        frameInstances.reset(terrain, frameDhInstances);
        return frameInstances;
    }

    public long tableAddress() {
        Proxy proxy = current;
        return proxy == null ? 0L : proxy.table.deviceAddress;
    }

    public long emissiveRevision() {
        Proxy proxy = current;
        return proxy == null ? 0L : proxy.revision;
    }

    /** Raster DH fills only the still-unbuilt horizon during the first progressive proxy stream. */
    public boolean bootstrapComplete() {
        return bootstrapComplete;
    }

    private void startCpuBuild(int originX, int originZ, int proxyRadius, long taskEpoch,
                               long revision, Proxy base, RtMaterialRegistry.Snapshot materials,
                               DistantHorizonsCompat.LodQuality quality) {
        cpuPending = true;
        // Repository access and the lightweight validity/count scan can still touch gigabytes of DH VBO
        // data. Run it on a low-priority daemon rather than a virtual-thread carrier so it cannot starve
        // the render/server workers during a large LOD upload burst.
        cpuWorker.execute(() -> {
            try {
                List<PlannedBatch> plan = planCapturedLods(originX, originZ, proxyRadius, taskEpoch, base, quality);
                cpuCompleted.add(new CpuCompleted(taskEpoch, revision + 1L, plan, materials, null, true));
            } catch (Throwable failure) {
                cpuCompleted.add(new CpuCompleted(taskEpoch, revision + 1L, null, materials, failure, true));
            }
        });
    }

    /**
     * Build an atomic refresh plan. Unchanged DH source VBOs retain their compacted BLAS; only changed/new
     * source meshes are decoded and uploaded. Batches are kept inside one source mesh so a small DH update
     * never forces a full render-distance-sized replacement.
     */
    private List<PlannedBatch> planCapturedLods(int originX, int originZ, int proxyRadius,
                                                long taskEpoch, Proxy base,
                                                DistantHorizonsCompat.LodQuality quality) {
        if (taskEpoch != epoch) return List.of();
        ArrayList<DistantHorizonsCompat.LodMesh> snapshot =
                new ArrayList<>(DistantHorizonsCompat.lodMeshesSnapshot());
        if (snapshot.isEmpty()) return List.of();
        snapshot = removeFullyCoveredCoarseMeshes(snapshot);
        int qualityBandBlocks = switch (Math.clamp(quality.horizontalQualityRank(), 0, 4)) {
            case 0 -> 128;
            case 1 -> 192;
            case 2 -> 256;
            case 3 -> 384;
            default -> 512;
        };
        snapshot.sort((a, b) -> {
            int ad = meshDistance(a, originX, originZ);
            int bd = meshDistance(b, originX, originZ);
            int byBand = Integer.compare(ad / qualityBandBlocks, bd / qualityBandBlocks);
            if (byBand != 0) return byBand;
            // Within the visible quality band, build the finest captured VBOs first. The previous coarse-first
            // ordering made low-detail parents mask refined children until the entire horizon was complete.
            int byDetail = Integer.compare(a.dataPointWidth(), b.dataPointWidth());
            if (byDetail != 0) return byDetail;
            int byDistance = Integer.compare(ad, bd);
            if (byDistance != 0) return byDistance;
            int byCoverage = Integer.compare(a.width(), b.width());
            return byCoverage != 0 ? byCoverage : Long.compareUnsigned(a.key(), b.key());
        });

        ArrayList<PlannedBatch> plan = new ArrayList<>();
        int selectedMeshes = 0;
        int reusedMeshes = 0;
        int reusedBatches = 0;
        int rebuiltBatches = 0;
        for (DistantHorizonsCompat.LodMesh mesh : snapshot) {
            if (taskEpoch != epoch) return List.of();
            int cx = mesh.originX() + mesh.width() / 2;
            int cz = mesh.originZ() + mesh.width() / 2;
            int distance = Math.max(Math.abs(cx - originX), Math.abs(cz - originZ));
            if (distance > proxyRadius + mesh.width()) continue;
            selectedMeshes++;

            SourceState oldSource = base == null ? null : base.sources.get(mesh.key());
            if (oldSource != null && oldSource.version == mesh.version()) {
                reusedMeshes++;
                reusedBatches += oldSource.entries.size();
                for (GeomEntry entry : oldSource.entries) {
                    plan.add(PlannedBatch.reuse(entry));
                }
                continue;
            }

            ArrayList<DhSlice> slices = new ArrayList<>();
            appendDhSlices(mesh, mesh.opaque(), false, slices);
            appendDhSlices(mesh, mesh.transparent(), true, slices);
            rebuiltBatches += appendPlannedBuilds(plan, mesh, slices);
        }
        CausticaMod.LOGGER.info(
                "DH RT refresh plan: {} active source meshes ({} reused), {} reused + {} rebuilt bounded BLAS batches; quality {} / {}, target datapoint {} blocks",
                selectedMeshes, reusedMeshes, reusedBatches, rebuiltBatches,
                quality.maxHorizontalResolution(), quality.horizontalQuality(), quality.maxDataPointWidth());
        return plan;
    }

    /**
     * Combine compatible slices from one source section up to the existing quad budget. Voxy normally
     * publishes opaque and transparent arrays separately even when both are small; building them as two
     * BLASes nearly doubled startup work and TLAS instance count. They already share one source version
     * and {@link #packDhBatch} supports mixed material buckets, so one bounded BLAS is equivalent.
     */
    private static int appendPlannedBuilds(List<PlannedBatch> plan,
                                           DistantHorizonsCompat.LodMesh mesh,
                                           List<DhSlice> slices) {
        if (slices.isEmpty()) return 0;
        ArrayList<DhSlice> group = new ArrayList<>(2);
        int groupQuads = 0;
        int batchIndex = 0;
        for (DhSlice slice : slices) {
            int sliceQuads = slice.counts.total();
            if (!group.isEmpty() && groupQuads + sliceQuads > MAX_BUILD_QUADS) {
                plan.add(PlannedBatch.build(batchKey(mesh.key(), batchIndex++), mesh.key(), mesh.version(),
                        mesh.originX(), mesh.originZ(), mesh.width(), mesh.dataPointWidth(),
                        List.copyOf(group)));
                group.clear();
                groupQuads = 0;
            }
            group.add(slice);
            groupQuads += sliceQuads;
        }
        if (!group.isEmpty()) {
            plan.add(PlannedBatch.build(batchKey(mesh.key(), batchIndex++), mesh.key(), mesh.version(),
                    mesh.originX(), mesh.originZ(), mesh.width(), mesh.dataPointWidth(),
                    List.copyOf(group)));
        }
        return batchIndex;
    }

    private long refreshDelayNanos(long now) {
        return now < qualitySettleUntilNanos ? QUALITY_REFRESH_NANOS : REFRESH_NANOS;
    }

    private static int meshDistance(DistantHorizonsCompat.LodMesh mesh, int originX, int originZ) {
        int cx = mesh.originX() + mesh.width() / 2;
        int cz = mesh.originZ() + mesh.width() / 2;
        return Math.max(Math.abs(cx - originX), Math.abs(cz - originZ));
    }

    /**
     * DH can temporarily report a coarse parent together with its refined children while changing quality.
     * Keep the parent only until the finer active sections cover its complete square; otherwise both meshes
     * enter the RT proxy and the coarse surface masks the better LOD in camera, shadow, and reflection rays.
     */
    private static ArrayList<DistantHorizonsCompat.LodMesh> removeFullyCoveredCoarseMeshes(
            List<DistantHorizonsCompat.LodMesh> meshes) {
        ArrayList<DistantHorizonsCompat.LodMesh> ordered = new ArrayList<>(meshes);
        ordered.sort((a, b) -> {
            int byDetail = Integer.compare(a.dataPointWidth(), b.dataPointWidth());
            if (byDetail != 0) return byDetail;
            int byWidth = Integer.compare(a.width(), b.width());
            return byWidth != 0 ? byWidth : Long.compareUnsigned(a.key(), b.key());
        });
        ArrayList<DistantHorizonsCompat.LodMesh> selected = new ArrayList<>(ordered.size());
        ArrayList<LodRect> selectedRects = new ArrayList<>(ordered.size());
        for (DistantHorizonsCompat.LodMesh mesh : ordered) {
            LodRect rect = new LodRect(mesh.key(), mesh.originX(), mesh.originZ(), mesh.width(),
                    mesh.dataPointWidth());
            if (!fullyCoveredBy(rect, selectedRects)) {
                selected.add(mesh);
                selectedRects.add(rect);
            }
        }
        return selected;
    }

    private static boolean fullyCoveredBy(LodRect target, List<LodRect> candidates) {
        ArrayList<LodRect> relevant = new ArrayList<>();
        for (LodRect candidate : candidates) {
            if (candidate.key == target.key) continue;
            boolean finer = candidate.detailWidth < target.detailWidth
                    || (candidate.detailWidth == target.detailWidth && candidate.width < target.width);
            if (finer && intersects(target, candidate)) relevant.add(candidate);
        }
        return !relevant.isEmpty() && coveredSquare(target.x, target.z, target.width, relevant);
    }

    private static boolean coveredSquare(int x, int z, int width, List<LodRect> candidates) {
        for (LodRect candidate : candidates) {
            if (contains(candidate, x, z, width)) return true;
        }
        if (width <= 1) return false;
        int first = width / 2;
        int second = width - first;
        return coveredSquarePart(x, z, first, first, candidates)
                && coveredSquarePart(x + first, z, second, first, candidates)
                && coveredSquarePart(x, z + first, first, second, candidates)
                && coveredSquarePart(x + first, z + first, second, second, candidates);
    }

    private static boolean coveredSquarePart(int x, int z, int width, int depth,
                                             List<LodRect> candidates) {
        if (width <= 0 || depth <= 0) return true;
        ArrayList<LodRect> relevant = new ArrayList<>();
        for (LodRect candidate : candidates) {
            if (intersects(candidate, x, z, width, depth)) relevant.add(candidate);
        }
        if (relevant.isEmpty()) return false;
        if (width == depth) return coveredSquare(x, z, width, relevant);
        // DH section widths are powers of two, but keep a safe rectangular fallback for malformed metadata.
        if (width > depth) {
            int first = width / 2;
            return coveredSquarePart(x, z, first, depth, relevant)
                    && coveredSquarePart(x + first, z, width - first, depth, relevant);
        }
        int first = depth / 2;
        return coveredSquarePart(x, z, width, first, relevant)
                && coveredSquarePart(x, z + first, width, depth - first, relevant);
    }

    private static boolean contains(LodRect outer, int x, int z, int width) {
        long outerEndX = (long) outer.x + outer.width;
        long outerEndZ = (long) outer.z + outer.width;
        return outer.x <= x && outer.z <= z
                && outerEndX >= (long) x + width && outerEndZ >= (long) z + width;
    }

    private static boolean intersects(LodRect a, LodRect b) {
        return intersects(a, b.x, b.z, b.width, b.width);
    }

    private static boolean intersects(LodRect a, int x, int z, int width, int depth) {
        return (long) a.x < (long) x + width && (long) x < (long) a.x + a.width
                && (long) a.z < (long) z + depth && (long) z < (long) a.z + a.width;
    }

    private static long batchKey(long sourceKey, int batchIndex) {
        long x = sourceKey ^ (0x9E3779B97F4A7C15L * (batchIndex + 1L));
        x ^= x >>> 30;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 27;
        x *= 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    private static void appendDhSlices(DistantHorizonsCompat.LodMesh mesh, byte[] bytes,
                                       boolean transparentPass, List<DhSlice> out) {
        int maxLocal = mesh.width() + 1;
        int recordsPerSlice = MAX_BUILD_QUADS;
        int byteStride = 64;
        for (int firstQuad = 0; firstQuad < bytes.length / byteStride; firstQuad += recordsPerSlice) {
            int start = firstQuad * byteStride;
            int end = (int) Math.min(bytes.length, (long) (firstQuad + recordsPerSlice) * byteStride);
            QuadCounts counts = countDhQuads(bytes, transparentPass, maxLocal, start, end);
            if (counts.total() != 0) out.add(new DhSlice(mesh, bytes, transparentPass, start, end, counts));
        }
    }

    private static PackedSection packDhBatch(List<DhSlice> batch, int originX, int originZ,
                                             RtMaterialRegistry.Snapshot materials) {
        int solidQuads = 0;
        int emissiveQuads = 0;
        int glassQuads = 0;
        int waterQuads = 0;
        for (DhSlice slice : batch) {
            solidQuads = Math.addExact(solidQuads, slice.counts.solid);
            emissiveQuads = Math.addExact(emissiveQuads, slice.counts.emissive);
            glassQuads = Math.addExact(glassQuads, slice.counts.glass);
            waterQuads = Math.addExact(waterQuads, slice.counts.water);
        }
        int opaqueQuads = Math.addExact(solidQuads, emissiveQuads);
        PackedMeshBuilder packed = new PackedMeshBuilder(opaqueQuads, glassQuads, waterQuads);
        DhMaterialPalette palette = new DhMaterialPalette(
                materials.resolve(null, RtMaterials.Profile.DEFAULT, false, false),
                materials.resolve(null, RtMaterials.Profile.METAL, false, false),
                materials.resolve(null, RtMaterials.Profile.SMOOTH, false, false),
                materials.glassId(), materials.waterId(), materials.lavaId(), materials.emissiveId(),
                materials.emissiveGlassId());
        for (DhSlice slice : batch) {
            decodeDhBuffer(slice.bytes, slice.transparentPass, slice.mesh, originX, originZ, packed,
                    palette, slice.start, slice.end);
        }
        packed.requireComplete();

        int solidTris = Math.multiplyExact(solidQuads, 2);
        int emissiveTris = Math.multiplyExact(emissiveQuads, 2);
        int glassTris = Math.multiplyExact(glassQuads, 2);
        int waterTris = Math.multiplyExact(waterQuads, 2);
        int anyHitTris = Math.addExact(Math.addExact(solidTris, emissiveTris), glassTris);
        // Coarse LOD proxies deliberately publish no RIS light records: our light hierarchy
        // (RtLightCollector/RtLightGrid) only samples emitters inside vanilla render distance, and DH/Voxy
        // emissive faces still glow through their direct-hit emission term in the closest-hit shader.
        return new PackedSection(packed.positions, packed.indices, packed.uvs, packed.prims,
                new int[]{0, anyHitTris, 0, waterTris},
                new int[]{0, 0, anyHitTris, anyHitTris},
                EMPTY_LIGHTS);
    }

    private static QuadCounts countDhQuads(byte[] bytes, boolean transparentPass, int maxLocal) {
        return countDhQuads(bytes, transparentPass, maxLocal, 0, bytes.length);
    }

    private static QuadCounts countDhQuads(byte[] bytes, boolean transparentPass, int maxLocal,
                                           int start, int end) {
        int solid = 0;
        int emissive = 0;
        int glass = 0;
        int water = 0;
        for (int q = start; q < end; q += 64) {
            if (!validDhQuad(bytes, q, maxLocal)) continue;
            int material = bytes[q + 12] & 0xFF;
            if (material == DH_MATERIAL_AIR) {
                continue;
            } else if (material == DH_MATERIAL_WATER) {
                water++;
            } else if (isDhEmissiveMaterial(material)) {
                emissive++;
            } else if (transparentPass && material != DH_MATERIAL_LEAVES) {
                glass++;
            } else {
                // Leaves can be submitted through DH's transparent pass, but they remain an opaque/cutout
                // diffuse material for RT. Treating them as dielectric glass caused mirrored distant forests.
                solid++;
            }
        }
        return new QuadCounts(solid, emissive, glass, water);
    }

    static boolean isDhEmissiveMaterial(int material) {
        return material == DH_MATERIAL_LAVA || material == DH_MATERIAL_ILLUMINATED;
    }

    /**
     * Convert DH's baked whole-block coverage to one interface's optical coverage. A full glass cube
     * normally contributes an entry and an exit face; applying the baked alpha independently to both
     * squares the colour transmittance and makes saturated stained glass look opaque. This inverse
     * composition makes the two interfaces together reproduce the requested coverage.
     */
    static float dhGlassInterfaceAlpha(float bakedAlpha) {
        float coverage = bakedAlpha >= 0.98f ? 0.24f : Math.max(0.06f, Math.min(bakedAlpha, 0.72f));
        return 1.0f - (float) Math.sqrt(1.0f - coverage);
    }

    private static boolean validDhQuad(byte[] bytes, int q, int maxLocal) {
        for (int v = 0; v < 4; v++) {
            int p = q + v * 16;
            if (u16le(bytes, p) > maxLocal || u16le(bytes, p + 4) > maxLocal) return false;
        }
        return true;
    }

    private static void decodeDhBuffer(byte[] bytes, boolean transparentPass,
                                       DistantHorizonsCompat.LodMesh mesh,
                                       int originX, int originZ, PackedMeshBuilder packed,
                                       DhMaterialPalette palette) {
        decodeDhBuffer(bytes, transparentPass, mesh, originX, originZ, packed, palette, 0, bytes.length);
    }

    private static void decodeDhBuffer(byte[] bytes, boolean transparentPass,
                                       DistantHorizonsCompat.LodMesh mesh,
                                       int originX, int originZ, PackedMeshBuilder packed,
                                       DhMaterialPalette palette, int start, int end) {
        if (start >= end) return;
        float[] xyz = new float[12]; // reused for every quad; the writer copies values immediately
        int offsetX = mesh.originX() - originX;
        int offsetZ = mesh.originZ() - originZ;
        int maxLocal = mesh.width() + 1;
        for (int q = start; q < end; q += 64) {
            if (!validDhQuad(bytes, q, maxLocal)) continue;
            int material = bytes[q + 12] & 0xFF;
            if (material == DH_MATERIAL_AIR) continue;

            for (int v = 0; v < 4; v++) {
                int p = q + v * 16;
                int out = v * 3;
                xyz[out] = offsetX + u16le(bytes, p);
                xyz[out + 1] = mesh.originY() + u16le(bytes, p + 2);
                xyz[out + 2] = offsetZ + u16le(bytes, p + 4);
            }

            // DH has already baked source texture and biome tint into vertex colour. Material identity is
            // coarse (the EDhApiBlockMaterial mini-material), but preserving it still gives distant metal,
            // wood, stone, foliage, snow, sand, water, lava and illuminated blocks distinct RT behaviour.
            float r = SRGB_TO_LINEAR[bytes[q + 8] & 0xFF];
            float g = SRGB_TO_LINEAR[bytes[q + 9] & 0xFF];
            float b = SRGB_TO_LINEAR[bytes[q + 10] & 0xFF];
            float alpha = (bytes[q + 11] & 0xFF) * (1.0f / 255.0f);
            int rtMaterial = dhRtMaterial(palette, material, transparentPass);
            if (material == DH_MATERIAL_WATER) {
                // A coarse LOD voxel is a surface approximation, not a watertight water volume. Pass its
                // source scale to closest-hit so multi-block cells use a thin dielectric interface instead
                // of accumulating Beer-Lambert absorption through artificial 2/4/8-block water cubes.
                packed.water.addQuad(xyz, r, g, b, rtMaterial, SurfaceKind.WATER,
                        material, mesh.dataPointWidth(), 0.0f);
            } else if (isDhEmissiveMaterial(material)) {
                boolean lava = material == DH_MATERIAL_LAVA;
                // The final two bytes differ slightly between DH renderer revisions but contain its packed
                // light/face metadata. Use only their low light-range values and only for a surface already
                // classified as an emitter; ordinary sunlit terrain can never become emissive here.
                float lightBoost = 0.85f + 0.45f * dhPackedLight(bytes, q) * (1.0f / 15.0f);
                float emission = (lava ? DH_LAVA_EMISSION : DH_ILLUMINATED_EMISSION) * lightBoost;
                if (transparentPass && !lava) {
                    float opticalAlpha = dhGlassInterfaceAlpha(alpha);
                    packed.solid.addQuad(xyz, r, g, b, palette.emissiveGlassId,
                            SurfaceKind.EMISSIVE_GLASS, material, emission, opticalAlpha);
                } else {
                    packed.solid.addQuad(xyz, r, g, b, rtMaterial, SurfaceKind.EMISSIVE,
                            material, emission, 0.0f);
                }
            } else if (transparentPass && material != DH_MATERIAL_LEAVES) {
                // Some DH/resource-pack combinations bake alpha=1 in the transparent VBO. Keep a conservative
                // thin-glass fallback so distant panes never collapse back to opaque.
                float opticalAlpha = dhGlassInterfaceAlpha(alpha);
                packed.glass.addQuad(xyz, r, g, b, rtMaterial, SurfaceKind.GLASS,
                        material, opticalAlpha, 0.0f);
            } else {
                packed.solid.addQuad(xyz, r, g, b, rtMaterial, SurfaceKind.SOLID,
                        material, 0.0f, 0.0f);
            }
        }
    }

    private static int dhRtMaterial(DhMaterialPalette palette, int material, boolean transparentPass) {
        if (material == DH_MATERIAL_WATER) return palette.waterId;
        if (material == DH_MATERIAL_LAVA) return palette.lavaId;
        if (material == DH_MATERIAL_ILLUMINATED) {
            return transparentPass ? palette.emissiveGlassId : palette.emissiveId;
        }
        if (transparentPass && material != DH_MATERIAL_LEAVES) return palette.glassId;
        return switch (material) {
            case DH_MATERIAL_METAL -> palette.metalId;
            case DH_MATERIAL_SNOW -> palette.smoothId;
            default -> palette.defaultId;
        };
    }

    private static int dhPackedLight(byte[] bytes, int q) {
        int strongest = 0;
        for (int v = 0; v < 4; v++) {
            int p = q + v * 16;
            int a = bytes[p + 13] & 0xFF;
            int b = bytes[p + 14] & 0xFF;
            if (a <= 15) strongest = Math.max(strongest, a);
            if (b <= 15) strongest = Math.max(strongest, b);
        }
        return strongest;
    }

    private static int u16le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static float[] makeSrgbLut() {
        float[] lut = new float[256];
        for (int i = 0; i < lut.length; i++) {
            float x = i * (1.0f / 255.0f);
            lut[i] = x <= 0.04045f ? x / 12.92f
                    : (float) Math.pow((x + 0.055f) / 1.055f, 2.4);
        }
        return lut;
    }

    private void drainCpuCompleted(RtContext ctx) {
        CpuCompleted done;
        while ((done = cpuCompleted.poll()) != null) {
            if (done.epoch != epoch) continue;
            if (done.finalBatch) {
                cpuPending = false;
                if (done.failure != null || done.plan == null || done.plan.isEmpty()) {
                    requestRetry();
                } else {
                    long now = System.nanoTime();
                    nextRefresh = now + refreshDelayNanos(now);
                }
            }
            if (done.failure != null) {
                CausticaMod.LOGGER.warn("Distant Horizons terrain buffer read failed", done.failure);
            } else if (done.plan != null && !done.plan.isEmpty()) {
                queueAtomicBuild(ctx, done.plan, done.materials, done.epoch, done.revision);
            }
        }
    }

    private void queueAtomicBuild(RtContext ctx, List<PlannedBatch> plan,
                                  RtMaterialRegistry.Snapshot materials,
                                  long taskEpoch, long revision) {
        if (taskEpoch != epoch) return;
        if (buildSession != null || pending || packPending) {
            requestRetry();
            return;
        }
        buildSession = new BuildSession(taskEpoch, revision, new ArrayDeque<>(plan), materials, current);
        CausticaMod.LOGGER.info(
                "DH progressive refresh started: current RT proxy remains active while {} planned batches stream in",
                plan.size());
        serviceBuildSession(ctx);
    }

    private void serviceBuildSession(RtContext ctx) {
        BuildSession session = buildSession;
        if (session == null || pending || packPending) return;

        while (!session.remaining.isEmpty()) {
            PlannedBatch next = session.remaining.removeFirst();
            if (next.reused != null) {
                session.workingEntries.put(next.batchKey, next.reused);
                if (session.markBatchReady(next)) evictSupersededSources(session);
                continue;
            }
            schedulePack(next, session);
            return;
        }
        if (session.workingEntries.isEmpty()) {
            abortBuildSession();
            requestRetry();
            return;
        }
        // Only the final checkpoint drops batches that DH no longer reports. Intermediate snapshots keep
        // the old geometry for not-yet-resolved sources, so progressive publication never creates holes.
        session.workingEntries.keySet().removeIf(key -> !session.finalBatchKeys.contains(key));
        publishBuildSession(ctx, true);
    }

    private void schedulePack(PlannedBatch item, BuildSession session) {
        packPending = true;
        synchronized (activeLock) {
            activeTasks++;
        }
        cpuWorker.execute(() -> {
            PackedSection packed = null;
            Throwable failure = null;
            try {
                if (session.epoch != epoch) {
                    throw new CancellationException("DH proxy epoch changed before CPU batch packing");
                }
                // Exactly one bounded quad batch is expanded into final float/int arrays at a time. The old
                // implementation expanded every active DH section up front, temporarily consuming many GB
                // of Java heap and causing the 5-10 second system-wide stalls reported on large horizons.
                packed = packDhBatch(item.slices, item.originX, item.originZ, session.materials);
            } catch (Throwable t) {
                failure = t;
            } finally {
                packCompleted.add(new PackCompleted(session.epoch, session.revision, item, packed, failure));
                finishActiveTask();
            }
        });
    }

    private void drainPackCompleted(RtContext ctx) {
        PackCompleted done;
        while ((done = packCompleted.poll()) != null) {
            packPending = false;
            BuildSession session = buildSession;
            if (done.epoch != epoch || session == null || done.revision != session.revision) {
                continue;
            }
            if (done.failure != null || done.mesh == null) {
                abortBuildSession();
                requestRetry();
                if (!(done.failure instanceof CancellationException)) {
                    CausticaMod.LOGGER.warn("Distant Horizons CPU batch packing failed", done.failure);
                }
                continue;
            }
            schedule(ctx, done.item, done.mesh, done.epoch, done.revision);
        }
    }

    private void schedule(RtContext ctx, PlannedBatch item, PackedSection mesh,
                          long taskEpoch, long revision) {
        PreparedSection prepared = null;
        boolean taskRegistered = false;
        try {
            // Only one changed/new bounded batch is prepared at a time. Unchanged source meshes never
            // allocate staging, build inputs, scratch, or replacement BLAS memory. Compaction follows the
            // same player-facing toggle as vanilla terrain sections.
            prepared = RtSectionBuilder.prepare(ctx, mesh, null,
                    CausticaConfig.Rt.Terrain.BLAS_COMPACTION.value(), item.batchKey,
                    item.originX, 0, item.originZ);
            PreparedSection owned = prepared;
            pending = true;
            synchronized (activeLock) {
                activeTasks++;
                taskRegistered = true;
            }
            ctx.gpuExecutor().submit(
                    () -> taskEpoch != epoch,
                    cmd -> {
                        RtSectionBuilder.recordUpload(cmd, owned);
                        RtAccel.recordBlasBuilds(ctx, cmd, List.of(owned.blas()));
                    },
                    () -> {
                        RtAccel.freeBlasScratch(List.of(owned.blas()));
                        owned.releaseUpload();
                    },
                    (build, failure) -> {
                        if (failure != null || taskEpoch != epoch) {
                            RtSectionBuilder.destroy(owned);
                            completed.add(new Completed(taskEpoch, revision, item, null, build,
                                    failure != null ? failure
                                            : new CancellationException("DH proxy epoch changed after BLAS build")));
                            finishActiveTask();
                            return;
                        }
                        if (owned.blas().requestsCompaction()) {
                            submitCompaction(ctx, owned, item, taskEpoch, revision, build);
                        } else {
                            owned.releaseBuildInputs();
                            completed.add(new Completed(taskEpoch, revision, item, owned, build, null));
                            finishActiveTask();
                        }
                    });
        } catch (Throwable t) {
            if (taskRegistered) finishActiveTask();
            if (prepared != null) RtSectionBuilder.destroy(prepared);
            pending = false;
            abortBuildSession();
            requestRetry();
            CausticaMod.LOGGER.warn("Failed to prepare Distant Horizons RT proxy batch", t);
        }
    }

    private void submitCompaction(RtContext ctx, PreparedSection prepared, PlannedBatch item,
                                  long taskEpoch, long revision, RtGpuExecutor.Build sourceBuild) {
        RtAccel.PreparedTerrainCompaction compaction;
        try {
            compaction = RtAccel.prepareTerrainCompaction(ctx, prepared.blas());
        } catch (Throwable t) {
            RtSectionBuilder.destroy(prepared);
            completed.add(new Completed(taskEpoch, revision, item, null, sourceBuild, t));
            finishActiveTask();
            return;
        }
        try {
            ctx.gpuExecutor().submit(
                    () -> taskEpoch != epoch,
                    cmd -> RtAccel.recordTerrainCompaction(ctx, cmd, compaction),
                    () -> {
                        RtAccel.finishTerrainCompaction(compaction);
                        prepared.releaseBuildInputs();
                    },
                    (copyBuild, failure) -> {
                        if (failure != null || taskEpoch != epoch) {
                            Throwable terminal = failure != null ? failure
                                    : new CancellationException("DH proxy epoch changed during BLAS compaction");
                            try {
                                RtAccel.destroyTerrainCompaction(compaction);
                            } catch (Throwable destroyFailure) {
                                terminal.addSuppressed(destroyFailure);
                            }
                            RtSectionBuilder.destroy(prepared);
                            completed.add(new Completed(taskEpoch, revision, item, null, copyBuild, terminal));
                        } else {
                            completed.add(new Completed(taskEpoch, revision, item,
                                    prepared.withBlas(compaction.compacted()), copyBuild, null));
                        }
                        finishActiveTask();
                    });
        } catch (Throwable t) {
            try {
                RtAccel.destroyTerrainCompaction(compaction);
            } catch (Throwable destroyFailure) {
                t.addSuppressed(destroyFailure);
            }
            RtSectionBuilder.destroy(prepared);
            completed.add(new Completed(taskEpoch, revision, item, null, sourceBuild, t));
            finishActiveTask();
        }
    }

    private void finishActiveTask() {
        synchronized (activeLock) {
            activeTasks--;
            activeLock.notifyAll();
        }
    }

    private void drainCompleted(RtContext ctx) {
        Completed done;
        while ((done = completed.poll()) != null) {
            pending = false;
            PreparedSection prepared = done.prepared;
            if (done.epoch != epoch) {
                if (prepared != null) destroyBuilt(prepared);
                continue;
            }
            BuildSession session = buildSession;
            if (done.failure != null || prepared == null || session == null
                    || done.revision != session.revision) {
                if (prepared != null) destroyBuilt(prepared);
                abortBuildSession();
                requestRetry();
                if (done.failure != null) {
                    CausticaMod.LOGGER.warn("Distant Horizons RT proxy batch failed", done.failure);
                }
                continue;
            }
            PlannedBatch item = done.item;
            RtSectionTable.SectionGeom geom = new RtSectionTable.SectionGeom(item.batchKey, prepared.uvs(),
                    prepared.material(), prepared.blas().accel, prepared.triBase(),
                    prepared.sx(), prepared.sy(), prepared.sz(), prepared.lights());
            GeomEntry entry = new GeomEntry(item.batchKey, item.sourceKey, item.sourceVersion,
                    item.originX, item.originZ, item.sourceWidth, item.dataPointWidth, geom);
            session.workingEntries.put(item.batchKey, entry);
            session.owned.add(geom);
            session.builtCount++;
            if (session.markBatchReady(item)) evictSupersededSources(session);
            session.lastBuild = done.build;
            maybePublishProgress(ctx, session);
        }
    }

    private void evictSupersededSources(BuildSession session) {
        List<LodRect> ready = session.readyRects();
        if (ready.isEmpty()) return;
        HashMap<Long, LodRect> stale = new HashMap<>();
        for (GeomEntry entry : session.workingEntries.values()) {
            if (!session.finalSourceKeys.contains(entry.sourceKey)) {
                stale.putIfAbsent(entry.sourceKey, entry.rect());
            }
        }
        if (stale.isEmpty()) return;
        HashSet<Long> removable = new HashSet<>();
        for (Map.Entry<Long, LodRect> entry : stale.entrySet()) {
            if (fullyCoveredBy(entry.getValue(), ready)) removable.add(entry.getKey());
        }
        if (removable.isEmpty()) return;
        int before = session.workingEntries.size();
        session.workingEntries.entrySet().removeIf(entry -> removable.contains(entry.getValue().sourceKey));
        int removedBatches = before - session.workingEntries.size();
        if (removedBatches > 0) {
            session.coverageChanged = true;
            CausticaMod.LOGGER.info(
                    "DH RT refinement replaced {} coarse source mesh(es) / {} BLAS batch(es) immediately",
                    removable.size(), removedBatches);
        }
    }

    private void maybePublishProgress(RtContext ctx, BuildSession session) {
        if (session != buildSession || session.workingEntries.isEmpty()) return;
        int unpublished = session.builtCount - session.publishedBuiltCount;
        int threshold = current == null && !session.publishedAny
                ? INITIAL_PROGRESS_BATCHES : STEADY_PROGRESS_BATCHES;
        long now = System.nanoTime();
        if (session.coverageChanged || unpublished >= threshold
                || (unpublished > 0 && now - session.lastPublishNanos >= PROGRESS_PUBLISH_NANOS)) {
            publishBuildSession(ctx, false);
        }
    }

    private void publishBuildSession(RtContext ctx, boolean finished) {
        BuildSession session = buildSession;
        RtBuffer table = null;
        try {
            List<GeomEntry> entries = List.copyOf(session.workingEntries.values());
            int count = entries.size();
            if (count == 0) {
                if (finished) {
                    abortBuildSession();
                    requestRetry();
                }
                return;
            }
            table = ctx.createBuffer(Math.multiplyExact(32L, count), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                    "Distant Horizons RT proxy table " + count + " batches");
            long p = table.mapped;
            for (int section = 0; section < count; section++) {
                RtSectionTable.SectionGeom geom = entries.get(section).geom;
                long entry = p + section * 32L;
                MemoryUtil.memPutLong(entry, geom.material.deviceAddress);
                MemoryUtil.memPutLong(entry + 8, geom.uvs.deviceAddress);
                for (int i = 0; i < 4; i++) {
                    MemoryUtil.memPutInt(entry + 16L + i * 4L, geom.triBase[i]);
                }
            }
            table.flush();
            if (session.lastBuild != null) {
                ctx.gpuExecutor().markPublished(session.lastBuild);
                session.lastBuild = null;
            }

            Proxy next = new Proxy(session.revision, entries, table, finished);
            Proxy old = current;
            current = next;
            instanceProxy = null;
            frameDhInstances = new RtAccel.Instance[0];
            session.owned.clear(); // ownership transferred to the newly published proxy
            session.publishedBuiltCount = session.builtCount;
            session.lastPublishNanos = System.nanoTime();
            session.publishedAny = true;
            session.coverageChanged = false;

            if (old != null) {
                Set<RtSectionTable.SectionGeom> retained = next.geomIdentitySet();
                var lastUse = ctx.gpuExecutor().latestGraphicsUse();
                ctx.gpuExecutor().retireAfterGraphics(lastUse, () -> old.destroyExcept(retained));
            }

            if (finished) {
                bootstrapComplete = true;
                buildSession = null;
                long now = System.nanoTime();
                nextRefresh = now + refreshDelayNanos(now);
                CausticaMod.LOGGER.info(
                        "Published final DH RT proxy as {} compacted BLAS instances ({} reused, {} rebuilt)",
                        count, session.reusedCount, session.builtCount);
            } else {
                CausticaMod.LOGGER.info(
                        "Published progressive DH RT checkpoint: {} BLAS instances, {}/{} changed batches ready",
                        count, session.builtCount, session.totalBuildCount);
            }
        } catch (Throwable t) {
            if (table != null) table.destroy();
            abortBuildSession();
            requestRetry();
            CausticaMod.LOGGER.warn("Failed to publish Distant Horizons RT proxy", t);
        }
    }

    private void abortBuildSession() {
        BuildSession session = buildSession;
        buildSession = null;
        if (session != null) {
            for (RtSectionTable.SectionGeom geom : session.owned) geom.destroy();
            session.owned.clear();
            session.workingEntries.clear();
            session.remaining.clear();
        }
    }

    private void requestRetry() {
        // A transient DH reflection/read/GPU failure must not permanently mark the current revision as
        // consumed. Re-arm the same revision after a short delay while leaving the previous RT proxy live.
        capturedLodRevision = -1L;
        nextRefresh = System.nanoTime() + RETRY_NANOS;
    }

    private void reset(RtContext ctx, boolean clearCapturedLods) {
        epoch++;
        anchorX = anchorZ = Integer.MIN_VALUE;
        nextRefresh = 0L;
        capturedLodRevision = -1L;
        earliestBuildNanos = 0L;
        nextQualityPollNanos = 0L;
        qualitySettleUntilNanos = 0L;
        lodQuality = new DistantHorizonsCompat.LodQuality(0L, 16, 2, "UNKNOWN", "UNKNOWN");
        cpuPending = false;
        bootstrapComplete = false;
        manualRefreshRequested.set(false);
        forceSourceRebuild = false;
        abortBuildSession();
        world = null;
        instanceProxy = null;
        frameDhInstances = new RtAccel.Instance[0];
        if (clearCapturedLods) {
            DistantHorizonsCompat.clearCapturedLods();
        }
        Proxy old = current;
        current = null;
        if (old != null) {
            var lastUse = ctx.gpuExecutor().latestGraphicsUse();
            ctx.gpuExecutor().retireAfterGraphics(lastUse, old::destroy);
        }
    }

    public void shutdown(RtContext ctx) {
        epoch++;
        synchronized (activeLock) {
            while (activeTasks != 0) {
                try {
                    activeLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while joining DH proxy build", e);
                }
            }
        }
        ctx.waitIdle();
        Completed done;
        while ((done = completed.poll()) != null) {
            if (done.prepared != null) destroyBuilt(done.prepared);
        }
        packCompleted.clear();
        packPending = false;
        abortBuildSession();
        if (current != null) current.destroy();
        current = null;
        instanceProxy = null;
        frameDhInstances = new RtAccel.Instance[0];
        pending = false;
        cpuPending = false;
        packPending = false;
        bootstrapComplete = false;
        manualRefreshRequested.set(false);
        forceSourceRebuild = false;
        DistantHorizonsCompat.clearCapturedLods();
    }

    private static void destroyBuilt(PreparedSection prepared) {
        prepared.blas().accel.destroy();
        prepared.material().destroy();
        prepared.uvs().destroy();
    }

    private record Completed(long epoch, long revision, PlannedBatch item, PreparedSection prepared,
                             RtGpuExecutor.Build build, Throwable failure) {
    }

    private record CpuCompleted(long epoch, long revision, List<PlannedBatch> plan,
                                RtMaterialRegistry.Snapshot materials,
                                Throwable failure, boolean finalBatch) {
    }

    private record PackCompleted(long epoch, long revision, PlannedBatch item,
                                 PackedSection mesh, Throwable failure) {
    }

    private record PlannedBatch(long batchKey, long sourceKey, long sourceVersion, int originX, int originZ,
                                int sourceWidth, int dataPointWidth, List<DhSlice> slices, GeomEntry reused) {
        static PlannedBatch build(long batchKey, long sourceKey, long sourceVersion, int originX, int originZ,
                                  int sourceWidth, int dataPointWidth, List<DhSlice> slices) {
            return new PlannedBatch(batchKey, sourceKey, sourceVersion, originX, originZ,
                    sourceWidth, dataPointWidth, slices, null);
        }

        static PlannedBatch reuse(GeomEntry entry) {
            return new PlannedBatch(entry.batchKey, entry.sourceKey, entry.sourceVersion,
                    entry.originX, entry.originZ, entry.sourceWidth, entry.dataPointWidth, null, entry);
        }
    }

    private static final class BuildSession {
        final long epoch;
        final long revision;
        final ArrayDeque<PlannedBatch> remaining;
        final RtMaterialRegistry.Snapshot materials;
        final LinkedHashMap<Long, GeomEntry> workingEntries = new LinkedHashMap<>();
        final Set<Long> finalBatchKeys = new HashSet<>();
        final Set<Long> finalSourceKeys = new HashSet<>();
        final Map<Long, SourceProgress> sourceProgress = new HashMap<>();
        final Set<RtSectionTable.SectionGeom> owned =
                Collections.newSetFromMap(new IdentityHashMap<>());
        final int totalBuildCount;
        RtGpuExecutor.Build lastBuild;
        int reusedCount;
        int builtCount;
        int publishedBuiltCount;
        long lastPublishNanos = System.nanoTime();
        boolean publishedAny;
        boolean coverageChanged;

        BuildSession(long epoch, long revision, ArrayDeque<PlannedBatch> remaining,
                     RtMaterialRegistry.Snapshot materials, Proxy base) {
            this.epoch = epoch;
            this.revision = revision;
            this.remaining = remaining;
            this.materials = materials;
            if (base != null) {
                for (GeomEntry entry : base.entries) workingEntries.put(entry.batchKey, entry);
            }
            int builds = 0;
            for (PlannedBatch batch : remaining) {
                finalBatchKeys.add(batch.batchKey);
                finalSourceKeys.add(batch.sourceKey);
                sourceProgress.computeIfAbsent(batch.sourceKey, ignored -> new SourceProgress(
                        new LodRect(batch.sourceKey, batch.originX, batch.originZ,
                                batch.sourceWidth, batch.dataPointWidth))).totalBatches++;
                if (batch.reused != null) reusedCount++;
                else builds++;
            }
            totalBuildCount = builds;
        }

        boolean markBatchReady(PlannedBatch batch) {
            SourceProgress progress = sourceProgress.get(batch.sourceKey);
            if (progress == null || progress.readyBatches >= progress.totalBatches) return false;
            progress.readyBatches++;
            if (progress.readyBatches == progress.totalBatches) {
                progress.ready = true;
                return true;
            }
            return false;
        }

        List<LodRect> readyRects() {
            ArrayList<LodRect> result = new ArrayList<>();
            for (SourceProgress progress : sourceProgress.values()) {
                if (progress.ready) result.add(progress.rect);
            }
            return result;
        }
    }

    private static final class SourceProgress {
        final LodRect rect;
        int totalBatches;
        int readyBatches;
        boolean ready;

        SourceProgress(LodRect rect) {
            this.rect = rect;
        }
    }

    private record LodRect(long key, int x, int z, int width, int detailWidth) {
    }

    private record GeomEntry(long batchKey, long sourceKey, long sourceVersion,
                             int originX, int originZ, int sourceWidth, int dataPointWidth,
                             RtSectionTable.SectionGeom geom) {
        LodRect rect() {
            return new LodRect(sourceKey, originX, originZ, sourceWidth, dataPointWidth);
        }
    }

    private record SourceState(long version, List<GeomEntry> entries) {
    }

    private record DhMaterialPalette(int defaultId, int metalId, int smoothId,
                                     int glassId, int waterId, int lavaId,
                                     int emissiveId, int emissiveGlassId) {
    }

    private record DhSlice(DistantHorizonsCompat.LodMesh mesh, byte[] bytes, boolean transparentPass,
                           int start, int end, QuadCounts counts) {
    }

    private static final class SurfaceKind {
        static final int SOLID = 0;
        static final int WATER = 1;
        static final int GLASS = 2;
        static final int EMISSIVE = 3;
        static final int EMISSIVE_GLASS = 4;

        private SurfaceKind() {
        }
    }

    private record QuadCounts(int solid, int emissive, int glass, int water) {
        int total() {
            return Math.addExact(Math.addExact(solid, emissive), Math.addExact(glass, water));
        }
    }

    /**
     * Owns the exact final arrays and independent category cursors. Opaque and emissive quads share the
     * solid range, followed by glass and water, so Vulkan ranges stay contiguous without a second copy.
     */
    private static final class PackedMeshBuilder {
        final float[] positions;
        final int[] indices;
        final float[] uvs;
        final float[] prims;
        final MeshWriter solid;
        final MeshWriter glass;
        final MeshWriter water;

        PackedMeshBuilder(int solidQuads, int glassQuads, int waterQuads) {
            int totalQuads = Math.addExact(Math.addExact(solidQuads, glassQuads), waterQuads);
            positions = new float[elementCount(totalQuads, 12)];
            indices = new int[elementCount(totalQuads, 6)];
            uvs = new float[elementCount(totalQuads, 12)];
            prims = new float[elementCount(totalQuads, 24)];
            solid = new MeshWriter(this, 0, solidQuads);
            glass = new MeshWriter(this, solidQuads, glassQuads);
            water = new MeshWriter(this, Math.addExact(solidQuads, glassQuads), waterQuads);
        }

        void requireComplete() {
            solid.requireComplete("solid");
            glass.requireComplete("glass");
            water.requireComplete("water");
        }

        private static int elementCount(int quads, int perQuad) {
            return Math.multiplyExact(quads, perQuad);
        }
    }

    private static final class MeshWriter {
        private final PackedMeshBuilder packed;
        private final int endQuad;
        private int nextQuad;

        MeshWriter(PackedMeshBuilder packed, int firstQuad, int quadCount) {
            this.packed = packed;
            this.nextQuad = firstQuad;
            this.endQuad = Math.addExact(firstQuad, quadCount);
        }

        void addQuad(float[] p, float r, float g, float b, int materialId, int surfaceKind,
                     int dhMaterial, float aux0Value, float aux1Value) {
            if (nextQuad >= endQuad) {
                throw new IllegalStateException("Distant Horizons quad count changed while packing");
            }
            int quad = nextQuad++;
            int positionOffset = quad * 12;
            System.arraycopy(p, 0, packed.positions, positionOffset, 12);

            int vertex = quad * 4;
            int indexOffset = quad * 6;
            packed.indices[indexOffset] = vertex;
            packed.indices[indexOffset + 1] = vertex + 1;
            packed.indices[indexOffset + 2] = vertex + 2;
            packed.indices[indexOffset + 3] = vertex;
            packed.indices[indexOffset + 4] = vertex + 2;
            packed.indices[indexOffset + 5] = vertex + 3;

            int uvOffset = quad * 12;
            packed.uvs[uvOffset] = 0f;
            packed.uvs[uvOffset + 1] = 0f;
            packed.uvs[uvOffset + 2] = 1f;
            packed.uvs[uvOffset + 3] = 0f;
            packed.uvs[uvOffset + 4] = 1f;
            packed.uvs[uvOffset + 5] = 1f;
            packed.uvs[uvOffset + 6] = 0f;
            packed.uvs[uvOffset + 7] = 0f;
            packed.uvs[uvOffset + 8] = 1f;
            packed.uvs[uvOffset + 9] = 1f;
            packed.uvs[uvOffset + 10] = 0f;
            packed.uvs[uvOffset + 11] = 1f;

            float ax = p[3] - p[0];
            float ay = p[4] - p[1];
            float az = p[5] - p[2];
            float bx = p[6] - p[0];
            float by = p[7] - p[1];
            float bz = p[8] - p[2];
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            float inv = 1f / Math.max(1e-6f, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
            nx *= inv;
            ny *= inv;
            nz *= inv;

            int primitiveOffset = quad * 24;
            writePrimitive(packed.prims, primitiveOffset, nx, ny, nz, r, g, b,
                    materialId, surfaceKind, dhMaterial, aux0Value, aux1Value);
            writePrimitive(packed.prims, primitiveOffset + 12, nx, ny, nz, r, g, b,
                    materialId, surfaceKind, dhMaterial, aux0Value, aux1Value);
        }

        void requireComplete(String category) {
            if (nextQuad != endQuad) {
                throw new IllegalStateException("Distant Horizons " + category + " quad count changed while packing: "
                        + nextQuad + " != " + endQuad);
            }
        }

        private static void writePrimitive(float[] out, int offset, float nx, float ny, float nz,
                                           float r, float g, float b, int materialId, int surfaceKind,
                                           int dhMaterial, float aux0Value, float aux1Value) {
            out[offset] = nx;
            out[offset + 1] = ny;
            out[offset + 2] = nz;
            out[offset + 3] = 0f;
            out[offset + 4] = r;
            out[offset + 5] = g;
            out[offset + 6] = b;
            out[offset + 7] = surfaceKind;
            out[offset + 8] = Float.intBitsToFloat(materialId);
            out[offset + 9] = Float.intBitsToFloat(dhMaterial); // TerrainPrim.flags: original DH mini-material
            out[offset + 10] = aux0Value;
            out[offset + 11] = aux1Value;
        }
    }

    /** Random-access list view of terrain + bounded DH instances without copying the terrain list. */
    private static final class AppendedInstanceList extends AbstractList<RtAccel.Instance>
            implements RandomAccess {
        private List<RtAccel.Instance> base = List.of();
        private RtAccel.Instance[] tails = new RtAccel.Instance[0];

        void reset(List<RtAccel.Instance> base, RtAccel.Instance[] tails) {
            this.base = base;
            this.tails = tails;
        }

        @Override
        public RtAccel.Instance get(int index) {
            int baseSize = base.size();
            if (index < baseSize) return base.get(index);
            int tailIndex = index - baseSize;
            if (tailIndex >= 0 && tailIndex < tails.length) return tails[tailIndex];
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public int size() {
            return base.size() + tails.length;
        }
    }

    private static final class Proxy {
        final long revision;
        final List<GeomEntry> entries;
        final Map<Long, SourceState> sources;
        final RtBuffer table;

        Proxy(long revision, List<GeomEntry> entries, RtBuffer table, boolean sourceSetComplete) {
            this.revision = revision;
            this.entries = entries;
            this.table = table;
            HashMap<Long, ArrayList<GeomEntry>> grouped = new HashMap<>();
            for (GeomEntry entry : entries) {
                grouped.computeIfAbsent(entry.sourceKey, ignored -> new ArrayList<>()).add(entry);
            }
            HashMap<Long, SourceState> sourceStates = new HashMap<>(grouped.size() * 2);
            for (Map.Entry<Long, ArrayList<GeomEntry>> group : grouped.entrySet()) {
                List<GeomEntry> sourceEntries = List.copyOf(group.getValue());
                long version = sourceSetComplete ? sourceEntries.get(0).sourceVersion : Long.MIN_VALUE;
                if (sourceSetComplete) {
                    for (GeomEntry entry : sourceEntries) {
                        if (entry.sourceVersion != version) {
                            version = Long.MIN_VALUE;
                            break;
                        }
                    }
                }
                // Progressive checkpoints can contain only part of a new/changed DH source. Mark those
                // snapshots non-reusable so a retry can never mistake partial geometry for a complete VBO.
                sourceStates.put(group.getKey(), new SourceState(version, sourceEntries));
            }
            this.sources = Map.copyOf(sourceStates);
        }

        Set<RtSectionTable.SectionGeom> geomIdentitySet() {
            Set<RtSectionTable.SectionGeom> result =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            for (GeomEntry entry : entries) result.add(entry.geom);
            return result;
        }

        void destroyExcept(Set<RtSectionTable.SectionGeom> retained) {
            table.destroy();
            for (GeomEntry entry : entries) {
                if (!retained.contains(entry.geom)) entry.geom.destroy();
            }
        }

        void destroy() {
            destroyExcept(Set.of());
        }
    }
}
