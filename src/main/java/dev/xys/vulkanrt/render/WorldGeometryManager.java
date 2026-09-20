package dev.xys.vulkanrt.render;

import dev.xys.vulkanrt.geometry.GeometryInbox;
import net.minecraft.core.SectionPos;
import java.util.*;

/** Incremental opaque section BLAS; TLAS rebuilt only on accepted residency/mesh changes.
 * RUNTIME VERIFIED: NO. No MDI buffer readback, duplicate tessellation, or per-frame global BLAS rebuild. */
public final class WorldGeometryManager implements AutoCloseable {
    private final VulkanRayTracingContext context;
    private final AccelerationStructureManager acceleration;
    public final double anchorX, anchorY, anchorZ;
    private Map<Long, Resident> resident = Map.of();
    private AccelerationStructureManager.Structure tlas;
    private long acceptedRevision = -1;
    private int nextId;
    private record Resident(GeometryInbox.Capture capture, AccelerationStructureManager.Structure blas, int id) {}

    public WorldGeometryManager(VulkanRayTracingContext context, double x, double y, double z) {
        this.context = context; acceleration = new AccelerationStructureManager(context);
        anchorX = Math.floor(x / 256) * 256; anchorY = Math.floor(y / 256) * 256; anchorZ = Math.floor(z / 256) * 256;
    }

    public final class Prepared {
        public final AccelerationStructureManager.Structure tlas;
        private final Map<Long, Resident> next;
        private final long revision;
        private boolean committed;
        private Prepared(AccelerationStructureManager.Structure tlas, Map<Long, Resident> next, long revision) {
            this.tlas = tlas; this.next = next; this.revision = revision;
        }
        public void commit() {
            if (committed) throw new IllegalStateException("Scene committed twice"); committed = true;
            if (WorldGeometryManager.this.tlas != tlas && WorldGeometryManager.this.tlas != null) context.retire(WorldGeometryManager.this.tlas);
            resident.forEach((key, old) -> { if (next.get(key) != old) context.retire(old.blas()); });
            resident = next; WorldGeometryManager.this.tlas = tlas; acceptedRevision = revision;
        }
    }

    public Prepared prepare(CommandBatch batch) {
        if (GeometryInbox.revision() == acceptedRevision) return new Prepared(tlas, resident, acceptedRevision);
        var snapshot = GeometryInbox.snapshot();
        Map<Long, Resident> next = new TreeMap<>();
        int builds = 0; boolean pending = false;
        for (var entry : new TreeMap<>(snapshot.sections()).entrySet()) {
            long section = entry.getKey(); var capture = entry.getValue().capture();
            Resident current = resident.get(section);
            if (current != null && current.capture() == capture) next.put(section, current);
            else if (builds++ < RtOptions.BUILDS_PER_FRAME) {
                var mesh = capture.mesh();
                var blas = batch.own(acceleration.buildBlas(batch, mesh.positions(), mesh.indices()));
                if (nextId > 0xffffff) throw new IllegalStateException("Instance ID space exhausted; restart RT scene");
                next.put(section, new Resident(capture, blas, current == null ? nextId++ : current.id()));
            } else {
                pending = true;
                // Do not display an outdated mesh for an edited section. Admit it after its new BLAS is ready.
            }
        }
        boolean changed = !next.equals(resident);
        AccelerationStructureManager.Structure nextTlas = tlas;
        if (changed) {
            if (next.isEmpty()) nextTlas = null;
            else {
                List<AccelerationStructureManager.Instance> instances = new ArrayList<>(next.size());
                next.forEach((section, r) -> instances.add(new AccelerationStructureManager.Instance(r.blas(),
                        (float)(SectionPos.sectionToBlockCoord(SectionPos.x(section)) - anchorX),
                        (float)(SectionPos.sectionToBlockCoord(SectionPos.y(section)) - anchorY),
                        (float)(SectionPos.sectionToBlockCoord(SectionPos.z(section)) - anchorZ), r.id())));
                if (tlas != null && tlas.count == instances.size()) {
                    // Fixed instance count: refit in place, GPU-ordered after previous frame's trace.
                    // New input storage avoids any CPU write race with in-flight instance reads.
                    acceleration.updateTlas(batch, tlas, instances);
                } else {
                    nextTlas = batch.own(acceleration.buildTlas(batch, instances));
                }
            }
        }
        return new Prepared(nextTlas, Map.copyOf(next), pending ? -1 : snapshot.revision());
    }

    @Override public void close() {
        // Caller retires the whole manager after its last enqueued use.
        if (tlas != null) { tlas.close(); tlas = null; }
        resident.values().forEach(r -> r.blas().close()); resident = Map.of();
    }
}
