package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.xys.vulkanrt.geometry.ChunkCoordinates;
import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import dev.xys.vulkanrt.geometry.TerrainDrawCapture;
import dev.xys.vulkanrt.geometry.SectionGeometrySanity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** One cached BLAS per actual SOLID section draw; a single TLAS contains the entire valid set.
 * Capture, lifetime validation and enqueue all precede vanilla's later heap uploads/reuse.
 * No compiler interception, CPU vertex readback or second terrain mesher.
 * Geometry/cache user-verified with 444 sections in 0.5.0; texture-quality correction pending runtime validation. */
public final class WorldGeometryManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("native_vulkan_rt");
    private final VulkanRayTracingContext context;
    private final AccelerationStructureManager acceleration;
    private Map<Long, Resident> residents = Map.of();
    private AccelerationStructureManager.Structure tlas;
    private ChunkCoordinates.Anchor anchor;
    private ChunkMaterialTable materials;
    private String waitReason, invalidReason;
    private long lastReport, lastDetail;
    private int buildsSinceReport, retiresSinceReport;
    private record Resident(long section, Object owner, SectionMesh mesh, VulkanGpuBuffer source, long offset,
                            SectionGeometryLayout layout, AccelerationStructureManager.Structure blas) {}

    public WorldGeometryManager(VulkanRayTracingContext context, double x, double y, double z) {
        this.context = context;
        acceleration = new AccelerationStructureManager(context);
        anchor = ChunkCoordinates.Anchor.near(x, y, z);
        var pin = ChunkCoordinates.parseSection(System.getProperty("nativevulkanrt.section"));
        LOG.info("[RT] Scene: chunks; selection={}; BLAS cache keyed by section/owner/mesh/allocation",
                pin == null ? "ALL eligible SOLID terrain draws" : "diagnostic pin " + coordinates(pin));
    }

    public final class Prepared {
        public final AccelerationStructureManager.Structure tlas;
        public final ChunkCoordinates.Anchor anchor;
        public final ChunkMaterialTable materials;
        private final Map<Long, Resident> next;
        private final int built;
        private boolean committed;
        private Prepared(AccelerationStructureManager.Structure tlas, Map<Long, Resident> next,
                         ChunkCoordinates.Anchor anchor, int built, ChunkMaterialTable materials) {
            this.tlas = tlas; this.next = next; this.anchor = anchor; this.built = built; this.materials = materials;
        }
        /** Only after enqueue: old TLAS/BLAS remain alive through all earlier GPU consumers. */
        public void commit() {
            if (committed) throw new IllegalStateException("Scene committed twice");
            committed = true;
            boolean emptyTransition = residents.isEmpty() != next.isEmpty();
            if (WorldGeometryManager.this.tlas != null && WorldGeometryManager.this.tlas != tlas)
                context.retire(WorldGeometryManager.this.tlas);
            if (WorldGeometryManager.this.materials != null && WorldGeometryManager.this.materials != materials)
                context.retire(WorldGeometryManager.this.materials);
            WorldGeometryManager.this.materials = materials;
            int retired = SectionResidency.retireReplaced(residents, next, section -> context.retire(section.blas()));
            residents = next; WorldGeometryManager.this.tlas = tlas; WorldGeometryManager.this.anchor = anchor;
            buildsSinceReport += built; retiresSinceReport += retired;
            report(emptyTransition, next.size()-built);
        }
    }

    /** No extra GPU command buffer, copies or AS work for an unchanged set, even if draw order changes. */
    public Prepared reuseUnchangedDraws(LevelRenderer level, double x, double y, double z) {
        if (!TerrainDrawCapture.ready(level, level.sectionRenderDispatcher()) || residents.isEmpty()) return null;
        var draws = TerrainDrawCapture.draws();
        var nextAnchor = ChunkCoordinates.Anchor.near(x,y,z);
        if (draws.size() != residents.size() || !nextAnchor.equals(anchor)) return null;
        for (var receipt : draws.values()) {
            if (!liveReceipt(receipt) || !matches(residents.get(receipt.section()), receipt)) return null;
        }
        return new Prepared(tlas, residents, anchor, 0, materials);
    }

    public Prepared prepare(CommandBatch batch, LevelRenderer level, double x, double y, double z) {
        var nextAnchor = ChunkCoordinates.Anchor.near(x,y,z);
        var dispatcher = level.sectionRenderDispatcher();
        if (dispatcher == null) return empty(nextAnchor, "DISPATCHER_MISSING");
        if (!TerrainDrawCapture.ready(level, dispatcher)) return empty(nextAnchor, TerrainDrawCapture.failure());
        if (TerrainDrawCapture.draws().isEmpty()) {
            TerrainDrawCapture.diagnostics(level, false);
            return empty(nextAnchor, TerrainDrawCapture.failure());
        }
        dispatcher.lock();
        try {
            // Validate each receipt independently: one invalidated mesh must not drop the other sections.
            var valid = new TreeMap<Long, TerrainDrawCapture.Draw>();
            String firstInvalid = null;
            var format = ChunkSectionLayer.SOLID.pipeline(false).getVertexFormatBinding(0);
            for (var receipt : TerrainDrawCapture.draws().values()) {
                String reason = null;
                if (!TerrainDrawCapture.current(receipt)) reason = "STALE_TERRAIN_DRAW_RECEIPT";
                else {
                    var slice = dispatcher.getRenderSectionSlice(receipt.mesh(), ChunkSectionLayer.SOLID);
                    var sanity = SectionGeometrySanity.inspect(receipt.owner(),receipt.section(),receipt.mesh(),slice,format);
                    if (sanity != SectionGeometrySanity.Failure.OK) reason = sanity.name();
                    else if (slice.vertexBuffer() != receipt.buffer() || slice.vertexBufferOffset() != receipt.offset())
                        reason = "ALLOCATION_CHANGED_AFTER_TERRAIN_DRAW_CAPTURE";
                    else if (receipt.mesh().getSectionDraw(ChunkSectionLayer.SOLID).indexCount() != receipt.layout().indexCount())
                        reason = "DRAW_COUNT_CHANGED_AFTER_CAPTURE";
                }
                if (reason == null) valid.put(receipt.section(), receipt);
                else if (firstInvalid == null) firstInvalid = coordinates(receipt.section()) + ": " + reason;
            }
            if (firstInvalid != null && !firstInvalid.equals(invalidReason))
                LOG.info("[RT] Chunks: invalidatedBeforeCopy={}; first={}", TerrainDrawCapture.validSections()-valid.size(), firstInvalid);
            invalidReason = firstInvalid;
            if (valid.isEmpty()) return empty(nextAnchor, "NO_VALID_RECEIPTS_BEFORE_COPY: " + firstInvalid);
            // Check before allocating any new BLAS; never silently truncate the captured set.
            if (Long.compareUnsigned(valid.size(), context.capabilities().maxInstanceCount()) > 0)
                throw new IllegalArgumentException("validSections=" + valid.size() + " exceeds device maxInstanceCount="
                        + Long.toUnsignedString(context.capabilities().maxInstanceCount()));

            var diff = SectionResidency.diff(residents, valid, WorldGeometryManager::matches);
            var next = new TreeMap<Long, Resident>(); // Stable ordering independent of vanilla/frustum draw order.
            for (long node : diff.reuse()) next.put(node, residents.get(node));
            boolean detail = TerrainDrawCapture.diagnosticsEnabled() && System.nanoTime()-lastDetail >= 2_000_000_000L;
            int detailed = 0;
            for (long node : diff.build()) {
                var receipt = valid.get(node);
                var layout = receipt.layout();
                if (detail && detailed++ < 4) {
                    LOG.info("[RT][chunks-diag] Build section={} mesh={} UberGpuBuffer VkBuffer=0x{} offset={} bytes={} SOLID vertices={} triangles={} sanity=OK",
                            coordinates(node), SectionGeometrySanity.identity(receipt.mesh()), Long.toHexString(receipt.buffer().vkBuffer()),
                            receipt.offset(), layout.vertexBytes(), layout.vertexCount(), layout.triangles());
                }
                var blas = batch.own(acceleration.buildSectionBlas(batch, receipt.buffer(), receipt.offset(), layout));
                next.put(node, new Resident(node, receipt.owner(), receipt.mesh(), receipt.buffer(), receipt.offset(), layout, blas));
            }
            if (detail && detailed > 0) lastDetail = System.nanoTime();
            var action = SectionResidency.tlasAction(residents.size(), next.size(), diff.changed(), !nextAnchor.equals(anchor));
            var nextTlas = tlas;
            if (action != SectionResidency.TlasAction.REUSE) {
                var instances = new ArrayList<AccelerationStructureManager.Instance>(next.size());
                for (var section : next.values()) {
                    long node = section.section();
                    instances.add(new AccelerationStructureManager.Instance(section.blas(), nextAnchor.sectionX(node),
                            nextAnchor.sectionY(node), nextAnchor.sectionZ(node), 0)); // Existing hit group/material convention.
                }
                if (action == SectionResidency.TlasAction.BUILD) nextTlas = batch.own(acceleration.buildTlas(batch, instances));
                else acceleration.updateTlas(batch, tlas, instances);
                LOG.debug("[RT] TLAS {}: {} instances; BLAS built={}, reused={}, retired={}; anchor=({}, {}, {})",
                        action, next.size(), diff.build().size(), diff.reuse().size(), diff.retire().size(), nextAnchor.x(), nextAnchor.y(), nextAnchor.z());
            }
            var nextMaterials = materials;
            if (diff.changed()) {
                var entries = new ArrayList<ChunkMaterialTable.Entry>(next.size());
                for (var section : next.values()) entries.add(new ChunkMaterialTable.Entry(section.section(),section.blas().vertexAddress(),section.layout()));
                nextMaterials = batch.own(new ChunkMaterialTable(context,batch,entries));
                LOG.debug("[RT] Material rows={} in TLAS instance order; no vertex readback", entries.size());
            }
            waitReason = null;
            return new Prepared(nextTlas, next, nextAnchor, diff.build().size(), nextMaterials);
        } finally { dispatcher.unlock(); }
    }

    private static boolean liveReceipt(TerrainDrawCapture.Draw receipt) {
        return TerrainDrawCapture.current(receipt) && receipt.owner().getSectionNode() == receipt.section()
                && receipt.owner().getSectionMesh() == receipt.mesh() && !receipt.buffer().isClosed();
    }
    private static boolean matches(Resident resident, TerrainDrawCapture.Draw receipt) {
        return resident != null && resident.section() == receipt.section()
                && resident.layout().equals(receipt.layout())
                && sameSource(resident.owner(), resident.mesh(), resident.source(), resident.offset(),
                        receipt.owner(), receipt.mesh(), receipt.buffer(), receipt.offset());
    }
    static boolean sameSource(Object oldOwner, Object oldMesh, Object oldBuffer, long oldOffset,
                              Object owner, Object mesh, Object buffer, long offset) {
        return oldOwner != null && oldOwner == owner && oldMesh == mesh && oldBuffer == buffer && oldOffset == offset;
    }
    private Prepared empty(ChunkCoordinates.Anchor nextAnchor, String reason) {
        if (!reason.equals(waitReason)) { waitReason = reason; LOG.info("[RT] Chunks: {}", reason); }
        return new Prepared(null, Map.of(), nextAnchor, 0, null);
    }
    private void report(boolean force, int reusedThisFrame) {
        if (!force && System.nanoTime()-lastReport < 2_000_000_000L) return;
        lastReport = System.nanoTime();
        long triangles = 0, vertexBytes = 0;
        for (var resident : residents.values()) { triangles += resident.layout().triangles(); vertexBytes += resident.layout().vertexBytes(); }
        LOG.info("[RT] Scene: chunks | validSections={} | BLAS count: {} | TLAS instances: {} | RT triangles: {} | vertexBytes={}",
                TerrainDrawCapture.validSections(), residents.size(), tlas == null ? 0 : tlas.count, triangles, vertexBytes);
        LOG.info("[RT] Chunk cache: builtSinceReport={} retiredSinceReport={} (deferred) reusedThisFrame={}; section sample={}{}",
                buildsSinceReport, retiresSinceReport, reusedThisFrame, residents.keySet().stream().limit(8).map(WorldGeometryManager::coordinates).collect(Collectors.joining(", ")),
                residents.size() > 8 ? ", ..." : "");
        buildsSinceReport = 0; retiresSinceReport = 0;
    }
    private static String coordinates(long node) { return "("+SectionPos.x(node)+","+SectionPos.y(node)+","+SectionPos.z(node)+")"; }
    @Override public void close() {
        if (tlas != null) { tlas.close(); tlas = null; }
        if (materials != null) { materials.close(); materials = null; }
        residents.values().forEach(section -> section.blas().close());
        residents = Map.of();
    }
}
