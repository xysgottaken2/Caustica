package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.xys.vulkanrt.geometry.BlockTintDiagnostics;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** One cached BLAS per actual SOLID section draw; a single TLAS contains the entire valid set.
 * Capture, lifetime validation and enqueue all precede vanilla's later heap uploads/reuse.
 * No compiler interception, CPU vertex readback or second terrain mesher.
 * Geometry/cache user-verified with 444 sections in 0.5.0; TEXEL sharpness user-verified in 0.5.1; SOLID/materials/coplanar overlay user-verified in 0.6.0; CUTOUT/alpha test user-verified in 0.7.0; TRANSLUCENT new in 0.8.0. */
public final class WorldGeometryManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("native_vulkan_rt");
    private final VulkanRayTracingContext context;
    private final AccelerationStructureManager acceleration;
    private Map<Long, Resident> residents = Map.of();
    private Map<Long, Resident> cutoutResidents = Map.of();
    private int cutoutBuildsSinceReport, cutoutRetiresSinceReport;
    private Map<Long,Resident> translucentResidents=Map.of();
    private int translucentBuildsSinceReport,translucentRetiresSinceReport;
    private AccelerationStructureManager.Structure tlas;
    private ChunkCoordinates.Anchor anchor;
    private ChunkMaterialTable materials;
    private List<AccelerationStructureManager.Instance> lastSceneInstances=List.of();
    private List<ChunkMaterialTable.Entry> lastSceneMaterials=List.of();
    private int entityBlasCount;
    private Map<Long,Resident> frameSolid=Map.of(),frameCutout=Map.of(),frameTranslucent=Map.of();
    public void beginFrame() { frameSolid=residents;frameCutout=cutoutResidents;frameTranslucent=translucentResidents; }
    private static int reused(Map<Long,Resident> before,Map<Long,Resident> after) {
        return (int)after.entrySet().stream().filter(e->before.get(e.getKey())==e.getValue()).count();
    }
    private long entityTriangles;
    private CoplanarOverlayMapper overlayMapper;
    private Map<Long,CoplanarOverlayMapper.Overlay> overlays=Map.of();
    private int overlayBuildsSinceReport, overlayRetiresSinceReport;
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
                pin == null ? "ALL eligible SOLID + CUTOUT + TRANSLUCENT terrain draws" : "diagnostic pin " + coordinates(pin));
    }

    public final class Prepared {
        public final AccelerationStructureManager.Structure tlas;
        public final ChunkCoordinates.Anchor anchor;
        public final ChunkMaterialTable materials;
        private final Map<Long, Resident> next;
        private final int built;
        private final Map<Long,CoplanarOverlayMapper.Overlay> nextOverlays;
        private final int overlayBuilt;
        private final Map<Long,Resident> nextCutouts;
        private final int cutoutBuilt;
        private final Map<Long,Resident> nextTranslucents;
        private final int translucentBuilt;
        public EntityGeometryManager.Frame entities;
        public ParticleGeometryManager.Frame particles = new ParticleGeometryManager.Frame(List.of(), List.of(), null, 0, 0, 0, 0);
        private List<AccelerationStructureManager.Instance> sceneInstances;
        private List<ChunkMaterialTable.Entry> sceneMaterials;
        private boolean committed;
        private Prepared(AccelerationStructureManager.Structure tlas, Map<Long, Resident> next,
                         ChunkCoordinates.Anchor anchor, int built, ChunkMaterialTable materials, Map<Long,CoplanarOverlayMapper.Overlay> nextOverlays, int overlayBuilt, Map<Long,Resident> nextCutouts, int cutoutBuilt, Map<Long,Resident> nextTranslucents,int translucentBuilt) {
            this.nextTranslucents=nextTranslucents; this.translucentBuilt=translucentBuilt;
            this.nextCutouts=nextCutouts; this.cutoutBuilt=cutoutBuilt;
            this.tlas = tlas; this.next = next; this.anchor = anchor; this.built = built; this.materials = materials; this.nextOverlays=nextOverlays; this.overlayBuilt=overlayBuilt;
        }
        /** Only after enqueue: old TLAS/BLAS remain alive through all earlier GPU consumers. */
        public void commit() {
            if (committed) throw new IllegalStateException("Scene committed twice");
            committed = true;
            boolean emptyTransition = (residents.isEmpty() && cutoutResidents.isEmpty() && translucentResidents.isEmpty()) != (next.isEmpty() && nextCutouts.isEmpty() && nextTranslucents.isEmpty());
            if (WorldGeometryManager.this.tlas != null && WorldGeometryManager.this.tlas != tlas)
                context.retire(WorldGeometryManager.this.tlas);
            if (WorldGeometryManager.this.materials != null && WorldGeometryManager.this.materials != materials)
                context.retire(WorldGeometryManager.this.materials);
            WorldGeometryManager.this.materials = materials;
            int retired = SectionResidency.retireReplaced(residents, next, section -> context.retire(section.blas()));
            overlayRetiresSinceReport+=SectionResidency.retireReplaced(overlays,nextOverlays,context::retire);
            overlays=nextOverlays; overlayBuildsSinceReport+=overlayBuilt;
            cutoutRetiresSinceReport+=SectionResidency.retireReplaced(cutoutResidents,nextCutouts,section -> context.retire(section.blas()));
            cutoutResidents=nextCutouts; cutoutBuildsSinceReport+=cutoutBuilt;
            translucentRetiresSinceReport+=SectionResidency.retireReplaced(translucentResidents,nextTranslucents,section -> context.retire(section.blas()));
            translucentResidents=nextTranslucents; translucentBuildsSinceReport+=translucentBuilt;
            residents = next; WorldGeometryManager.this.tlas = tlas; WorldGeometryManager.this.anchor = anchor;
            buildsSinceReport += built; retiresSinceReport += retired;
            if(sceneInstances!=null) {
                emptyTransition |= lastSceneInstances.isEmpty()!=sceneInstances.isEmpty();
                lastSceneInstances=sceneInstances;lastSceneMaterials=sceneMaterials;
                entityBlasCount=entities.blasCount();entityTriangles=entities.materials().stream().mapToLong(e->e.layout().triangles()).sum();
                report(emptyTransition,reused(frameSolid,next),reused(frameCutout,nextCutouts),reused(frameTranslucent,nextTranslucents));
            }
        }
    }

    /** No extra GPU command buffer, copies or AS work for an unchanged set, even if draw order changes. */
    public Prepared reuseUnchangedDraws(LevelRenderer level, double x, double y, double z) {
        // Empty terrain is also reusable: entity-only views need no empty terrain command batch.
        if (!TerrainDrawCapture.ready(level, level.sectionRenderDispatcher())) return null;
        var draws = TerrainDrawCapture.draws();
        var nextAnchor = ChunkCoordinates.Anchor.near(x,y,z);
        if (draws.size() != residents.size() || TerrainDrawCapture.cutoutDraws().size()!=cutoutResidents.size() || TerrainDrawCapture.translucentDraws().size()!=translucentResidents.size() || !nextAnchor.equals(anchor)) return null;
        for (var receipt : draws.values()) {
            if (!liveReceipt(receipt) || !matches(residents.get(receipt.section()), receipt)) return null;
            var cutout=TerrainDrawCapture.cutout(receipt.section());
            var cached=overlays.get(receipt.section());
            if(cutout==null ? cached!=null : !liveReceipt(cutout) || cached==null || !cached.same(cutout,residents.get(receipt.section()).blas().vertexAddress())) return null;
        }
        for(var receipt : TerrainDrawCapture.cutoutDraws().values())
            if(!liveReceipt(receipt) || !matches(cutoutResidents.get(receipt.section()),receipt)) return null;
        // Index-only resorting is a permutation, not new geometry. Owned snapshots stay valid.
        for(var receipt:TerrainDrawCapture.translucentDraws().values())
            if(!liveReceipt(receipt) || !matches(translucentResidents.get(receipt.section()),receipt)) return null;
        return new Prepared(tlas, residents, anchor, 0, materials, overlays, 0, cutoutResidents, 0,translucentResidents,0);
    }

    public Prepared prepare(CommandBatch batch, LevelRenderer level, double x, double y, double z) {
        var nextAnchor = ChunkCoordinates.Anchor.near(x,y,z);
        var dispatcher = level.sectionRenderDispatcher();
        if (dispatcher == null) return empty(nextAnchor, "DISPATCHER_MISSING");
        if (!TerrainDrawCapture.ready(level, dispatcher)) return empty(nextAnchor, TerrainDrawCapture.failure());
        if (TerrainDrawCapture.draws().isEmpty() && TerrainDrawCapture.cutoutDraws().isEmpty() && TerrainDrawCapture.translucentDraws().isEmpty()) {
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
            var validCutouts=new TreeMap<Long,TerrainDrawCapture.Draw>();
            for(var receipt : TerrainDrawCapture.cutoutDraws().values()) {
                String reason=validateCutout(dispatcher,receipt);
                if(reason==null) validCutouts.put(receipt.section(),receipt);
                else LOG.warn("[RT][cutout-diag] Invalidated before copy: section={} reason={}",coordinates(receipt.section()),reason);
            }
            var validTranslucents=new TreeMap<Long,TerrainDrawCapture.Draw>();
            for(var receipt:TerrainDrawCapture.translucentDraws().values()) {
                String reason=validateTranslucent(dispatcher,receipt);
                if(reason==null) validTranslucents.put(receipt.section(),receipt);
                else LOG.warn("[RT][translucent-diag] Invalidated before copy: section={} reason={}",coordinates(receipt.section()),reason);
            }
            if (valid.isEmpty() && validCutouts.isEmpty() && validTranslucents.isEmpty()) return empty(nextAnchor, "NO_VALID_TERRAIN_LAYER_RECEIPTS_BEFORE_COPY: " + firstInvalid);
            int totalInstances=Math.addExact(Math.addExact(valid.size(),validCutouts.size()),validTranslucents.size());
            // Check before allocating any new BLAS; never silently truncate the captured set.
            if (Long.compareUnsigned(totalInstances, context.capabilities().maxInstanceCount()) > 0)
                throw new IllegalArgumentException("Terrain instances=" + totalInstances + " exceeds device maxInstanceCount="
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
            var cutoutDiff=SectionResidency.diff(cutoutResidents,validCutouts,WorldGeometryManager::matches);
            var nextCutouts=new TreeMap<Long,Resident>();
            for(long node : cutoutDiff.reuse()) nextCutouts.put(node,cutoutResidents.get(node));
            int cutoutDetailed=0;
            for(long node : cutoutDiff.build()) {
                var receipt=validCutouts.get(node); var layout=receipt.layout();
                if(detail && cutoutDetailed++<4)
                    LOG.info("[RT][cutout-diag] group=OPAQUE pipelines={} / {} section={} owner={} mesh={} UberGpuBuffer=0x{} offset={} bytes={} quads={} triangles={} layout={} sanity=OK; enqueue non-opaque BLAS in shared TLAS",
                            ChunkSectionLayer.CUTOUT.pipeline(false).getLocation(),ChunkSectionLayer.CUTOUT.pipeline(true).getLocation(),
                            coordinates(node),SectionGeometrySanity.identity(receipt.owner()),SectionGeometrySanity.identity(receipt.mesh()),Long.toHexString(receipt.buffer().vkBuffer()),
                            receipt.offset(),layout.vertexBytes(),layout.vertexCount()/4,layout.triangles(),layout);
                var blas=batch.own(acceleration.buildSectionBlas(batch,receipt.buffer(),receipt.offset(),layout,true));
                nextCutouts.put(node,new Resident(node,receipt.owner(),receipt.mesh(),receipt.buffer(),receipt.offset(),layout,blas));
            }
            var translucentDiff=SectionResidency.diff(translucentResidents,validTranslucents,WorldGeometryManager::matches);
            var nextTranslucents=new TreeMap<Long,Resident>();
            for(long node:translucentDiff.reuse()) nextTranslucents.put(node,translucentResidents.get(node));
            int translucentDetailed=0;
            for(long node:translucentDiff.build()) {
                var receipt=validTranslucents.get(node); var layout=receipt.layout();
                if(detail && translucentDetailed++<4)
                    LOG.info("[RT][translucent-diag] group=TRANSLUCENT pipelines={} / {} (or OIT_TERRAIN) section={} mesh={} vertex=0x{} offset={} bytes={} index=0x{} offset={} indexBytes={} triangles={} layout={}; original indexed snapshot -> non-opaque BLAS/shared TLAS",
                            ChunkSectionLayer.TRANSLUCENT.pipeline(false).getLocation(),ChunkSectionLayer.TRANSLUCENT.pipeline(true).getLocation(),coordinates(node),SectionGeometrySanity.identity(receipt.mesh()),
                            Long.toHexString(receipt.buffer().vkBuffer()),receipt.offset(),layout.vertexBytes(),Long.toHexString(receipt.indexBuffer().vkBuffer()),receipt.indexOffset(),receipt.indexBytes(),layout.triangles(),layout);
                var blas=batch.own(acceleration.buildTranslucentBlas(batch,receipt));
                nextTranslucents.put(node,new Resident(node,receipt.owner(),receipt.mesh(),receipt.buffer(),receipt.offset(),layout,blas));
            }
            if (detail && (detailed > 0 || cutoutDetailed>0 || translucentDetailed>0)) lastDetail = System.nanoTime();
            // Keep the early terrain copies/builds exactly here. Scene assembly waits until the
            // entity feature uploads/draws complete, then updates the ONE combined TLAS once.
            var nextTlas=tlas;
            // Independent material cache. The validated coplanar path still shades SOLID deterministically even if its opaque hit wins a depth tie.
            var nextOverlays=new TreeMap<Long,CoplanarOverlayMapper.Overlay>();
            int overlayBuilt=0;
            for(var section : next.values()) {
                var receipt=TerrainDrawCapture.cutout(section.section());
                if(receipt==null || !liveReceipt(receipt) || receipt.mesh()!=section.mesh() || receipt.owner()!=section.owner()) continue;
                var slice=dispatcher.getRenderSectionSlice(receipt.mesh(),ChunkSectionLayer.CUTOUT);
                var sanity=SectionGeometrySanity.inspect(receipt.owner(),receipt.section(),receipt.mesh(),slice,
                        ChunkSectionLayer.CUTOUT.pipeline(false).getVertexFormatBinding(0),ChunkSectionLayer.CUTOUT);
                if(sanity!=SectionGeometrySanity.Failure.OK || slice.vertexBuffer()!=receipt.buffer() || slice.vertexBufferOffset()!=receipt.offset()) {
                    LOG.debug("[RT] CUTOUT material receipt invalidated before GPU copy: section={} sanity={}",coordinates(receipt.section()),sanity);
                    continue;
                }
                var cached=overlays.get(section.section());
                if(cached==null || !cached.same(receipt,section.blas().vertexAddress())) {
                    if(overlayMapper==null) overlayMapper=new CoplanarOverlayMapper(context);
                    cached=batch.own(overlayMapper.build(batch,receipt,section.blas().vertexAddress(),section.layout()));
                    overlayBuilt++;
                }
                nextOverlays.put(section.section(),cached);
            }
            var nextMaterials=materials;
            waitReason = null;
            return new Prepared(nextTlas, next, nextAnchor, diff.build().size(), nextMaterials,nextOverlays,overlayBuilt,nextCutouts,cutoutDiff.build().size(),nextTranslucents,translucentDiff.build().size());
        } finally { dispatcher.unlock(); }
    }

    /** Single scene finalization after the original entity feature draws, before vkCmdTraceRaysKHR. */
    public boolean hasTerrain() { return !residents.isEmpty() || !cutoutResidents.isEmpty() || !translucentResidents.isEmpty(); }

    public Prepared compose(CommandBatch batch,EntityGeometryManager.Frame entities, ParticleGeometryManager.Frame particles) {
        var instances=new ArrayList<AccelerationStructureManager.Instance>();var entries=new ArrayList<ChunkMaterialTable.Entry>();
        for(var section:residents.values()) {
            long node=section.section();instances.add(new AccelerationStructureManager.Instance(section.blas(),anchor.sectionX(node),anchor.sectionY(node),anchor.sectionZ(node),0));
            entries.add(new ChunkMaterialTable.Entry(node,section.blas().vertexAddress(),section.layout(),overlays.get(node)));
        }
        int cutoutFlags=ChunkMaterialTable.CUTOUT|(ChunkSectionLayer.CUTOUT.pipeline(false).isCull()?ChunkMaterialTable.CULL_BACK:0);
        for(var section:cutoutResidents.values()) {
            long node=section.section();instances.add(new AccelerationStructureManager.Instance(section.blas(),anchor.sectionX(node),anchor.sectionY(node),anchor.sectionZ(node),0));
            entries.add(new ChunkMaterialTable.Entry(node,section.blas().vertexAddress(),section.layout(),null,cutoutFlags));
        }
        int transFlags=ChunkMaterialTable.TRANSLUCENT|(ChunkSectionLayer.TRANSLUCENT.pipeline(false).isCull()?ChunkMaterialTable.CULL_BACK:0);
        for(var section:translucentResidents.values()) {
            long node=section.section();instances.add(new AccelerationStructureManager.Instance(section.blas(),anchor.sectionX(node),anchor.sectionY(node),anchor.sectionZ(node),0,2));
            int waterFlags = transFlags | (BlockTintDiagnostics.hasFluid(node) ? ChunkMaterialTable.WATER : 0);
            entries.add(new ChunkMaterialTable.Entry(node,section.blas().vertexAddress(),section.layout(),null,waterFlags,section.blas().indexAddress(),section.blas().indexBytes()));
        }
        instances.addAll(entities.instances());entries.addAll(entities.materials());
        instances.addAll(particles.instances());entries.addAll(particles.materials());
        var nextTlas=tlas;var nextMaterials=materials;
        if(instances.isEmpty()) { nextTlas=null;nextMaterials=null; }
        else {
            if(tlas==null || tlas.count!=instances.size()) nextTlas=batch.own(acceleration.buildTlas(batch,instances));
            else if(!instances.equals(lastSceneInstances)) acceleration.updateTlas(batch,tlas,instances);
            if(materials==null || !entries.equals(lastSceneMaterials)) nextMaterials=batch.own(new ChunkMaterialTable(context,batch,entries));
        }
        var result=new Prepared(nextTlas,residents,anchor,0,nextMaterials,overlays,0,cutoutResidents,0,translucentResidents,0);
        result.entities=entities;result.particles=particles;result.sceneInstances=List.copyOf(instances);result.sceneMaterials=List.copyOf(entries);
        return result;
    }

    private static String validateCutout(net.minecraft.client.renderer.chunk.SectionRenderDispatcher dispatcher,TerrainDrawCapture.Draw receipt) {
        if(!liveReceipt(receipt)) return "STALE_CUTOUT_RECEIPT_OR_MESH";
        var slice=dispatcher.getRenderSectionSlice(receipt.mesh(),ChunkSectionLayer.CUTOUT);
        var sanity=SectionGeometrySanity.inspect(receipt.owner(),receipt.section(),receipt.mesh(),slice,ChunkSectionLayer.CUTOUT.vertexFormat(),ChunkSectionLayer.CUTOUT);
        if(sanity!=SectionGeometrySanity.Failure.OK) return sanity.name();
        if(slice.vertexBuffer()!=receipt.buffer() || slice.vertexBufferOffset()!=receipt.offset()) return "CUTOUT_ALLOCATION_CHANGED_AFTER_CAPTURE";
        if(receipt.mesh().getSectionDraw(ChunkSectionLayer.CUTOUT).indexCount()!=receipt.layout().indexCount()) return "CUTOUT_DRAW_COUNT_CHANGED_AFTER_CAPTURE";
        return null;
    }
    private static String validateTranslucent(net.minecraft.client.renderer.chunk.SectionRenderDispatcher dispatcher,TerrainDrawCapture.Draw receipt) {
        if(!liveReceipt(receipt)) return "STALE_TRANSLUCENT_RECEIPT_OR_MESH";
        var layer=ChunkSectionLayer.TRANSLUCENT; var slice=dispatcher.getRenderSectionSlice(receipt.mesh(),layer);
        var sanity=SectionGeometrySanity.inspect(receipt.owner(),receipt.section(),receipt.mesh(),slice,layer.vertexFormat(),layer);
        if(sanity!=SectionGeometrySanity.Failure.OK) return sanity.name();
        if(slice.vertexBuffer()!=receipt.buffer() || slice.vertexBufferOffset()!=receipt.offset()
                || slice.indexBuffer()!=receipt.indexBuffer() || slice.indexBufferOffset()!=receipt.indexOffset()) return "TRANSLUCENT_ALLOCATION_CHANGED_BEFORE_COPY";
        if(receipt.mesh().getSectionDraw(layer).indexCount()!=receipt.layout().indexCount()) return "TRANSLUCENT_COUNT_CHANGED";
        return null;
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
        return new Prepared(tlas, Map.of(), nextAnchor, 0, materials, Map.of(), 0, Map.of(), 0, Map.of(), 0);
    }
    private void report(boolean force, int reusedThisFrame, int cutoutReusedThisFrame,int translucentReusedThisFrame) {
        if (!force && System.nanoTime()-lastReport < 2_000_000_000L) return;
        lastReport = System.nanoTime();
        long triangles = 0, vertexBytes = 0;
        for (var resident : residents.values()) { triangles += resident.layout().triangles(); vertexBytes += resident.layout().vertexBytes(); }
        long cutoutTriangles=0,cutoutBytes=0;
        for(var resident : cutoutResidents.values()) { cutoutTriangles+=resident.layout().triangles();cutoutBytes+=resident.layout().vertexBytes(); }
        LOG.info("[RT] SOLID sections={} triangles={} vertexBytes={}",residents.size(),triangles,vertexBytes);
        LOG.info("[RT] CUTOUT sections={} quads={} triangles={} vertexBytes={} capturedSections={}; group=OPAQUE; CUTOUT_MIPPED=not a separate layer in 26.3",
                cutoutResidents.size(),cutoutTriangles/2,cutoutTriangles,cutoutBytes,TerrainDrawCapture.cutoutDraws().size());
        long translucentTriangles=0,translucentBytes=0;
        for(var resident:translucentResidents.values()) { translucentTriangles+=resident.layout().triangles();translucentBytes+=resident.layout().vertexBytes(); }
        LOG.info("[RT] TRANSLUCENT sections={} triangles={} vertexBytes={} capturedSections={}; indexed snapshots; camera-only index sorting does not rebuild BLAS",
                translucentResidents.size(),translucentTriangles,translucentBytes,TerrainDrawCapture.translucentDraws().size());
        LOG.info("[RT] TRANSLUCENT cache: builtSinceReport={} retiredSinceReport={} (deferred) reusedThisFrame={}",translucentBuildsSinceReport,translucentRetiresSinceReport,translucentReusedThisFrame);
        translucentBuildsSinceReport=0;translucentRetiresSinceReport=0;
        LOG.info("[RT] Scene: chunks | SOLID sections={} | CUTOUT sections={} | TRANSLUCENT sections={} | TOTAL BLAS count={} | TOTAL TLAS instances={} | TOTAL RT triangles={}; entity subtotals logged separately; CPU committed/enqueued, actual composition in GPU HUD",
                residents.size(),cutoutResidents.size(),translucentResidents.size(),residents.size()+cutoutResidents.size()+translucentResidents.size()+entityBlasCount,tlas==null?0:tlas.count,triangles+cutoutTriangles+translucentTriangles+entityTriangles);
        LOG.info("[RT] CUTOUT cache: builtSinceReport={} retiredSinceReport={} (deferred) reusedThisFrame={}",cutoutBuildsSinceReport,cutoutRetiresSinceReport,cutoutReusedThisFrame);
        cutoutBuildsSinceReport=0;cutoutRetiresSinceReport=0;
        LOG.info("[RT] Chunk cache: builtSinceReport={} retiredSinceReport={} (deferred) reusedThisFrame={}; section sample={}{}",
                buildsSinceReport, retiresSinceReport, reusedThisFrame, residents.keySet().stream().limit(8).map(WorldGeometryManager::coordinates).collect(Collectors.joining(", ")),
                residents.size() > 8 ? ", ..." : "");
        LOG.info("[RT] Coplanar material cache: sections={} buildsSinceReport={} retiredSinceReport={} (deferred); no additional BLAS/TLAS; GPU lookup truncation is shown in the hit HUD",
                overlays.size(),overlayBuildsSinceReport,overlayRetiresSinceReport);
        overlayBuildsSinceReport=0; overlayRetiresSinceReport=0;
        buildsSinceReport = 0; retiresSinceReport = 0;
    }
    private static String coordinates(long node) { return "("+SectionPos.x(node)+","+SectionPos.y(node)+","+SectionPos.z(node)+")"; }
    @Override public void close() {
        if (tlas != null) { tlas.close(); tlas = null; }
        if (materials != null) { materials.close(); materials = null; }
        overlays.values().forEach(CoplanarOverlayMapper.Overlay::close); overlays=Map.of();
        if(overlayMapper!=null) { overlayMapper.close();overlayMapper=null; }
        residents.values().forEach(section -> section.blas().close());
        residents = Map.of();
        cutoutResidents.values().forEach(section -> section.blas().close()); cutoutResidents=Map.of();
        translucentResidents.values().forEach(section -> section.blas().close()); translucentResidents=Map.of();
    }
}
