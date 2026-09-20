package dev.xys.vulkanrt.render;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

final class ShaderResourcesTest {
    private ByteBuffer shader(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/native_vulkan_rt/spirv/"+name+".spv")) {
            assertNotNull(stream,name);
            byte[] bytes=stream.readAllBytes();
            assertTrue(bytes.length>=20); assertEquals(0,bytes.length%4);
            var words=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0x07230203,words.getInt(0)); assertEquals(0x00010400,words.getInt(4));
            return words;
        }
    }
    @Test void everyPipelineStageHasBuildTimeSpirv() throws Exception {
        for (String family : new String[]{"primary","chunks"}) for (String stage : new String[]{"rgen","rmiss","rchit"})
            shader(family+"."+stage);
    }
    @Test void physicalVertexReadsDoNotRequireShaderInt64OrDescriptorIndexing() throws Exception {
        var words=shader("chunks.rchit");
        var capabilities=new HashSet<Integer>();
        var bindings=new HashSet<Integer>();
        boolean rowsStride48=false;
        for (int i=20;i<words.limit();) {
            int head=words.getInt(i), count=head>>>16, opcode=head&0xffff;
            assertTrue(count>0); assertTrue(i+count*4<=words.limit());
            if(opcode==17) capabilities.add(words.getInt(i+4)); // OpCapability
            if(opcode==71 && count==4) { // OpDecorate
                int decoration=words.getInt(i+8), value=words.getInt(i+12);
                if(decoration==33) bindings.add(value); // Binding
                if(decoration==6 && value==48) rowsStride48=true; // ArrayStride
            }
            i+=count*4;
        }
        assertTrue(capabilities.contains(5347),"PhysicalStorageBufferAddresses");
        assertFalse(capabilities.contains(11),"shaderInt64 is not enabled/required");
        assertFalse(capabilities.contains(5301),"ShaderNonUniform indexing is not enabled/required");
        assertEquals(java.util.Set.of(2,3),bindings);
        assertTrue(rowsStride48,"Material std430 row must match Java packer");
    }
    @Test void bothComparisonPathsSampleViewMipZeroAndDiagnosticProbeHasFiveVectors() throws Exception {
        var words=shader("chunks.rchit");
        var zeroConstants=new HashSet<Integer>();
        var lods=new java.util.ArrayList<Integer>();
        boolean fetch=false, filtered=false;
        for(int i=20;i<words.limit();) {
            int head=words.getInt(i), count=head>>>16, opcode=head&0xffff;
            assertTrue(count>0);
            if(opcode==43 && count==4 && words.getInt(i+12)==0) zeroConstants.add(words.getInt(i+8));
            if(opcode==95 || opcode==88) { // OpImageFetch / OpImageSampleExplicitLod
                fetch |= opcode==95; filtered |= opcode==88;
                assertTrue(count>=7); assertEquals(2,words.getInt(i+20)); // Lod, no implicit/bias selection
                lods.add(words.getInt(i+24));
            }
            i+=count*4;
        }
        assertTrue(fetch,"Unfiltered atlas texel path must be executable SPIR-V");
        assertTrue(filtered,"0.5.0 filtered A/B path must remain available");
        assertTrue(zeroConstants.containsAll(lods),"Both paths explicitly use view LOD 0, never an arbitrary reduced mip");
        var raygen=shader("chunks.rgen");
        var offsets=new java.util.HashMap<Integer,java.util.Map<Integer,Integer>>();
        for(int i=20;i<raygen.limit();) {
            int head=raygen.getInt(i), count=head>>>16;
            assertTrue(count>0);
            if((head&0xffff)==72 && count==5 && raygen.getInt(i+12)==35) // OpMemberDecorate Offset
                offsets.computeIfAbsent(raygen.getInt(i+4),k->new java.util.HashMap<>()).put(raygen.getInt(i+8),raygen.getInt(i+16));
            i+=count*4;
        }
        assertTrue(offsets.values().stream().anyMatch(m->m.equals(java.util.Map.of(0,0,1,16,2,32,3,48,4,64))));
        assertEquals(80,ChunkTextureSampling.PROBE_BYTES);
    }
    @Test void triangleShaderStillUsesOnlyItsOriginalDescriptors() throws Exception {
        var words=shader("primary.rgen");
        var bindings=new HashSet<Integer>();
        for(int i=20;i<words.limit();) {
            int head=words.getInt(i), count=head>>>16;
            assertTrue(count>0);
            if((head&0xffff)==71 && count==4 && words.getInt(i+8)==33) bindings.add(words.getInt(i+12));
            i+=count*4;
        }
        assertEquals(java.util.Set.of(0,1),bindings);
    }
}
