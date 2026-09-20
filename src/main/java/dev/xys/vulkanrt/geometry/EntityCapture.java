package dev.xys.vulkanrt.geometry;

import com.mojang.blaze3d.vertex.*;
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
import org.joml.*;
import java.util.*;

/** Receipts for vanilla-emitted ranges, never a mesher. All methods run on the render thread. */
public final class EntityCapture {
    public record Owner(int id,String type,double x,double y,double z,boolean falling) {}
    public record Key(Object geometry,SectionGeometryLayout layout) {}
    public record Colors(int a,int b,int c,int d) { public static Colors uniform(int c) { return new Colors(c,c,c,c); } }
    public record Piece(Owner owner,Key key,StagedVertexBuffer.Draw draw,int first,Matrix4f pose,
                        PreparedRenderType material,Colors colors,int overlay) {}
    public record Upload(Piece piece,VulkanGpuBuffer source,long offset) {}
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
    private static Owner submitting,preparing;
    private static Pending pending;
    public static boolean enabled() { return RayTracingRenderer.chunkCaptureEnabled(); }
    public static boolean collecting() { return enabled() && preparing!=null; }
    public static void beginFrame() { owners.clear();cursors.clear();types.clear();prepared.clear();uploaded.clear();pieces.clear();executed.clear();discovered.clear();rejected.clear();submitting=null;preparing=null;pending=null; }
    public static void state(EntityRenderState state,int id) { if(enabled()) ids.put(state,id); }
    public static void beginEntity(EntityRenderState s) {
        if(!enabled()) return;
        submitting=new Owner(ids.getOrDefault(s,System.identityHashCode(s)),String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(s.entityType)),s.x,s.y,s.z,s instanceof FallingBlockRenderState);
        discovered.put(submitting.id(),submitting);
    }
    public static void endEntity() { submitting=null; }
    public static void tag(Object submit) { if(enabled() && submitting!=null) owners.put(submit,submitting); }
    public static void enter(Object submit) { preparing=owners.get(submit); }
    public static void leave() { preparing=null;pending=null; }
    public static void reject(String reason) { rejected.merge(reason,1,Integer::sum); }
    public static void prepared(RenderType type,PreparedRenderType value) { if(enabled()) prepared.put(type,value); }
    public static void builder(StagedVertexBuffer.Draw draw,VertexConsumer vertex) {
        if(enabled() && vertex instanceof BufferBuilder b) cursors.putIfAbsent(b,new Cursor(draw,((EntityDrawAccessor)(Object)draw).nativeVulkanRt$vertexCount()));
    }
    public static void renderType(VertexConsumer vertex,RenderType type) { if(enabled() && vertex instanceof BufferBuilder b) types.put(b,type); }
    public static void beginPiece(VertexConsumer vertex,Object shape,PoseStack.Pose pose,Colors colors,int overlay) {
        pending=null;if(!enabled() || preparing==null) return;
        if(!(vertex instanceof BufferBuilder b)) { reject("WRAPPED_VERTEX_CONSUMER");return; }
        var cursor=cursors.get(b);var rt=types.get(b);var material=prepared.get(rt);
        if(cursor==null || material==null) { reject("NO_STAGED_DRAW_OR_PREPARED_MATERIAL");return; }
        var draw=(EntityDrawAccessor)(Object)cursor.draw();
        if(draw.nativeVulkanRt$topology()!=PrimitiveTopology.QUADS) { reject("NON_QUAD_TOPOLOGY");return; }
        var matrix=new Matrix4f(pose.pose());
        if(!matrix.isFinite() || Math.abs(matrix.determinant())<1e-10f) { reject("SINGULAR_OR_NONFINITE_POSE");return; }
        pending=new Pending(b,cursor,((EntityBuilderAccessor)(Object)b).nativeVulkanRt$vertices(),shape,matrix,colors,overlay,material,preparing);
    }
    public static void endPiece() {
        var p=pending;pending=null;if(p==null) return;
        int n=((EntityBuilderAccessor)(Object)p.builder()).nativeVulkanRt$vertices()-p.start();
        if(n==0) return;
        if(n<0 || n%4!=0 || p.start()%4!=0) { reject("INCOMPLETE_QUAD_RANGE");return; }
        var format=((EntityDrawAccessor)(Object)p.cursor().draw()).nativeVulkanRt$format();
        var layout=SectionGeometryLayout.solidQuads(format,n/4*6);
        pieces.add(new Piece(p.owner(),new Key(p.shape(),layout),p.cursor().draw(),p.cursor().base()+p.start(),p.pose(),p.material(),p.colors(),p.overlay()));
    }
    public static void quad(VertexConsumer vertex,PoseStack.Pose pose,BakedQuad quad,QuadInstance instance) {
        if(!collecting()) { vertex.putBakedQuad(pose,quad,instance);return; }
        beginPiece(vertex,quad,pose,new Colors(instance.getColor(0),instance.getColor(1),instance.getColor(2),instance.getColor(3)),instance.overlayCoords());
        vertex.putBakedQuad(pose,quad,instance); // EXACTLY the original vanilla emission, once
        endPiece();
    }
    public static void upload(StagedVertexBuffer owner,GpuBufferSlice source,GpuBufferSlice target) {
        if(!enabled() || source.offset()!=0 || !(source.buffer() instanceof VulkanGpuBuffer vk)) return;
        var draws=((EntityStagedAccessor)(Object)owner).nativeVulkanRt$draws();var uploads=new ArrayList<Upload>();
        for(var p:pieces) if(draws.contains(p.draw())) {
            var d=(EntityDrawAccessor)(Object)p.draw();int stride=p.key().layout().stride();
            long offset=(long)d.nativeVulkanRt$vertexOffset()+(long)p.first()*stride;
            long bytes=p.key().layout().vertexBytes();
            if(offset<0 || offset>source.length()-bytes || p.first()+p.key().layout().vertexCount()>d.nativeVulkanRt$vertexCount()) { reject("STAGED_UPLOAD_RANGE");continue; }
            uploads.add(new Upload(p,vk,offset));
            uploaded.computeIfAbsent(new DrawAddress(target.buffer(),d.nativeVulkanRt$vertexOffset()/stride),k->new ArrayList<>()).add(p);
        }
        if(!uploads.isEmpty()) RayTracingRenderer.uploadEntities(uploads);
    }
    public static void draw(PreparedRenderType material,StagedVertexBuffer.ExecuteInfo info) {
        if(!enabled()) return;
        var list=uploaded.get(new DrawAddress(info.vertexBuffer(),info.baseVertex()));
        if(list!=null) for(var p:list) if(p.material().equals(material)) executed.add(p);
    }
    private EntityCapture() {}
}
