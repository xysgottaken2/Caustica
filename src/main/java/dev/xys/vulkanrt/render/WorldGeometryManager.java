package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.xys.vulkanrt.geometry.ChunkCoordinates;
import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import net.minecraft.client.renderer.LevelRenderer;
import dev.xys.vulkanrt.geometry.TerrainDrawCapture;
import dev.xys.vulkanrt.geometry.SectionGeometrySanity;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/** One accepted SOLID section, from the same UberGpuBuffer slice used by terrain MDI.
 * No compiler interception, CPU vertex readback, second mesher or all-world BLAS loop.
 * Consume the receipt from vanilla draw extraction before later uploads can recycle the source;
 * copy/build ONLY when the accepted mesh/owner/allocation identity changes.
 * Chunk runtime unverified. The established triangle path is independent of this manager. */
public final class WorldGeometryManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("native_vulkan_rt");
    private final VulkanRayTracingContext context;
    private final AccelerationStructureManager acceleration;
    private final Long pinnedSection;
    private Resident resident;
    private AccelerationStructureManager.Structure tlas;
    private ChunkCoordinates.Anchor anchor;
    private String waitReason;
    private record Resident(long section, Object owner, SectionMesh mesh, VulkanGpuBuffer source, long offset,
                            SectionGeometryLayout layout, AccelerationStructureManager.Structure blas) {}

    public WorldGeometryManager(VulkanRayTracingContext context, double x, double y, double z) {
        this.context = context;
        acceleration = new AccelerationStructureManager(context);
        anchor = ChunkCoordinates.Anchor.near(x, y, z);
        pinnedSection = ChunkCoordinates.parseSection(System.getProperty("nativevulkanrt.section"));
        LOG.info("[RT] Scene: chunks; milestone limit=1 SOLID section; selection={}",
                pinnedSection == null ? "actual eligible terrain draws (no 3x3x3 cutoff), sticky within camera section"
                        : "pinned " + SectionPos.x(pinnedSection) + "," + SectionPos.y(pinnedSection) + "," + SectionPos.z(pinnedSection));
        counters(null);
    }

    public final class Prepared {
        public final AccelerationStructureManager.Structure tlas;
        public final ChunkCoordinates.Anchor anchor;
        private final Resident next;
        private boolean committed;
        private Prepared(AccelerationStructureManager.Structure tlas, Resident next, ChunkCoordinates.Anchor anchor) {
            this.tlas = tlas; this.next = next; this.anchor = anchor;
        }
        public void commit() {
            if (committed) throw new IllegalStateException("Scene committed twice");
            committed = true;
            boolean changed = resident != next;
            if (WorldGeometryManager.this.tlas != null && WorldGeometryManager.this.tlas != tlas)
                context.retire(WorldGeometryManager.this.tlas);
            if (resident != null && resident != next) {
                context.retire(resident.blas());
                LOG.info("[RT] Chunk section replaced/removed: x={}, y={}, z={}; old BLAS retired after in-flight work",
                        SectionPos.x(resident.section()), SectionPos.y(resident.section()), SectionPos.z(resident.section()));
            }
            resident = next; WorldGeometryManager.this.tlas = tlas; WorldGeometryManager.this.anchor = anchor;
            TerrainDrawCapture.preferSection(next == null ? null : next.section());
            if (changed) counters(next);
        }
    }

    /** No extra command buffer, copy or AS build for an unchanged draw and anchor. */
    public Prepared reuseUnchangedDraw(double x, double y, double z) {
        var receipt = TerrainDrawCapture.selected();
        var nextAnchor = ChunkCoordinates.Anchor.near(x,y,z);
        if (resident == null || !TerrainDrawCapture.current(receipt) || !nextAnchor.equals(anchor)) return null;
        if (receipt.section() != resident.section() || receipt.owner().getSectionNode() != receipt.section()
                || receipt.owner().getSectionMesh() != receipt.mesh() || receipt.buffer().isClosed()) return null;
        if (!sameSource(resident.owner(),resident.mesh(),resident.source(),resident.offset(),
                receipt.owner(),receipt.mesh(),receipt.buffer(),receipt.offset())) return null;
        return new Prepared(tlas, resident, anchor);
    }

    public Prepared prepare(CommandBatch batch, LevelRenderer level, double x, double y, double z) {
        var nextAnchor = ChunkCoordinates.Anchor.near(x, y, z);
        var dispatcher = level.sectionRenderDispatcher();
        if (dispatcher == null) return empty(nextAnchor, "DISPATCHER_MISSING");
        if (!TerrainDrawCapture.ready(level, dispatcher)) return empty(nextAnchor, TerrainDrawCapture.failure());
        var receipt = TerrainDrawCapture.selected();
        if (receipt == null) {
            TerrainDrawCapture.diagnostics(level, false);
            return empty(nextAnchor, TerrainDrawCapture.failure());
        }
        dispatcher.lock();
        try {
            if (!TerrainDrawCapture.current(receipt)) return empty(nextAnchor, "STALE_TERRAIN_DRAW_RECEIPT");
            var selected = receipt.owner();
            long node = receipt.section();
            var mesh = receipt.mesh();
            // This is lifetime validation of the captured receipt, NOT selection by a generic lookup.
            var slice = dispatcher.getRenderSectionSlice(mesh, ChunkSectionLayer.SOLID);
            var format = ChunkSectionLayer.SOLID.pipeline(false).getVertexFormatBinding(0);
            var sanity = SectionGeometrySanity.inspect(selected, node, mesh, slice, format);
            if (sanity != SectionGeometrySanity.Failure.OK) {
                TerrainDrawCapture.diagnostics(level, false);
                return empty(nextAnchor, "CAPTURE_INVALIDATED_BEFORE_COPY: " + sanity);
            }
            if (slice.vertexBuffer() != receipt.buffer() || slice.vertexBufferOffset() != receipt.offset())
                return empty(nextAnchor, "ALLOCATION_CHANGED_AFTER_TERRAIN_DRAW_CAPTURE");
            var source = receipt.buffer();
            var draw = mesh.getSectionDraw(ChunkSectionLayer.SOLID);
            Resident next = resident;
            boolean unchanged = resident != null && resident.section() == node
                    && sameSource(resident.owner(), resident.mesh(), resident.source(), resident.offset(),
                                  selected, mesh, source, slice.vertexBufferOffset());
            if (!unchanged) {
                var layout = receipt.layout();
                if (layout.indexCount() != draw.indexCount()) return empty(nextAnchor, "DRAW_COUNT_CHANGED_AFTER_CAPTURE");
                TerrainDrawCapture.diagnostics(level, true);
                LOG.info("[RT] Selected section sanity PASS: selected section found; SOLID geometry found; vertex range > 0; triangle count > 0; GPU buffer handle valid");
                if (TerrainDrawCapture.diagnosticsEnabled())
                    LOG.info("[RT][chunks-diag] {}", TerrainDrawCapture.describe(selected,mesh,slice,node,sanity));
                LOG.info("[RT] Chunk section discovered: x={}, y={}, z={}; world origin=({}, {}, {})",
                        SectionPos.x(node), SectionPos.y(node), SectionPos.z(node), SectionPos.x(node) * 16, SectionPos.y(node) * 16, SectionPos.z(node) * 16);
                LOG.info("[RT] Vertex buffer: UberGpuBuffer VkBuffer=0x{}, offset={}, bytes={}, vertices={}, stride={}, Position offset={}",
                        Long.toHexString(source.vkBuffer()), slice.vertexBufferOffset(), layout.vertexBytes(), layout.vertexCount(), layout.stride(), layout.positionOffset());
                LOG.info("[RT] Index buffer: vanilla implicit/shared QUADS -> uint32 triangle list; indices={}; Triangle count: {}",
                        layout.indexCount(), layout.triangles());
                LOG.info("[RT] Preserved vertex attributes: {}", layout.attributes());
                LOG.info("[RT] Building chunk BLAS...");
                var blas = batch.own(acceleration.buildSectionBlas(batch, source, slice.vertexBufferOffset(), layout));
                next = new Resident(node, selected, mesh, source, slice.vertexBufferOffset(), layout, blas);
                LOG.info("[RT] Chunk BLAS created: 0x{}; build recorded", Long.toHexString(blas.handle()));
            }
            waitReason = null;
            var nextTlas = tlas;
            if (next != resident || !nextAnchor.equals(anchor)) {
                LOG.info("[RT] Adding chunk instance to TLAS: section=({}, {}, {}), translation=({}, {}, {}), anchor=({}, {}, {})",
                        SectionPos.x(node), SectionPos.y(node), SectionPos.z(node), nextAnchor.sectionX(node), nextAnchor.sectionY(node), nextAnchor.sectionZ(node),
                        nextAnchor.x(), nextAnchor.y(), nextAnchor.z());
                var instances = List.of(new AccelerationStructureManager.Instance(next.blas(), nextAnchor.sectionX(node), nextAnchor.sectionY(node), nextAnchor.sectionZ(node), 0));
                if (tlas == null) nextTlas = batch.own(acceleration.buildTlas(batch, instances));
                else acceleration.updateTlas(batch, tlas, instances);
                LOG.info("[RT] TLAS updated: 1 instances; {}; camera=({}, {}, {})", tlas == null ? "BUILD" : "UPDATE", x, y, z);
            }
            return new Prepared(nextTlas, next, nextAnchor);
        } finally { dispatcher.unlock(); }
    }

    static boolean sameSource(Object oldOwner, Object oldMesh, Object oldBuffer, long oldOffset,
                              Object owner, Object mesh, Object buffer, long offset) {
        return oldOwner != null && oldOwner == owner && oldMesh == mesh && oldBuffer == buffer && oldOffset == offset;
    }
    private Prepared empty(ChunkCoordinates.Anchor nextAnchor, String reason) {
        if (!reason.equals(waitReason)) { waitReason = reason; LOG.info("[RT] Chunks: {}", reason); }
        return new Prepared(null, null, nextAnchor);
    }
    private static void counters(Resident next) {
        LOG.info("[RT] Scene: chunks | BLAS count: {} | TLAS instances: {} | RT triangles: {}",
                next == null ? 0 : 1, next == null ? 0 : 1, next == null ? 0 : next.layout().triangles());
    }
    @Override public void close() {
        if (tlas != null) { tlas.close(); tlas = null; }
        if (resident != null) { resident.blas().close(); resident = null; }
    }
}
