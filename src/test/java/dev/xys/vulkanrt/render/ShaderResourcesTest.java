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
        shader("overlay.comp");
        shader("chunks.rahit");
    }
    @Test void physicalVertexReadsDoNotRequireShaderInt64OrDescriptorIndexing() throws Exception {
        var words=shader("chunks.rchit");
        var capabilities=new HashSet<Integer>();
        var bindings=new HashSet<Integer>();
        boolean rowsStride96=false;
        for (int i=20;i<words.limit();) {
            int head=words.getInt(i), count=head>>>16, opcode=head&0xffff;
            assertTrue(count>0); assertTrue(i+count*4<=words.limit());
            if(opcode==17) capabilities.add(words.getInt(i+4)); // OpCapability
            if(opcode==71 && count==4) { // OpDecorate
                int decoration=words.getInt(i+8), value=words.getInt(i+12);
                if(decoration==33) bindings.add(value); // Binding
                if(decoration==6 && value==96) rowsStride96=true; // ArrayStride
            }
            i+=count*4;
        }
        assertTrue(capabilities.contains(5347),"PhysicalStorageBufferAddresses");
        assertFalse(capabilities.contains(11),"shaderInt64 is not enabled/required");
        assertFalse(capabilities.contains(5301),"ShaderNonUniform indexing is not enabled/required");
        assertEquals(java.util.Set.of(2,3),bindings);
        assertTrue(rowsStride96,"Material std430 row must match Java packer");
    }
    @Test void bothComparisonPathsSampleViewMipZeroAndDiagnosticProbeHasSeventeenVectors() throws Exception {
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
        assertTrue(offsets.values().stream().anyMatch(m->m.size()==17 && java.util.stream.IntStream.range(0,17).allMatch(i->java.util.Objects.equals(m.get(i),i*16))));
        assertEquals(272,ChunkTextureSampling.PROBE_BYTES);
    }
    @Test void overlayComputeIsOfflineCompiledWithBoundedInterfaceAndNoExtraDescriptors() throws Exception {
        var words=shader("overlay.comp");
        var capabilities=new HashSet<Integer>();
        boolean compute=false,atomicExchange=false,localSize=false,pushEnd=false;
        for(int i=20;i<words.limit();) {
            int head=words.getInt(i),count=head>>>16,op=head&0xffff;
            assertTrue(count>0);assertTrue(i+count*4<=words.limit());
            if(op==17) capabilities.add(words.getInt(i+4));
            if(op==15) compute=words.getInt(i+4)==5; // GLCompute
            if(op==16 && count==6 && words.getInt(i+8)==17) localSize=words.getInt(i+12)==64 && words.getInt(i+16)==1 && words.getInt(i+20)==1;
            if(op==229) atomicExchange=true;
            if(op==71 && count==4) assertNotEquals(33,words.getInt(i+8),"Only physical addresses; no new descriptor set");
            if(op==72 && count==5 && words.getInt(i+8)==12 && words.getInt(i+12)==35) pushEnd=words.getInt(i+16)==68;
            i+=count*4;
        }
        assertTrue(compute);assertTrue(atomicExchange);assertTrue(localSize);assertTrue(pushEnd);
        assertTrue(capabilities.contains(5347));assertFalse(capabilities.contains(11));
        assertEquals(72,CoplanarOverlayMapper.PUSH_BYTES);
    }
    @Test void anyHitReallySamplesAlphaAndIgnoresIntersectionsWithoutForcingOpaqueRays() throws Exception {
        var words=shader("chunks.rahit");
        var bindings=new HashSet<Integer>();var capabilities=new HashSet<Integer>();
        boolean anyHit=false,ignore=false,fetch=false,cutoff=false,transCutoff=false,stride=false;
        for(int i=20;i<words.limit();) {
            int head=words.getInt(i),count=head>>>16,op=head&0xffff;assertTrue(count>0);
            if(op==15) anyHit=words.getInt(i+4)==5315;
            assertNotEquals(4445,op,"Any-hit must not recursively trace or compose transparency");
            if(op==4448) ignore=true;
            if(op==95) fetch=true;
            if(op==17) capabilities.add(words.getInt(i+4));
            if(op==43 && count==4 && words.getInt(i+12)==Float.floatToIntBits(0.5f)) cutoff=true;
            if(op==43 && count==4 && words.getInt(i+12)==Float.floatToIntBits(0.1f)) transCutoff=true;
            if(op==71 && count==4) {
                if(words.getInt(i+8)==33) bindings.add(words.getInt(i+12));
                if(words.getInt(i+8)==6 && words.getInt(i+12)==96) stride=true;
            }
            i+=count*4;
        }
        assertTrue(anyHit);assertTrue(ignore);assertTrue(fetch);assertTrue(cutoff);assertTrue(transCutoff);assertTrue(stride);
        assertEquals(java.util.Set.of(2,3),bindings);assertFalse(capabilities.contains(11));
        var raygen=shader("chunks.rgen");var zeros=new HashSet<Integer>();var flags=new java.util.ArrayList<Integer>();
        for(int i=20;i<raygen.limit();) {
            int h=raygen.getInt(i),n=h>>>16,op=h&0xffff;assertTrue(n>0);
            if(op==43 && n==4 && raygen.getInt(i+12)==0) zeros.add(raygen.getInt(i+8));
            if(op==4445) flags.add(raygen.getInt(i+8));
            i+=n*4;
        }
        assertTrue(flags.size()>=3,"Initial, continuation and overflow-background traces must be executable SPIR-V");assertTrue(zeros.containsAll(flags),"Chunks rays must not force OPAQUE on CUTOUT");
    }
    @Test void allPayloadsMatchAndOnlyRaygenComposesInOrderedLoop() throws Exception {
        String payload=null;
        for(String stage:new String[]{"rgen","rmiss","rchit","rahit"}) {
            String source=java.nio.file.Files.readString(java.nio.file.Path.of("shaders/chunks."+stage));
            String value=source.substring(source.indexOf("struct Hit {"),source.indexOf("};")+2);
            if(payload==null) payload=value;else assertEquals(payload,value,stage);
            if(stage.equals("rgen")) {
                assertTrue(source.contains("composed+=remaining*hit.alpha*hit.color"));
                assertTrue(source.contains("remaining*=1.0-hit.alpha"));
                assertTrue(source.contains("floatBitsToUint(hit.distance)+1u"));
                assertTrue(source.contains("step<64u"));assertTrue(source.contains("0x01"));
            } else assertFalse(source.contains("composed+="));
        }
        var words=shader("chunks.rahit");
        for(int i=20;i<words.limit();) {
            int head=words.getInt(i),count=head>>>16;
            if((head&0xffff)==17) assertNotEquals(22,words.getInt(i+4),"SHORT indices do not require shaderInt16");
            i+=count*4;
        }
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
