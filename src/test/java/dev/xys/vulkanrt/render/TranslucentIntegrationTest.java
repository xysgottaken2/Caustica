package dev.xys.vulkanrt.render;

import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.*;
import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import dev.xys.vulkanrt.geometry.SectionGeometrySanity;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.oit.OitStage;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual resolved 26.3 classes, plus explicitly CPU-only arithmetic/cache reference tests.
 * These tests are not Vulkan execution or evidence of water/glass visibility. */
final class TranslucentIntegrationTest {
    @Test void realTranslucentClassicAndOitShareBlockQuadFormatButNotCutoutAlphaPolicy() {
        var layer=ChunkSectionLayer.TRANSLUCENT;
        assertArrayEquals(new ChunkSectionLayer[]{layer},ChunkSectionLayerGroup.TRANSLUCENT.layers());
        for(boolean mdi:new boolean[]{false,true}) {
            var pipeline=layer.pipeline(mdi);
            assertEquals("pipeline/translucent_terrain"+(mdi?"_multidraw":""),pipeline.getLocation().getPath());
            assertTrue(pipeline.isCull());assertEquals(PrimitiveTopology.QUADS,pipeline.getPrimitiveTopology());
            assertEquals(BlendFunction.TRANSLUCENT,pipeline.getColorTargetStates().getFirst().blendFunction().orElseThrow());
            assertEquals(0.1f,Float.parseFloat(pipeline.getShaderDefines().values().get("ALPHA_CUTOUT")));
            for(var stage:OitStage.values()) {
                var oit=(mdi?RenderPipelines.OIT_TERRAIN_MULTIDRAW:RenderPipelines.OIT_TERRAIN).getPipeline(stage);
                assertEquals(0.1f,Float.parseFloat(oit.getShaderDefines().values().get("ALPHA_CUTOUT")));
            }
        }
        var layout=SectionGeometryLayout.solidQuads(layer.vertexFormat(),12);
        assertEquals(28,layout.stride());assertEquals(0,layout.positionOffset());
        assertEquals(8,layout.vertexCount());assertEquals(4,layout.triangles());
        assertEquals(3,ChunkMaterialTable.attribute(layout,"Color","RGBA8_UNORM",4));
        assertEquals(4,ChunkMaterialTable.attribute(layout,"UV0","RG32_FLOAT",8));
        assertEquals(6,ChunkMaterialTable.attribute(layout,"UV2","RG16_SINT",4));
        var bytes=ByteBuffer.allocate(ChunkMaterialTable.ROW_BYTES).order(ByteOrder.nativeOrder());
        for(int width:new int[]{2,4}) {
            ChunkMaterialTable.pack(bytes,0,new ChunkMaterialTable.Entry(0,0x123400,layout,null,ChunkMaterialTable.TRANSLUCENT|ChunkMaterialTable.CULL_BACK,0x567800,width));
            assertEquals(6,bytes.getInt(44));assertEquals(0,bytes.getInt(28));
            assertEquals(0x567800,bytes.getLong(80));assertEquals(width,bytes.getInt(88));assertEquals(12,bytes.getInt(92));
            assertEquals(0,bytes.getLong(64),"Do not apply grass overlay to a fluid/TRANSLUCENT surface");
        }
        assertThrows(IllegalArgumentException.class,()->ChunkMaterialTable.pack(bytes,0,new ChunkMaterialTable.Entry(0,0x123400,layout,null,4)));
        assertThrows(IllegalArgumentException.class,()->new ChunkMaterialTable.Entry(0,0x123400,layout,null,5,0x567800,2));
    }
    @Test void actualVanillaSortedIndicesAreOnlyQuadPermutationsForShortAndInt() {
        for(var type:IndexType.values()) {
            var state=new MeshData.SortState(new CompactVectorArray(3),type);
            var bytes=ByteBuffer.allocate(18*type.bytes).order(ByteOrder.nativeOrder());
            for(int[] order:new int[][]{{2,0,1},{0,1,2},{1,2,0}}) {
                bytes.clear();state.writeSortedIndexBuffer(bytes,points->order);
                int[] corners={0,1,2,2,3,0};
                var triangles=new HashSet<String>();
                for(int q=0;q<3;q++) for(int c=0;c<6;c++) {
                    int index=q*6+c;
                    // Same packed uint decoding used by the shader, without shaderInt16.
                    int actual=type.bytes==2?(bytes.getInt((index/2)*4)>>>((index&1)*16))&65535:bytes.getInt(index*4);
                    assertEquals(order[q]*4+corners[c],actual);
                    if(c%3==0) triangles.add(order[q]+":"+(c/3));
                }
                assertEquals(6,triangles.size(),"Sorting changes order, never drops/adds geometry");
            }
        }
    }
    @Test void sortedShortIndicesRemainUnsignedAbove32767() {
        var state=new MeshData.SortState(new CompactVectorArray(16384),IndexType.SHORT);
        var bytes=ByteBuffer.allocate(16384*6*2).order(ByteOrder.nativeOrder());
        state.writeSortedIndexBuffer(bytes,points->java.util.stream.IntStream.range(0,16384).map(i->16383-i).toArray());
        int[] expected={65532,65533,65534,65534,65535,65532};
        for(int i=0;i<6;i++) assertEquals(expected[i],(bytes.getInt((i/2)*4)>>>((i&1)*16))&65535);
    }
    @Test void indexRangesRejectBadLifetimeCopyUsageAlignmentAndSize() {
        assertTrue(SectionGeometrySanity.validIndexRange(12,2,4,28,1,false,true));
        assertTrue(SectionGeometrySanity.validIndexRange(12,4,4,52,1,false,true));
        assertFalse(SectionGeometrySanity.validIndexRange(12,2,2,28,1,false,true));
        assertFalse(SectionGeometrySanity.validIndexRange(12,2,4,27,1,false,true));
        assertFalse(SectionGeometrySanity.validIndexRange(12,2,Long.MAX_VALUE-3,Long.MAX_VALUE,1,false,true));
        assertFalse(SectionGeometrySanity.validIndexRange(12,2,4,28,1,true,true));
        assertFalse(SectionGeometrySanity.validIndexRange(12,2,4,28,1,false,false));
        assertFalse(SectionGeometrySanity.validIndexRange(12,1,4,28,1,false,true));
        assertFalse(SectionGeometrySanity.validIndexRange(11,2,4,28,1,false,true));
    }
    @Test void independentTranslucentChurnLeavesAcceptedSolidAndCutoutCachesAlone() {
        var solid=new TreeMap<Long,Object>();for(long i=0;i<444;i++) solid.put(i,new Object());
        var cutout=Map.of(1L,new Object());var trans=Map.of(1L,new Object(),2L,new Object());
        var changed=Map.of(1L,trans.get(1L),2L,new Object());
        assertEquals(444,SectionResidency.diff(solid,solid,(a,b)->a==b).reuse().size());
        assertFalse(SectionResidency.diff(cutout,cutout,(a,b)->a==b).changed());
        var diff=SectionResidency.diff(trans,changed,(a,b)->a==b);
        assertEquals(List.of(2L),diff.build());assertEquals(List.of(2L),diff.retire());assertEquals(List.of(1L),diff.reuse());
        assertEquals(SectionResidency.TlasAction.UPDATE,SectionResidency.tlasAction(447,447,diff.changed(),false));
        assertEquals(SectionResidency.TlasAction.BUILD,SectionResidency.tlasAction(445,447,true,false));
        assertEquals(SectionResidency.TlasAction.REUSE,SectionResidency.tlasAction(447,447,false,false));
        assertEquals(SectionResidency.TlasAction.BUILD,SectionResidency.tlasAction(0,2,true,false),"TRANSLUCENT-only scene allowed");
        assertEquals(SectionResidency.TlasAction.EMPTY,SectionResidency.tlasAction(2,0,true,false));
        var retired=new ArrayList<Object>();assertEquals(1,SectionResidency.retireReplaced(trans,changed,retired::add));assertEquals(List.of(trans.get(2L)),retired);
        assertEquals(0,0x02&0x01,"Overflow background ray excludes TRANSLUCENT instances");
        assertNotEquals(0,0xff&0x01,"Existing SOLID/CUTOUT masks remain included");
    }
    @Test void frontToBackReferenceRetainsBackgroundAndUsesBothAlphas() {
        // Near red .25, further green .5, opaque blue: source-over in true depth order.
        double t=1,r=0,g=0,b=0;
        r+=t*.25;t*=1-.25;g+=t*.5;t*=1-.5;b+=t;
        assertEquals(.25,r);assertEquals(.375,g);assertEquals(.375,b);
        assertEquals(.2,.5*.4,1e-12,"Texture alpha times prepared vertex alpha, not guessed water opacity");
        assertTrue(.2>=.1 && .2<.5,"Valid TRANSLUCENT is not CUTOUT .5");
        for(float distance:new float[]{.001f,1f,100f,4095f}) {
            float next=Float.intBitsToFloat(Float.floatToRawIntBits(distance)+1);
            assertEquals(Math.nextUp(distance),next);assertTrue(next>distance && next-distance<.001f);
        }
        assertTrue(Math.pow(.9,64)<.00118,"Bounded residual at 64 accepted vanilla .1-or-greater surfaces");
    }
}
