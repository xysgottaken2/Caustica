package dev.xys.vulkanrt.render;

import com.mojang.blaze3d.platform.Transparency;
import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

final class CutoutIntegrationTest {
    @Test void real263LayersAndOpaqueGroupHaveNoSeparateCutoutMipped() {
        assertArrayEquals(new String[]{"SOLID","CUTOUT","TRANSLUCENT"},java.util.Arrays.stream(ChunkSectionLayer.values()).map(Enum::name).toArray(String[]::new));
        assertArrayEquals(new ChunkSectionLayer[]{ChunkSectionLayer.SOLID,ChunkSectionLayer.CUTOUT},ChunkSectionLayerGroup.OPAQUE.layers());
        assertEquals(ChunkSectionLayer.SOLID,ChunkSectionLayer.byTransparency(Transparency.NONE));
        assertEquals(ChunkSectionLayer.CUTOUT,ChunkSectionLayer.byTransparency(Transparency.TRANSPARENT));
        assertEquals(ChunkSectionLayer.TRANSLUCENT,ChunkSectionLayer.byTransparency(Transparency.TRANSPARENT_AND_TRANSLUCENT));
        assertEquals("pipeline/cutout_terrain",ChunkSectionLayer.CUTOUT.pipeline(false).getLocation().getPath());
        assertEquals("pipeline/cutout_terrain_multidraw",ChunkSectionLayer.CUTOUT.pipeline(true).getLocation().getPath());
        assertTrue(ChunkSectionLayer.CUTOUT.pipeline(false).isCull());
        for(boolean mdi:new boolean[]{false,true})
            assertEquals(0.5f,Float.parseFloat(ChunkSectionLayer.CUTOUT.pipeline(mdi).getShaderDefines().values().get("ALPHA_CUTOUT")));
    }
    @Test void actualCutoutVertexLayoutAndMetadataPreserveUvColorAndLight() {
        var layout=SectionGeometryLayout.solidQuads(ChunkSectionLayer.CUTOUT.vertexFormat(),12);
        assertEquals(28,layout.stride());assertEquals(0,layout.positionOffset());assertEquals(8,layout.vertexCount());assertEquals(4,layout.triangles());
        assertEquals(3,ChunkMaterialTable.attribute(layout,"Color","RGBA8_UNORM",4));
        assertEquals(4,ChunkMaterialTable.attribute(layout,"UV0","RG32_FLOAT",8));
        assertEquals(6,ChunkMaterialTable.attribute(layout,"UV2","RG16_SINT",4));
        var bytes=ByteBuffer.allocate(80).order(ByteOrder.nativeOrder());
        ChunkMaterialTable.pack(bytes,0,new ChunkMaterialTable.Entry(0,0x123400L,layout,null,ChunkMaterialTable.CUTOUT|ChunkMaterialTable.CULL_BACK));
        assertEquals(3,bytes.getInt(44));assertEquals(0x123400L,bytes.getLong(0));
        assertEquals(0,bytes.getInt(28),"TEXEL remains default");assertEquals(0,bytes.getLong(64),"CUTOUT closest-hit does not apply the SOLID overlay twice");
    }
    @Test void actualFaceInfoWindingMakesFrontAndBackCutoutSidesUnambiguous() {
        for(var face:net.minecraft.core.Direction.values()) {
            var info=net.minecraft.client.renderer.FaceInfo.fromFacing(face);
            var min=new org.joml.Vector3f(0);var max=new org.joml.Vector3f(1);
            var a=info.getVertexInfo(0).select(min,max);var b=info.getVertexInfo(1).select(min,max);var c=info.getVertexInfo(2).select(min,max);
            var normal=b.sub(a,new org.joml.Vector3f()).cross(c.sub(a,new org.joml.Vector3f()));
            assertTrue(normal.dot(face.getUnitVec3f())>0,face.toString());
            assertTrue(normal.dot(new org.joml.Vector3f(face.getUnitVec3f()).negate())<0,"Approaching external face must survive culling");
        }
    }
    @Test void onlyCutoutBlasMayInvokeAnyHit() {
        assertEquals(VK_GEOMETRY_OPAQUE_BIT_KHR,AccelerationStructureManager.sectionGeometryFlags(false));
        int cutout=AccelerationStructureManager.sectionGeometryFlags(true);
        assertEquals(0,cutout&VK_GEOMETRY_OPAQUE_BIT_KHR);
        assertNotEquals(0,cutout&VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
    }
    @Test void addingAndChangingCutoutCannotInvalidate444StableSolidResidents() {
        var solid=new TreeMap<Long,Object>(); for(long i=0;i<444;i++) solid.put(i,new Object());
        var oldCutout=Map.of(1L,new Object());var nextCutout=Map.of(1L,new Object(),500L,new Object());
        var solidDiff=SectionResidency.diff(solid,solid,(a,b)->a==b);
        var cutoutDiff=SectionResidency.diff(oldCutout,nextCutout,(a,b)->a==b);
        assertEquals(444,solidDiff.reuse().size());assertTrue(solidDiff.build().isEmpty());assertTrue(solidDiff.retire().isEmpty());
        assertEquals(2,cutoutDiff.build().size());assertEquals(1,cutoutDiff.retire().size());
        assertEquals(SectionResidency.TlasAction.BUILD,SectionResidency.tlasAction(445,446,cutoutDiff.changed(),false));
        var stable=SectionResidency.diff(nextCutout,nextCutout,(a,b)->a==b);
        assertEquals(SectionResidency.TlasAction.REUSE,SectionResidency.tlasAction(446,446,stable.changed(),false));
        // A section containing only CUTOUT must still produce a TLAS, with no SOLID requirement.
        assertEquals(SectionResidency.TlasAction.BUILD,SectionResidency.tlasAction(0,1,true,false));
    }
}
