package dev.xys.vulkanrt.render;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.xys.vulkanrt.geometry.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** No GPU: real format/pipeline contracts plus cache/transform/material reference checks. */
final class EntityIntegrationTest {
 @Test void actualVulkanTransformPackingIsRowMajorAndIncludesAnimationScaleAndTranslation() {
  var m=new Matrix4f().translation(12,4,-7).rotateY(.7f).rotateX(.3f).scale(-2,.5f,3);
  try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
   var packed=org.lwjgl.vulkan.VkTransformMatrixKHR.calloc(stack);
   AccelerationStructureManager.putAffineTransform(packed,m);
   for(var v:List.of(new Vector3f(),new Vector3f(1,2,3),new Vector3f(-4,.2f,7))) {
    var expected=m.transformPosition(v,new Vector3f());
    for(int r=0;r<3;r++) assertEquals(expected.get(r),packed.matrix(r*4)*v.x+packed.matrix(r*4+1)*v.y+packed.matrix(r*4+2)*v.z+packed.matrix(r*4+3),.00001f);
   }
  }
 }
 @Test void realEntityFormatHasOverlayLightAndNormalUnlikeTerrain() {
  var l=SectionGeometryLayout.solidQuads(DefaultVertexFormat.ENTITY,36);
  assertEquals(36,l.stride());assertEquals(24,l.vertexCount());assertEquals(12,l.triangles());
  assertEquals(0,l.positionOffset());assertEquals(3,ChunkMaterialTable.attribute(l,"Color","RGBA8_UNORM",4));
  assertEquals(4,ChunkMaterialTable.attribute(l,"UV0","RG32_FLOAT",8));
  assertEquals(6,ChunkMaterialTable.attribute(l,"UV1","RG16_SINT",4));
  assertEquals(7,ChunkMaterialTable.attribute(l,"UV2","RG16_SINT",4));
  assertEquals(8,ChunkMaterialTable.attribute(l,"Normal","RGBA8_SNORM",4));
 }
 @Test void entityAndMovingBlockAlphaPoliciesComeFromDifferentRealPipelines() {
  assertEquals(.1f,EntityGeometryManager.cutoff(RenderPipelines.ENTITY_CUTOUT));
  assertEquals(.5f,EntityGeometryManager.cutoff(RenderPipelines.CUTOUT_TERRAIN));
  assertEquals(.5f,EntityGeometryManager.cutoff(RenderTypes.cutoutMovingBlock().pipeline()));
  assertSame(RenderPipelines.SOLID_BLOCK,RenderTypes.solidMovingBlock().pipeline());
  assertSame(RenderPipelines.TRANSLUCENT_BLOCK,RenderTypes.translucentMovingBlock().pipeline());
  assertTrue(EntityGeometryManager.supported(RenderPipelines.ITEM_CUTOUT));
  assertTrue(EntityGeometryManager.supported(RenderPipelines.ENTITY_TRANSLUCENT));
  assertFalse(EntityGeometryManager.supported(RenderPipelines.ITEM_CUTOUT_GLINT),"Same name is not sufficient to authorize an incompatible shader");
  assertFalse(EntityGeometryManager.supported(RenderPipelines.ENTITY_CUTOUT_DISSOLVE));
  assertFalse(EntityGeometryManager.capturedAsNonOpaque(RenderPipelines.SOLID_BLOCK,true),"Falling SOLID_BLOCK must use an opaque BLAS");
  assertTrue(EntityGeometryManager.capturedAsNonOpaque(RenderPipelines.CUTOUT_BLOCK,true),"Falling CUTOUT_BLOCK retains any-hit alpha");
  assertTrue(EntityGeometryManager.capturedAsNonOpaque(RenderPipelines.ENTITY_SOLID,false),"Normal entity policy remains unchanged");
  assertEquals(8|1,EntityGeometryManager.flags(RenderPipelines.ENTITY_CUTOUT));
  assertEquals(8|4,EntityGeometryManager.flags(RenderPipelines.ENTITY_TRANSLUCENT));
  assertEquals(8|2,EntityGeometryManager.flags(RenderPipelines.ENTITY_SOLID));
 }
 @Test void movingPoseAndAnimationAreIndependentOfGeometryAndMaterial() {
  var local=new Vector3f(.25f,.75f,-.4f);
  for(float yaw:new float[]{0,.5f,2}) {
   var pose=new Matrix4f().translation(23,4,-12).rotateY(yaw).rotateX(yaw*.5f).scale(-.75f,-.75f,.75f);
   var emitted=pose.transformPosition(local,new Vector3f());
   var recovered=new Matrix4f(pose).invert().transformPosition(emitted,new Vector3f());
   assertTrue(local.distance(recovered)<.00001f);
   var anchor=new ChunkCoordinates.Anchor(30000000,64,-30000000);
   var world=new Matrix4f().translation(anchor.cameraX(30000020),anchor.cameraY(70),anchor.cameraZ(-29999980)).mul(pose);
   var result=world.transformPosition(recovered,new Vector3f());
   assertTrue(result.distance(new Vector3f(emitted).add(20,6,20))<.00002f);
  }
  var geometry=new Object();var layout=SectionGeometryLayout.solidQuads(DefaultVertexFormat.ENTITY,6);
  var key=new EntityCapture.Key(geometry,layout);
  assertEquals(key,new EntityCapture.Key(geometry,layout),"No entity position, animation, material, light or tint in immutable geometry key");
  assertNotEquals(key,new EntityCapture.Key(new Object(),layout),"Replacement vanilla geometry invalidates cache");
 }
 @Test void materialKeepsCurrentOriginalColorsCutoffOverlayAndTextureSlotWithoutRebuildingBlas() {
  var layout=SectionGeometryLayout.solidQuads(DefaultVertexFormat.ENTITY,6);
  var c=new EntityCapture.Colors(0x80112233,0xff445566,0xff778899,0xffaabbcc);
  var m=new EntityGeometryManager.Material(3,.1f,0x000a000b,7,c,4,false,19,true);
  var entry=new ChunkMaterialTable.Entry(0,0x123400,layout,null,8|1,0,0,m);
  var bytes=ByteBuffer.allocate(ChunkMaterialTable.ROW_BYTES).order(ByteOrder.nativeOrder());
  ChunkMaterialTable.pack(bytes,0,entry);
  assertEquals(144,ChunkMaterialTable.ROW_BYTES);assertEquals(3,bytes.getInt(96));assertEquals(.1f,bytes.getFloat(100));
  assertEquals(0x000a000b,bytes.getInt(104));assertEquals(7,bytes.getInt(108));assertEquals(0x80332211,bytes.getInt(112));
  assertEquals(4,bytes.getInt(128));assertEquals(19,bytes.getInt(136));assertEquals(1,bytes.getInt(140));
  assertEquals(0,bytes.getInt(28),"TEXEL unchanged");assertEquals(0,bytes.getLong(80));
 }
 @Test void cacheChurnOfEntitiesCannotInvalidateTerrainCaches() {
  var terrain=new TreeMap<Integer,Object>();for(int i=0;i<444;i++) terrain.put(i,new Object());
  var zombie=new Object();var cow=new Object();var sand=new Object();
  var before=Map.of(1,zombie,2,cow,3,sand);var after=Map.of(1,zombie,3,sand);
  assertFalse(SectionResidency.diff(terrain,terrain,(a,b)->a==b).changed());
  var diff=SectionResidency.diff(before,after,(a,b)->a==b);assertEquals(2,diff.reuse().size());assertTrue(diff.build().isEmpty());assertEquals(List.of(2),diff.retire());
  assertEquals(SectionResidency.TlasAction.UPDATE,SectionResidency.tlasAction(450,450,true,false));
  assertEquals(SectionResidency.TlasAction.BUILD,SectionResidency.tlasAction(450,449,true,false));
  assertTrue(EntityGeometryManager.TEXTURES+1<=16,"Literal texture bank fits core per-stage sampler minimum");
 }
}
