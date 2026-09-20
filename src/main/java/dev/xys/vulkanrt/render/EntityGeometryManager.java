package dev.xys.vulkanrt.render;

import dev.xys.vulkanrt.geometry.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.vulkan.*;
import org.joml.Matrix4f;
import java.util.*;
import java.nio.*;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static dev.xys.vulkanrt.render.VulkanRayTracingContext.memoryBarrier;

/** Separate geometry residency; the only TLAS/RT pipeline remains WorldGeometryManager's scene. */
public final class EntityGeometryManager implements AutoCloseable {
    public static final int TEXTURES=12, HUD_HEADER=64, HUD_ROW=160;
    public record Material(int texture,float cutoff,int overlay,int record,EntityCapture.Colors colors,int overlayTexture,boolean mirror,int id,boolean falling) {}
    public record Frame(List<AccelerationStructureManager.Instance> instances,List<ChunkMaterialTable.Entry> materials,
                        List<TerrainAtlasCapture.Atlas> textures,GpuBuffer hud,int blasCount) {}
    private final VulkanRayTracingContext context;
    private final AccelerationStructureManager acceleration;
    private EntityVertexNormalizer normalizer;
    private final Map<EntityCapture.Key,AccelerationStructureManager.Structure> cache=new HashMap<>();
    private final Map<EntityCapture.Piece,AccelerationStructureManager.Structure> ready=new IdentityHashMap<>();
    private List<AccelerationStructureManager.Instance> previousInstances=List.of();
    private GpuBuffer emptyHud;
    private int built,copies,retired,reused;
    private final Set<EntityCapture.Key> builtKeys=new HashSet<>();
    private long lastReport,lastCopyReport;
    private final java.util.concurrent.atomic.AtomicInteger destroyed=new java.util.concurrent.atomic.AtomicInteger();
    public EntityGeometryManager(VulkanRayTracingContext context) {
        this.context=context;acceleration=new AccelerationStructureManager(context);
    }
    public void beginFrame() { ready.clear();builtKeys.clear();built=copies=retired=reused=0; }
    /** Read CPU pipeline state, not texture pixels, uniform bytes or GPU memory. Unknown effects fail closed. */
    public static boolean supported(RenderPipeline p) {
        return p==RenderPipelines.ENTITY_SOLID || p==RenderPipelines.ENTITY_CUTOUT_CULL || p==RenderPipelines.ENTITY_CUTOUT
            || p==RenderPipelines.ENTITY_TRANSLUCENT || p==RenderPipelines.ENTITY_TRANSLUCENT_CULL
            || p==RenderPipelines.ITEM_CUTOUT || p==RenderPipelines.ITEM_TRANSLUCENT
            || p==RenderPipelines.SOLID_BLOCK || p==RenderPipelines.CUTOUT_BLOCK || p==RenderPipelines.TRANSLUCENT_BLOCK;
    }
    public static float cutoff(RenderPipeline p) { return Float.parseFloat(p.getShaderDefines().values().getOrDefault("ALPHA_CUTOUT","0")); }
    public static int flags(RenderPipeline p) {
        boolean blend=p.getColorTargetStates().getFirst().blendFunction().filter(BlendFunction.TRANSLUCENT::equals).isPresent();
        return ChunkMaterialTable.ENTITY | (p.isCull()?ChunkMaterialTable.CULL_BACK:0)
            | (blend?ChunkMaterialTable.TRANSLUCENT:cutoff(p)>0?ChunkMaterialTable.CUTOUT:0);
    }
    public static int abgr(int argb) { return (argb&0xff00ff00)|((argb&255)<<16)|((argb>>>16)&255); }
    public void upload(List<EntityCapture.Upload> uploads) {
        var added=new HashMap<EntityCapture.Key,AccelerationStructureManager.Structure>();
        var next=new IdentityHashMap<EntityCapture.Piece,AccelerationStructureManager.Structure>();
        CommandBatch batch=null;
        int copySamples=0;boolean diagnoseCopies=Boolean.getBoolean("nativevulkanrt.entityDiagnostics") && System.nanoTime()-lastCopyReport>2_000_000_000L;
        try {
            for(var upload:uploads) {
                var p=upload.piece();
                if(!supported(p.material().pipeline())) { EntityCapture.reject(p.owner(),"UNSUPPORTED_PIPELINE:"+p.material().pipeline().getLocation()+":"+p.material().pipeline().getShaderDefines());continue; }
                var geometry=cache.get(p.key());if(geometry==null) geometry=added.get(p.key());
                if(geometry==null) {
                    if(normalizer==null) normalizer=new EntityVertexNormalizer(context);
                    if(batch==null) batch=new CommandBatch(context);
                    geometry=batch.own(acceleration.buildCapturedBlas(batch,upload.source(),upload.offset(),p.key().layout(),true,normalizer,new Matrix4f(p.pose()).invert()));
                    geometry.onDestroyed(destroyed::incrementAndGet);
                    added.put(p.key(),geometry);built++;copies++;
                    if(diagnoseCopies && copySamples++<4) {
                        lastCopyReport=System.nanoTime();
                        org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info("[RT][entity-geometry] id={} type={} source=vanilla-staging offset={} bytes={} stride={} vertices={} triangles={} pipeline={} cutoff={} cull={} GPU-unpose=YES; CPU receipt, not hit proof",
                            p.owner().id(),p.owner().type(),upload.offset(),p.key().layout().vertexBytes(),p.key().layout().stride(),p.key().layout().vertexCount(),p.key().layout().triangles(),p.material().pipeline().getLocation(),cutoff(p.material().pipeline()),p.material().pipeline().isCull());
                    }
                } else reused++;
                next.put(p,geometry);
            }
            // Do not let vanilla reuse/write a staging allocation ahead of our transfer read.
            if(batch!=null) {
                memoryBarrier(batch.commands,VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,VK_ACCESS_2_MEMORY_WRITE_BIT_KHR);
                batch.commit();
            }
            cache.putAll(added);builtKeys.addAll(added.keySet());ready.putAll(next);
        } finally { if(batch!=null) batch.close(); }
    }
    private static TerrainAtlasCapture.Atlas texture(PreparedRenderType material,String name) {
        for(var t:material.textures()) if(t.name().equals(name) && t.textureView() instanceof VulkanGpuTextureView v && t.sampler() instanceof VulkanGpuSampler s) {
            var a=new TerrainAtlasCapture.Atlas(v,s);if(a.live()) return a;
        }
        return null;
    }
    private static int slot(List<TerrainAtlasCapture.Atlas> list,TerrainAtlasCapture.Atlas value) {
        if(value==null) return -1;
        int i=list.indexOf(value);if(i>=0) return i;
        if(list.size()>=TEXTURES) return -1;
        list.add(value);return list.size()-1;
    }
    public Frame frame(CommandBatch batch,ChunkCoordinates.Anchor anchor,double camX,double camY,double camZ) {
        var pieces=new ArrayList<>(EntityCapture.executed);
        pieces.sort(Comparator.comparingInt((EntityCapture.Piece p)->p.owner().id()).thenComparingInt(p->p.key().hashCode()).thenComparingInt(EntityCapture.Piece::first));
        var instances=new ArrayList<AccelerationStructureManager.Instance>();var materials=new ArrayList<ChunkMaterialTable.Entry>();
        var textures=new ArrayList<TerrainAtlasCapture.Atlas>();var active=new HashSet<EntityCapture.Key>();var owners=new HashSet<Integer>();
        var accepted=new ArrayList<EntityCapture.Piece>();long triangles=0;int falling=0;
        for(var p:pieces) {
            var geometry=ready.get(p);if(geometry==null) continue;
            var sampler=texture(p.material(),"Sampler0");int t=slot(textures,sampler);
            if(t<0) { EntityCapture.reject(p.owner(),sampler==null?"SAMPLER0_ABSENT_OR_CLOSED":"TEXTURE_SLOT_LIMIT_12");continue; }
            var overlay=texture(p.material(),"Sampler1");int ot=slot(textures,overlay);
            if(overlay!=null && ot<0) { EntityCapture.reject(p.owner(),"OVERLAY_TEXTURE_SLOT_LIMIT_12");continue; }
            int flags=flags(p.material().pipeline());
            var transform=new Matrix4f().translation(anchor.cameraX(camX),anchor.cameraY(camY),anchor.cameraZ(camZ)).mul(p.pose());
            int record=accepted.size();
            var material=new Material(t,cutoff(p.material().pipeline()),p.overlay(),record,p.colors(),ot,p.pose().determinant()<0,p.owner().id(),p.owner().falling());
            instances.add(new AccelerationStructureManager.Instance(geometry,transform,(flags&ChunkMaterialTable.TRANSLUCENT)!=0?2:1));
            materials.add(new ChunkMaterialTable.Entry(0,geometry.vertexAddress(),p.key().layout(),null,flags,0,0,material));
            accepted.add(p);active.add(p.key());triangles+=p.key().layout().triangles();
            if(owners.add(p.owner().id()) && p.owner().falling()) falling++;
        }
        // No subsequent trace references removed geometry; destruction remains deferred through vanilla fences.
        var it=cache.entrySet().iterator();while(it.hasNext()) { var e=it.next();if(!active.contains(e.getKey())) { context.retire(e.getValue());it.remove();retired++; } }
        var newOwners=new HashSet<Integer>();for(var p:accepted) if(builtKeys.contains(p.key())) newOwners.add(p.owner().id());
        int reusedEntities=owners.size()-newOwners.size();
        int transforms=0;for(int i=0;i<instances.size();i++) if(i>=previousInstances.size() || !instances.get(i).equals(previousInstances.get(i))) transforms++;
        previousInstances=List.copyOf(instances);
        boolean diagnostic=Boolean.getBoolean("nativevulkanrt.entityDiagnostics") || Boolean.getBoolean("nativevulkanrt.materialDiagnostics");
        GpuBuffer hud;
        if(diagnostic) {
            var bytes=MemoryUtil.memCalloc(HUD_HEADER+Math.max(1,accepted.size())*HUD_ROW);
            try {
                bytes.putInt(0,EntityCapture.discovered.size()).putInt(4,owners.size()).putInt(8,cache.size()).putInt(12,instances.size());
                bytes.putInt(16,Math.toIntExact(triangles)).putInt(20,reused).putInt(24,transforms).putInt(28,falling);
                bytes.putInt(48,reusedEntities).putInt(52,destroyed.get());
                bytes.putInt(32,built).putInt(36,copies).putInt(40,retired).putInt(44,textures.size());
                for(int i=0;i<accepted.size();i++) {
                    var p=accepted.get(i);var o=p.owner();int off=HUD_HEADER+i*HUD_ROW;
                    bytes.putFloat(off,(float)o.x()).putFloat(off+4,(float)o.y()).putFloat(off+8,(float)o.z());
                    bytes.putInt(off+16,o.id()).putInt(off+20,o.falling()?1:0).putInt(off+24,materials.get(i).entity().texture());
                    ascii(bytes,off+32,o.type(),32);
                    ascii(bytes,off+64,String.valueOf(textures.get(materials.get(i).entity().texture()).view().texture().getLabel()),96);
                }
                hud=batch.temporary(GpuBuffer.upload(context,batch,bytes,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT));
            } finally { MemoryUtil.memFree(bytes); }
        } else {
            if(emptyHud==null) { var bytes=MemoryUtil.memCalloc(HUD_HEADER+HUD_ROW);try { emptyHud=GpuBuffer.upload(context,batch,bytes,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT); } finally { MemoryUtil.memFree(bytes); } }
            hud=emptyHud;
        }
        if(System.nanoTime()-lastReport>2_000_000_000L) {
            lastReport=System.nanoTime();var log=org.slf4j.LoggerFactory.getLogger("native_vulkan_rt");
            log.info("[RT][entities] discovered(vanilla submitted)={} captured={} ignored={} reasons={} geometry={} GPUcopies={} BLASbuilt={} BLASreusedReceipts={} BLASdestroyed(total)={} BLASretired(deferred)={} TLASinstances={} triangles={} transformUpdates={} FallingBlockEntity={} reusedEntities={} textures={}; CPU enqueue counters, selected hit only in GPU HUD",
                EntityCapture.discovered.size(),owners.size(),EntityCapture.discovered.size()-owners.size(),EntityCapture.rejected,cache.size(),copies,built,reused,destroyed.get(),retired,instances.size(),triangles,transforms,falling,reusedEntities,textures.stream().map(t->t.view().texture().getLabel()).toList());
            if(diagnostic) for(int slotIndex=0;slotIndex<textures.size();slotIndex++) {
                var t=textures.get(slotIndex);log.info("[RT][entity-texture] slot={} label={} view=0x{} viewLod0={}x{} baseMip={} viewMips={} originalSampler=0x{} borrowed=true copy=NO",
                    slotIndex,t.view().texture().getLabel(),Long.toHexString(t.view().vkImageView()),t.view().getWidth(0),t.view().getHeight(0),t.view().baseMipLevel(),t.view().mipLevels(),Long.toHexString(t.sampler().vkSampler()));
            }
            for(var o:EntityCapture.discovered.values()) if(o.falling()) log.info("[RT][falling] id={} block={} pos=({},{},{}) captured={} state={}",o.id(),o.detail(),o.x(),o.y(),o.z(),owners.contains(o.id()),owners.contains(o.id())?"SHARED_TLAS_ENQUEUED":"NOT_CAPTURED (see reasons)");
            for(var owner:EntityCapture.discovered.values()) if(!owners.contains(owner.id())) log.info("[RT][entities] ignored id={} type={} reason={}",owner.id(),owner.type(),EntityCapture.ignoredReason(owner));
        }
        return new Frame(List.copyOf(instances),List.copyOf(materials),List.copyOf(textures),hud,cache.size());
    }
    private static void ascii(ByteBuffer out,int offset,String s,int max) { byte[] text=s.toUpperCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII);for(int i=0;i<Math.min(max,text.length);i++) out.put(offset+i,text[i]); }
    @Override public void close() { cache.values().forEach(AccelerationStructureManager.Structure::close);cache.clear();if(emptyHud!=null) emptyHud.close();if(normalizer!=null) normalizer.close(); }
}
