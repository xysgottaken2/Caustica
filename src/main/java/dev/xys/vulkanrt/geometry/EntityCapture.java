package dev.xys.vulkanrt.geometry;

import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.xys.vulkanrt.mixin.*;
import dev.xys.vulkanrt.render.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Matrix4f;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Receipts for vanilla-emitted ranges, never a mesher. All methods run on the render thread. */
public final class EntityCapture {
    public record Owner(int id,String type,double x,double y,double z,boolean falling,String detail) {}
    public record Key(Object geometry,SectionGeometryLayout layout) {}
    public record Colors(int a,int b,int c,int d) { public static Colors uniform(int c) { return new Colors(c,c,c,c); } }
    public record Piece(Owner owner,Key key,StagedVertexBuffer.Draw draw,int first,Matrix4f pose,
                        PreparedRenderType material,Colors colors,int overlay) {}
    public record Upload(Piece piece,VulkanGpuBuffer source,long offset) {}
    /** FallingBlockEntity-only receipt chain. Counters are CPU command receipts; ray hit is GPU HUD-only. */
    public static final class FallingTrace {
        public final Owner owner;
        public int movingSubmits, movingGroups, movingGroupsWithoutOwner;
        public int quadCalls, emittedQuads, emittedVertices;
        public int uploadPieces, uploadVertices, uploadBytes, drawReceipts;
        public int blasBuilt, blasReused, tlasInstances, transformUpdates;
        public String pipeline="NONE", texture="NONE", asGeometry="NONE", discard="NONE";
        public String pose="NONE", localSpace="BLOCK_MODEL_ORIGIN=(0,0,0)";
        private FallingTrace(Owner owner) { this.owner=owner; }
        public int triangles() { return emittedQuads * 2; }
        public String summary() {
            return "id="+owner.id()+" block="+owner.detail()+" pos=("+owner.x()+","+owner.y()+","+owner.z()+")"
                +" submit="+movingSubmits+" group="+movingGroups+" groupWithoutOwner="+movingGroupsWithoutOwner
                +" quadCalls="+quadCalls+" emittedQuads="+emittedQuads+" vertices="+emittedVertices+" triangles="+triangles()
                +" uploads="+uploadPieces+"/"+uploadVertices+"v/"+uploadBytes+"B draws="+drawReceipts
                +" blasBuilt="+blasBuilt+" blasReused="+blasReused+" tlas="+tlasInstances+" transforms="+transformUpdates
                +" pipeline="+pipeline+" texture="+texture+" asGeometry="+asGeometry+" pose="+pose+" local="+localSpace+" discard="+discard;
        }
    }
    private record Cursor(StagedVertexBuffer.Draw draw,int base) {}
    private record Pending(BufferBuilder builder,Cursor cursor,int start,Object shape,Matrix4f pose,Colors colors,int overlay,PreparedRenderType material,Owner owner) {}
    private record DrawAddress(Object buffer,int base) {}
    private static final Map<EntityRenderState,Integer> ids=new WeakHashMap<>();
    private static final Map<Object,Owner> owners=new IdentityHashMap<>();
    private static final Map<BufferBuilder,Cursor> cursors=new IdentityHashMap<>();
    private static final Map<BufferBuilder,RenderType> types=new IdentityHashMap<>();
    private static final Map<RenderType,PreparedRenderType> prepared=new IdentityHashMap<>();
    private static final Map<DrawAddress,List<Piece>> uploaded=new HashMap<>();
    private static final List<Piece> pieces=new ArrayList<>();
    public static final Set<Piece> executed=Collections.newSetFromMap(new IdentityHashMap<>());
    public static final Map<Integer,Owner> discovered=new TreeMap<>();
    public static final Map<String,Integer> rejected=new TreeMap<>();
    private static final Map<Integer,FallingTrace> fallingTraces=new TreeMap<>();
    private static final Logger LOG=LoggerFactory.getLogger("native_vulkan_rt");
    private static long lastFallingReport;
    private static final Map<Integer,Set<String>> ownerRejections=new HashMap<>();
    private static Owner submitting,preparing;
    private static Pending pending;
    public static Collection<FallingTrace> fallingTraces() { return List.copyOf(fallingTraces.values()); }
    private static FallingTrace falling(Owner owner) {
        return owner!=null && owner.falling() ? fallingTraces.computeIfAbsent(owner.id(),id->new FallingTrace(owner)) : null;
    }
    public static void fallingTexture(Piece piece, String texture) {
        var trace=falling(piece.owner()); if(trace!=null) trace.texture=texture;
    }
    public static void fallingDiscard(Piece piece, String reason) {
        var trace=falling(piece.owner()); if(trace!=null && trace.discard.equals("NONE")) trace.discard=reason;
    }
    public static void fallingAsPolicy(Piece piece, boolean nonOpaque) {
        var trace=falling(piece.owner()); if(trace!=null) trace.asGeometry=nonOpaque?"NON_OPAQUE_ANY_HIT":"OPAQUE";
    }
    public static void fallingBlasBuilt(Piece piece, boolean reused) {
        var trace=falling(piece.owner()); if(trace==null) return;
        if(reused) trace.blasReused++; else trace.blasBuilt++;
    }
    public static void fallingTlas(Piece piece, boolean transformChanged, Matrix4f transform) {
        var trace=falling(piece.owner()); if(trace==null) return;
        trace.tlasInstances++; if(transformChanged) trace.transformUpdates++;
        trace.pose=matrixSummary(transform);
    }
    private static String matrixSummary(Matrix4f m) {
        return "["+f(m.m00())+","+f(m.m01())+","+f(m.m02())+","+f(m.m03())+";"
            +f(m.m10())+","+f(m.m11())+","+f(m.m12())+","+f(m.m13())+";"
            +f(m.m20())+","+f(m.m21())+","+f(m.m22())+","+f(m.m23())+"]";
    }
    private static String f(float value) { return String.format(Locale.ROOT,"%.4f",value); }
    public static void reportFalling(long now) {
        if(now-lastFallingReport<2_000_000_000L || fallingTraces.isEmpty()) return;
        lastFallingReport=now;
        for(var trace:fallingTraces.values()) {
            if(trace.discard.equals("NONE")) {
                if(trace.movingGroupsWithoutOwner>0 && trace.movingGroups==0) trace.discard="MOVING_SUBMIT_OWNER_NOT_BOUND";
                else if(trace.movingGroups==0) trace.discard="MOVING_BLOCK_GROUP_NOT_EXECUTED";
                else if(trace.emittedQuads==0) trace.discard="NO_BAKED_QUAD_EMISSION";
                else if(trace.uploadPieces==0) trace.discard="NO_STAGED_UPLOAD_RECEIPT";
                else if(trace.drawReceipts==0) trace.discard="NO_MATCHING_PREPARED_DRAW_RECEIPT";
                else if(trace.tlasInstances==0) trace.discard="NOT_ADDED_TO_TLAS";
                else if(trace.texture.equals("NONE")) trace.discard="NO_MATERIAL_TEXTURE";
                else trace.discard="NONE_CPU_PIPELINE_COMPLETE_GPU_HUD_REQUIRED";
            }
            LOG.info("[RT][falling-trace] {} | CPU stages: detected->moving submit->group->quad->upload->draw; ray intersection is GPU HUD FALLING HIT only",trace.summary());
        }
    }
    public static boolean enabled() { return RayTracingRenderer.chunkCaptureEnabled(); }
    public static boolean collecting() { return worldCapture() && preparing!=null; }
    private static boolean worldCapture() { return enabled() && RenderSystem.isRenderingLevel; }
    public static void beginFrame() { owners.clear();cursors.clear();types.clear();prepared.clear();uploaded.clear();pieces.clear();executed.clear();discovered.clear();rejected.clear();ownerRejections.clear();fallingTraces.clear();submitting=null;preparing=null;pending=null; }
    public static void state(EntityRenderState state,int id) { if(enabled()) ids.put(state,id); }
    public static void beginEntity(EntityRenderState s) {
        if(!worldCapture()) return;
        submitting=new Owner(ids.getOrDefault(s,System.identityHashCode(s)),String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(s.entityType)),s.x,s.y,s.z,s instanceof FallingBlockRenderState,s instanceof FallingBlockRenderState f?String.valueOf(f.movingBlockRenderState.blockState):"");
        discovered.put(submitting.id(),submitting); falling(submitting);
    }
    public static void endEntity() { submitting=null; }
    public static void tag(Object submit) {
        if(worldCapture() && submitting!=null) {
            owners.put(submit,submitting);
            if(submit instanceof MovingBlockFeatureRenderer.Submit) { var trace=falling(submitting); if(trace!=null) trace.movingSubmits++; }
        }
    }
    public static void enter(Object submit) {
        if(submit instanceof MovingBlockFeatureRenderer.Submit) {
            var owner=worldCapture()?owners.get(submit):null;
            var trace=falling(owner);
            if(trace!=null) trace.movingGroups++;
            else if(worldCapture()) for(var candidate:fallingTraces.values()) candidate.movingGroupsWithoutOwner++;
            preparing=owner;
        } else preparing=worldCapture()?owners.get(submit):null;
    }
    public static void leave() { preparing=null;pending=null; }
    public static void reject(String reason) { reject(preparing,reason); }
    public static void reject(Owner owner,String reason) {
        rejected.merge(reason,1,Integer::sum);
        if(owner!=null) ownerRejections.computeIfAbsent(owner.id(),id->new TreeSet<>()).add(reason);
    }
    public static String ignoredReason(Owner owner) {
        var reasons=ownerRejections.get(owner.id());if(reasons!=null) return reasons.toString();
        if(pieces.stream().noneMatch(p->p.owner().id()==owner.id())) return "NO_MODEL_CUBE_OR_BAKED_QUAD_EMISSION";
        if(executed.stream().noneMatch(p->p.owner().id()==owner.id())) return "NO_MATCHING_EXECUTED_DRAW";
        return "NO_ACCEPTED_GPU_UPLOAD";
    }
    public static void prepared(RenderType type,PreparedRenderType value) { if(worldCapture()) prepared.put(type,value); }
    public static void builder(StagedVertexBuffer.Draw draw,VertexConsumer vertex) {
        if(worldCapture() && vertex instanceof BufferBuilder b) cursors.putIfAbsent(b,new Cursor(draw,((EntityDrawAccessor)(Object)draw).nativeVulkanRt$vertexCount()));
    }
    public static void renderType(VertexConsumer vertex,RenderType type) { if(worldCapture() && vertex instanceof BufferBuilder b) types.put(b,type); }
    public static void beginPiece(VertexConsumer vertex,Object shape,PoseStack.Pose pose,Colors colors,int overlay) {
        pending=null;if(!collecting()) return;
        var trace=falling(preparing);
        if(trace!=null) { trace.quadCalls++; trace.pipeline="UNRESOLVED"; }
        if(!(vertex instanceof BufferBuilder b)) { reject("WRAPPED_VERTEX_CONSUMER");return; }
        var cursor=cursors.get(b);var rt=types.get(b);var material=prepared.get(rt);
        if(cursor==null || material==null) { reject("NO_STAGED_DRAW_OR_PREPARED_MATERIAL");return; }
        var draw=(EntityDrawAccessor)(Object)cursor.draw();
        if(draw.nativeVulkanRt$topology()!=PrimitiveTopology.QUADS) { reject("NON_QUAD_TOPOLOGY");return; }
        var matrix=new Matrix4f(pose.pose());
        if(!matrix.isFinite() || Math.abs(matrix.determinant())<1e-10f) { reject("SINGULAR_OR_NONFINITE_POSE");return; }
        if(trace!=null) { trace.pipeline=material.pipeline().getLocation()+" cutoff="+EntityGeometryManager.cutoff(material.pipeline())+" cull="+material.pipeline().isCull(); trace.pose=matrixSummary(matrix); }
        pending=new Pending(b,cursor,((EntityBuilderAccessor)(Object)b).nativeVulkanRt$vertices(),shape,matrix,colors,overlay,material,preparing);
    }
    public static void endPiece() {
        var p=pending;pending=null;if(p==null) return;
        int n=((EntityBuilderAccessor)(Object)p.builder()).nativeVulkanRt$vertices()-p.start();
        if(n==0) return;
        if(n<0 || n%4!=0 || p.start()%4!=0) { reject("INCOMPLETE_QUAD_RANGE");return; }
        var format=((EntityDrawAccessor)(Object)p.cursor().draw()).nativeVulkanRt$format();
        if(format!=DefaultVertexFormat.ENTITY && format!=DefaultVertexFormat.BLOCK) { reject(p.owner(),"UNSUPPORTED_VERTEX_FORMAT:"+format);return; }
        SectionGeometryLayout layout;
        try { layout=SectionGeometryLayout.solidQuads(format,n/4*6); }
        catch(IllegalArgumentException failure) { reject("VERTEX_LAYOUT:"+failure.getMessage());return; }
        pieces.add(new Piece(p.owner(),new Key(p.shape(),layout),p.cursor().draw(),p.cursor().base()+p.start(),p.pose(),p.material(),p.colors(),p.overlay()));
        var trace=falling(p.owner()); if(trace!=null) { trace.emittedQuads+=n/4; trace.emittedVertices+=n; trace.pipeline=p.material().pipeline().getLocation()+" cutoff="+EntityGeometryManager.cutoff(p.material().pipeline())+" cull="+p.material().pipeline().isCull(); trace.pose=matrixSummary(p.pose()); }
    }
    public static void quad(VertexConsumer vertex,PoseStack.Pose pose,BakedQuad quad,QuadInstance instance) {
        if(!collecting()) { vertex.putBakedQuad(pose,quad,instance);return; }
        beginPiece(vertex,quad,pose,new Colors(instance.getColor(0),instance.getColor(1),instance.getColor(2),instance.getColor(3)),instance.overlayCoords());
        vertex.putBakedQuad(pose,quad,instance); // EXACTLY the original vanilla emission, once
        endPiece();
    }
    public static void upload(StagedVertexBuffer owner,GpuBufferSlice source,GpuBufferSlice target) {
        if(!worldCapture() || source.offset()!=0 || !(source.buffer() instanceof VulkanGpuBuffer vk)) return;
        var draws=((EntityStagedAccessor)(Object)owner).nativeVulkanRt$draws();var uploads=new ArrayList<Upload>();
        for(var p:pieces) if(draws.contains(p.draw())) {
            var d=(EntityDrawAccessor)(Object)p.draw();int stride=p.key().layout().stride();
            long offset=(long)d.nativeVulkanRt$vertexOffset()+(long)p.first()*stride;
            long bytes=p.key().layout().vertexBytes();
            if(offset<0 || offset>source.length()-bytes || p.first()+p.key().layout().vertexCount()>d.nativeVulkanRt$vertexCount()) { reject(p.owner(),"STAGED_UPLOAD_RANGE");continue; }
            uploads.add(new Upload(p,vk,offset));
            var trace=falling(p.owner()); if(trace!=null) { trace.uploadPieces++; trace.uploadVertices+=p.key().layout().vertexCount(); trace.uploadBytes+=Math.toIntExact(bytes); }
            uploaded.computeIfAbsent(new DrawAddress(target.buffer(),d.nativeVulkanRt$vertexOffset()/stride),k->new ArrayList<>()).add(p);
        }
        if(!uploads.isEmpty()) RayTracingRenderer.uploadEntities(uploads);
    }
    public static void draw(PreparedRenderType material,StagedVertexBuffer.ExecuteInfo info) {
        if(!worldCapture()) return;
        var list=uploaded.get(new DrawAddress(info.vertexBuffer(),info.baseVertex()));
        if(list!=null) for(var p:list) if(p.material().equals(material)) { executed.add(p); var trace=falling(p.owner()); if(trace!=null) trace.drawReceipts++; }
    }
    private EntityCapture() {}
}
