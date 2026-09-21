package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.xys.vulkanrt.geometry.ChunkCoordinates;
import dev.xys.vulkanrt.geometry.ParticleCapture;
import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import java.util.*;

/**
 * Particle-only residency and material cache. It shares the acceleration/TLAS
 * infrastructure with terrain and entities but never their ownership maps.
 *
 * 26.3 emits camera-facing particle quads into a transient PARTICLE draw. The
 * emitted positions already contain vanilla billboard orientation and the
 * particle position relative to the camera, so this manager preserves that
 * geometry and applies only camera-to-scene-anchor translation in the TLAS.
 * It intentionally does not multiply the camera view a second time.
 */
public final class ParticleGeometryManager implements AutoCloseable {
    public static final int PARTICLE_TEXTURES = 1;

    public record ParticleMaterial(int texture, float cutoff, boolean blockAtlas) {}
    public record Frame(List<AccelerationStructureManager.Instance> instances,
                        List<ChunkMaterialTable.Entry> materials,
                        TerrainAtlasCapture.Atlas texture, int built, int removed,
                        int vertices, int triangles) {}

    private final VulkanRayTracingContext context;
    private final AccelerationStructureManager acceleration;
    private final List<AccelerationStructureManager.Structure> active = new ArrayList<>();
    private final List<ParticleCapture.Upload> receipts = new ArrayList<>();
    private int built;
    private int removed;
    private int vertices;
    private long lastReport;

    public ParticleGeometryManager(VulkanRayTracingContext context) {
        this.context = context;
        this.acceleration = new AccelerationStructureManager(context);
    }

    /** Called before the next vanilla frame. Destruction is fence-deferred by the shared context. */
    public void beginFrame() {
        for (var blas : active) context.retire(blas);
        removed += active.size();
        active.clear();
        receipts.clear();
        built = 0;
        vertices = 0;
    }

    public void upload(List<ParticleCapture.Upload> uploads) {
        if (uploads.isEmpty()) return;
        try (CommandBatch batch = new CommandBatch(context)) {
            for (var upload : uploads) {
                // The render type is resolved by PreparedRenderType.draw. Build
                // the shared geometry through the non-opaque path until then;
                // the material row still selects OPAQUE/CUTOUT/TRANSLUCENT and
                // the any-hit shader exits immediately for OPAQUE.
                var blas = acceleration.buildCapturedBlas(batch, upload.source(), upload.offset(),
                        upload.layout(), true, null, null);
                active.add(blas);
                receipts.add(upload);
                built++;
                vertices += upload.layout().vertexCount();
            }
            // The source is the vanilla staging allocation. This barrier is
            // ordered on the same Minecraft graphics queue as the vanilla copy.
            VulkanRayTracingContext.memoryBarrier(batch.commands,
                    org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                    org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                    org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,
                    org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_MEMORY_WRITE_BIT_KHR);
            batch.commit();
        }
        report();
    }

    public Frame frame(ChunkCoordinates.Anchor anchor, double cameraX, double cameraY, double cameraZ) {
        var instances = new ArrayList<AccelerationStructureManager.Instance>();
        var materials = new ArrayList<ChunkMaterialTable.Entry>();
        TerrainAtlasCapture.Atlas particleTexture = null;
        TerrainAtlasCapture.Atlas terrain = TerrainAtlasCapture.current();
        int i = 0;
        int triangles = 0;
        for (var receipt : receipts) {
            if (i >= active.size()) break;
            var blas = active.get(i++);
            if (receipt.material() == null) continue;
            var source = sampler(receipt.material(), "Sampler0");
            if (source == null || !source.live()) continue;
            boolean blockAtlas = terrain != null && source.view() == terrain.view();
            if (!blockAtlas) {
                if (particleTexture == null) particleTexture = source;
                else if (particleTexture.view() != source.view() || particleTexture.sampler() != source.sampler()) {
                    // A single binding is deliberate: vanilla's standard
                    // particle sheets share the particles atlas. Unknown custom
                    // sheets fail closed instead of sampling the wrong texture.
                    continue;
                }
            }
            var pipeline = receipt.material().pipeline();
            int materialFlags = flags(pipeline) | ChunkMaterialTable.PARTICLE;
            var transform = new org.joml.Matrix4f().translation(
                    anchor.cameraX(cameraX), anchor.cameraY(cameraY), anchor.cameraZ(cameraZ));
            instances.add(new AccelerationStructureManager.Instance(blas, transform,
                    (materialFlags & ChunkMaterialTable.TRANSLUCENT) != 0 ? 2 : 1));
            materials.add(new ChunkMaterialTable.Entry(0, blas.vertexAddress(), receipt.layout(), null,
                    materialFlags, 0, 0,
                    null, new ParticleMaterial(0, cutoff(pipeline), blockAtlas)));
            triangles += receipt.layout().triangles();
        }
        reportFrame(instances.size(), vertices, triangles, particleTexture);
        return new Frame(List.copyOf(instances), List.copyOf(materials), particleTexture,
                built, removed, vertices, triangles);
    }

    private static int flags(RenderPipeline pipeline) {
        boolean blend = pipeline.getColorTargetStates().getFirst().blendFunction()
                .filter(BlendFunction.TRANSLUCENT::equals).isPresent();
        float cutoff = cutoff(pipeline);
        int layer = blend ? ChunkMaterialTable.TRANSLUCENT
                : cutoff > 0.0f ? ChunkMaterialTable.CUTOUT : 0;
        return layer | (pipeline.isCull() ? ChunkMaterialTable.CULL_BACK : 0);
    }

    static float cutoff(RenderPipeline pipeline) {
        return Float.parseFloat(pipeline.getShaderDefines().values().getOrDefault("ALPHA_CUTOUT", "0"));
    }

    private static TerrainAtlasCapture.Atlas sampler(PreparedRenderType material, String name) {
        for (var texture : material.textures()) {
            if (texture.name().equals(name)
                    && texture.textureView() instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView view
                    && texture.sampler() instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler sampler) {
                var atlas = new TerrainAtlasCapture.Atlas(view, sampler);
                if (atlas.live()) return atlas;
            }
        }
        return null;
    }

    private void report() {
        long now = System.nanoTime();
        if (now - lastReport < 2_000_000_000L) return;
        lastReport = now;
        org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info(
                "[RT][particles] BLAS built={} retiredDeferred={} vertices={} geometry=vanilla PARTICLE staging; no CPU readback/retessellation",
                built, removed, vertices);
    }

    private void reportFrame(int instances, int vertexCount, int triangles,
                             TerrainAtlasCapture.Atlas particleTexture) {
        if (instances == 0 && particleTexture == null) return;
        org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").debug(
                "[RT][particles] frame instances={} vertices={} triangles={} texture={} transform=camera-anchor; CPU enqueue only, GPU hit HUD remains authoritative",
                instances, vertexCount, triangles,
                particleTexture == null ? "terrain-atlas-or-none" : particleTexture.view().texture().getLabel());
    }

    @Override public void close() {
        for (var blas : active) blas.close();
        active.clear();
        receipts.clear();
    }
}
