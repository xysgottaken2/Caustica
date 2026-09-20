package dev.xys.vulkanrt.geometry;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.xys.vulkanrt.render.RtOptions;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
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
    public static void observe(BlockPos pos,BlockState state,BakedQuad quad,QuadInstance instance,int vanillaTint) {
        if(!RtOptions.ENABLED || !RtOptions.CHUNKS || PIN==null || !PIN.equals(pos) || COUNT.getAndIncrement()>=64) return;
        var material=quad.materialInfo();
        var vertices=new StringBuilder();
        int minAlpha=255,maxAlpha=0;
        float minU=Float.POSITIVE_INFINITY,minV=minU,maxU=Float.NEGATIVE_INFINITY,maxV=maxU;
        for(int i=0;i<4;i++) {
            int alpha=instance.getColor(i)>>>24; minAlpha=Math.min(minAlpha,alpha); maxAlpha=Math.max(maxAlpha,alpha);
            float u=UVPair.unpackU(quad.packedUV(i)),v=UVPair.unpackV(quad.packedUV(i));
            minU=Math.min(minU,u);minV=Math.min(minV,v);maxU=Math.max(maxU,u);maxV=Math.max(maxV,v);
            vertices.append(" v").append(i).append(" UV=(").append(u).append(',').append(v).append(") ColorARGB=").append(Integer.toHexString(instance.getColor(i)));
        }
        LOG.info("[RT][tint-source] CPU prepared quad, NOT GPU sample: block={} state={} face={} modelLayer={} sprite={} tintIndex={} vanillaTintARGB={} vertexAlphaRange={}..{} spriteTransparency={} (metadata only; sampled alpha range in GPU HUD; compiler may force fast leaves SOLID) UVbounds=({},{})..({},{}){}",
                pos.toShortString(),state,quad.direction(),material.layer(),material.sprite().contents().name(),material.tintIndex(),
                material.isTinted()?Integer.toHexString(vanillaTint):"NONE",minAlpha,maxAlpha,material.sprite().contents().transparency(),minU,minV,maxU,maxV,vertices);
    }
    private record FluidProbe(BlockPos pos,BlockState state,net.minecraft.world.level.material.FluidState fluid,
                              net.minecraft.client.renderer.block.FluidModel model) {}
    private static final ThreadLocal<FluidProbe> FLUID=new ThreadLocal<>(); // section compilation is multithreaded
    public static boolean fluidProbeEnabled() { return RtOptions.ENABLED && RtOptions.CHUNKS && PIN!=null; }
    public static void beginFluid(BlockPos pos,BlockState state,net.minecraft.world.level.material.FluidState fluid,
                                  net.minecraft.client.renderer.block.FluidStateModelSet models) {
        if(!fluidProbeEnabled()) return;
        FLUID.remove();
        if(PIN.equals(pos) && COUNT.get()<64) FLUID.set(new FluidProbe(pos.immutable(),state,fluid,models.get(fluid)));
    }
    public static void endFluid() { if(fluidProbeEnabled()) FLUID.remove(); }
    public static void fluidVertex(float x,float y,float z,int color,float u,float v,int light) {
        if(!fluidProbeEnabled()) return;
        var probe=FLUID.get();if(probe==null || COUNT.getAndIncrement()>=64) return;
        var model=probe.model();
        // UV-containing sprite candidates, NOT a claimed GPU texture sample or pixel readback.
        var sprites=new StringBuilder();
        fluidSprite(sprites,model.stillMaterial(),u,v);fluidSprite(sprites,model.flowingMaterial(),u,v);
        if(model.overlayMaterial()!=null) fluidSprite(sprites,model.overlayMaterial(),u,v);
        LOG.info("[RT][fluid-source] CPU emitted vertex, NOT GPU sample: block={} state={} fluid={} layer={} spriteCandidates={} localPosition=({},{},{}) UV=({},{}) preparedColorARGB={} vertexAlpha={} UV2={} tintSource={}; texture/final alpha and COMPOSE/CONTINUE only in GPU HUD",
                probe.pos().toShortString(),probe.state(),probe.fluid(),model.layer(),sprites,x,y,z,u,v,Integer.toHexString(color),color>>>24,light,model.tintSource());
    }
    private static void fluidSprite(StringBuilder out,net.minecraft.client.resources.model.sprite.Material.Baked material,float u,float v) {
        var sprite=material.sprite();
        if(u>=sprite.getU0() && u<=sprite.getU1() && v>=sprite.getV0() && v<=sprite.getV1())
            out.append(sprite.contents().name()).append(" transparency=").append(sprite.transparency()).append(" forceTranslucent=").append(material.forceTranslucent()).append(';');
    }
    private BlockTintDiagnostics() {}
}
