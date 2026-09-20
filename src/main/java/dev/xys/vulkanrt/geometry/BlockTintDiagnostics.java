package dev.xys.vulkanrt.geometry;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.xys.vulkanrt.render.RtOptions;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional, bounded inspection of EXISTING prepared quads before vertex upload. NOT GPU readback. */
public final class BlockTintDiagnostics {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("native_vulkan_rt");
    private static final BlockPos PIN=readPin();
    private static final AtomicInteger COUNT=new AtomicInteger();
    private static BlockPos readPin() {
        String value=System.getProperty("nativevulkanrt.tintProbeBlock","");
        if(value.isBlank()) return null;
        try {
            var xyz=value.split(",",-1);
            if(xyz.length!=3) throw new IllegalArgumentException("Expected x,y,z");
            return new BlockPos(Integer.parseInt(xyz[0].strip()),Integer.parseInt(xyz[1].strip()),Integer.parseInt(xyz[2].strip()));
        } catch(RuntimeException bad) { LOG.warn("[RT][tint-source] Invalid tintProbeBlock '{}'; CPU probe disabled",value); return null; }
    }
    public static void observe(BlockPos pos,BakedQuad quad,QuadInstance instance,int vanillaTint) {
        if(!RtOptions.ENABLED || !RtOptions.CHUNKS || PIN==null || !PIN.equals(pos) || COUNT.getAndIncrement()>=64) return;
        var material=quad.materialInfo();
        var vertices=new StringBuilder();
        float minU=Float.POSITIVE_INFINITY,minV=minU,maxU=Float.NEGATIVE_INFINITY,maxV=maxU;
        for(int i=0;i<4;i++) {
            float u=UVPair.unpackU(quad.packedUV(i)),v=UVPair.unpackV(quad.packedUV(i));
            minU=Math.min(minU,u);minV=Math.min(minV,v);maxU=Math.max(maxU,u);maxV=Math.max(maxV,v);
            vertices.append(" v").append(i).append(" UV=(").append(u).append(',').append(v).append(") ColorARGB=").append(Integer.toHexString(instance.getColor(i)));
        }
        LOG.info("[RT][tint-source] CPU prepared quad, NOT GPU sample: block={} face={} layer={} sprite={} tintIndex={} vanillaTintARGB={} UVbounds=({},{})..({},{}){}",
                pos.toShortString(),quad.direction(),material.layer(),material.sprite().contents().name(),material.tintIndex(),
                material.isTinted()?Integer.toHexString(vanillaTint):"NONE",minU,minV,maxU,maxV,vertices);
    }
    private BlockTintDiagnostics() {}
}
