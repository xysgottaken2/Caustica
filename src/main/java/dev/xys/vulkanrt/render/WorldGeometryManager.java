package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.xys.vulkanrt.geometry.ChunkCoordinates;
import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/** One accepted SOLID section, from the same UberGpuBuffer slice used by terrain MDI.
 * No compiler interception, CPU vertex readback, second mesher or all-world BLAS loop.
 * Check the accepted mesh/owner/allocation each frame; copy/build ONLY when that identity changes.
 * Chunk runtime unverified. The established triangle path is independent of this manager. */
public final class WorldGeometryManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("native_vulkan_rt");
    private final VulkanRayTracingContext context;
    private final AccelerationStructureManager acceleration;
    private final Long pinnedSection;
    private final BlockPos.MutableBlockPos lookup = new BlockPos.MutableBlockPos();
    private Resident resident;
    private AccelerationStructureManager.Structure tlas;
    private ChunkCoordinates.Anchor anchor;
    private long cameraSection = Long.MIN_VALUE;
    private String waitReason;
    private record Resident(long section, Object owner, SectionMesh mesh, VulkanGpuBuffer source, long offset,
                            SectionGeometryLayout layout, AccelerationStructureManager.Structure blas) {}

    public WorldGeometryManager(VulkanRayTracingContext context, double x, double y, double z) {
        this.context = context;
        acceleration = new AccelerationStructureManager(context);
        anchor = ChunkCoordinates.Anchor.near(x, y, z);
        pinnedSection = ChunkCoordinates.parseSection(System.getProperty("nativevulkanrt.section"));
        LOG.info("[RT] Scene: chunks; milestone limit=1 SOLID section; selection={}",
                pinnedSection == null ? "nearest accepted section in camera's 3x3x3 neighborhood, sticky until camera changes section or mesh disappears"
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
            if (changed) counters(next);
        }
    }

    public Prepared prepare(CommandBatch batch, LevelRenderer level, double x, double y, double z) {
        var nextAnchor = ChunkCoordinates.Anchor.near(x, y, z);
        var dispatcher = level.sectionRenderDispatcher();
        var view = level.viewArea();
        if (dispatcher == null || view == null) return empty(nextAnchor, "waiting for vanilla section dispatcher/view area");
        // This is the same lock used by LevelRenderer.extractSectionDrawGroups and Uber allocations.
        // Vanilla GPU uploads happen on this render thread, so none can interleave between the
        // recorded copy and this frame's enqueue. Worker release cannot invalidate our owned copy;
        // vanilla defers heap destruction via its encoder, and future uploads follow this batch.
        dispatcher.lock();
        try {
            long currentCameraSection = SectionPos.asLong((int)Math.floor(x / 16), (int)Math.floor(y / 16), (int)Math.floor(z / 16));
            SectionRenderDispatcher.RenderSection selected = null;
            if (pinnedSection != null) selected = at(view, pinnedSection);
            else {
                if (resident != null && cameraSection == currentCameraSection) {
                    var previous = at(view, resident.section());
                    if (usable(previous, dispatcher)) selected = previous;
                }
                if (selected == null) selected = nearest(view, dispatcher, currentCameraSection, x, y, z);
            }
            cameraSection = currentCameraSection;
            if (!usable(selected, dispatcher)) return empty(nextAnchor,
                    "selected section not loaded/uploaded or has no SOLID geometry; waiting for vanilla mesh (pin with -Dnativevulkanrt.section=x,y,z)");
            long node = selected.getSectionNode();
            var mesh = selected.getSectionMesh();
            var draw = mesh.getSectionDraw(ChunkSectionLayer.SOLID);
            var slice = dispatcher.getRenderSectionSlice(mesh, ChunkSectionLayer.SOLID);
            if (draw == null || slice == null) return empty(nextAnchor, "section changed during selection; retrying next frame");
            if (draw.hasCustomIndexBuffer())
                throw new IllegalStateException("SOLID section has custom indices; milestone supports vanilla shared QUADS only");
            if (!(slice.vertexBuffer() instanceof VulkanGpuBuffer source))
                throw new IllegalStateException("Section vertex heap is not a VulkanGpuBuffer");
            Resident next = resident;
            boolean unchanged = resident != null && resident.section() == node
                    && sameSource(resident.owner(), resident.mesh(), resident.source(), resident.offset(),
                                  selected, mesh, source, slice.vertexBufferOffset());
            if (!unchanged) {
                var layout = SectionGeometryLayout.solidQuads(ChunkSectionLayer.SOLID.vertexFormat(), draw.indexCount());
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
    private SectionRenderDispatcher.RenderSection at(ViewArea view, long node) {
        lookup.set(SectionPos.x(node) * 16, SectionPos.y(node) * 16, SectionPos.z(node) * 16);
        var section = view.getRenderSectionAt(lookup);
        return section != null && section.getSectionNode() == node ? section : null; // rotating storage may recycle owners
    }
    private boolean usable(SectionRenderDispatcher.RenderSection section, SectionRenderDispatcher dispatcher) {
        if (section == null) return false;
        var mesh = section.getSectionMesh();
        var draw = mesh.getSectionDraw(ChunkSectionLayer.SOLID);
        return draw != null && draw.indexCount() > 0 && dispatcher.getRenderSectionSlice(mesh, ChunkSectionLayer.SOLID) != null;
    }
    private SectionRenderDispatcher.RenderSection nearest(ViewArea view, SectionRenderDispatcher dispatcher, long camera, double x, double y, double z) {
        SectionRenderDispatcher.RenderSection best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            long node = SectionPos.asLong(SectionPos.x(camera) + dx, SectionPos.y(camera) + dy, SectionPos.z(camera) + dz);
            var candidate = at(view, node);
            if (!usable(candidate, dispatcher)) continue;
            double a = SectionPos.x(node) * 16.0 + 8 - x, b = SectionPos.y(node) * 16.0 + 8 - y, c = SectionPos.z(node) * 16.0 + 8 - z;
            double distance = a * a + b * b + c * c;
            if (distance < bestDistance) { best = candidate; bestDistance = distance; }
        }
        return best;
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
