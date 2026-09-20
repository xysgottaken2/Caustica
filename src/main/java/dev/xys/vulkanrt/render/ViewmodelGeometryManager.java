package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import dev.xys.vulkanrt.geometry.ChunkCoordinates;
import dev.xys.vulkanrt.geometry.EntityCapture;
import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import net.minecraft.client.renderer.RenderPipeline;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns BLAS snapshots made from the exact first-person staged GPU uploads. The
 * hand vertices already contain vanilla PoseStack swing/equip/bob transforms;
 * this class only copies those bytes GPU-to-GPU and places the BLAS at the
 * camera-relative origin. It deliberately uses mask bit 2 so viewmodel meshes
 * cannot block world shadow rays.
 */
public final class ViewmodelGeometryManager implements AutoCloseable {
    public record Frame(List<AccelerationStructureManager.Instance> instances,
                        List<ChunkMaterialTable.Entry> materials,
                        List<TerrainAtlasCapture.Atlas> textures) {}
    private record Piece(AccelerationStructureManager.Structure blas,
                         SectionGeometryLayout layout,
                         PreparedRenderType material,
                         TerrainAtlasCapture.Atlas texture,
                         int flags,
                         EntityGeometryManager.Material metadata) {}

    private final VulkanRayTracingContext context;
    private final AccelerationStructureManager acceleration;
    private final List<Piece> current = new ArrayList<>();
    private int built;

    public ViewmodelGeometryManager(VulkanRayTracingContext context) {
        this.context = context;
        this.acceleration = new AccelerationStructureManager(context);
    }

    public void beginFrame() {
        for (Piece piece : current) context.retire(piece.blas());
        current.clear();
        built = 0;
    }

    private static int flags(PreparedRenderType material) {
        RenderPipeline pipeline = material.pipeline();
        boolean blend = pipeline.getColorTargetStates().getFirst().blendFunction()
                .filter(BlendFunction.TRANSLUCENT::equals).isPresent();
        float cutoff = EntityGeometryManager.cutoff(pipeline);
        return ChunkMaterialTable.VIEWMODEL
                | (pipeline.isCull() ? ChunkMaterialTable.CULL_BACK : 0)
                | (blend ? ChunkMaterialTable.TRANSLUCENT : cutoff > 0.0f ? ChunkMaterialTable.CUTOUT : 0);
    }

    private static TerrainAtlasCapture.Atlas texture(PreparedRenderType material, String name) {
        for (var texture : material.textures()) {
            if (texture.name().equals(name)
                    && texture.textureView() instanceof VulkanGpuTextureView view
                    && texture.sampler() instanceof VulkanGpuSampler sampler) {
                var atlas = new TerrainAtlasCapture.Atlas(view, sampler);
                if (atlas.live()) return atlas;
            }
        }
        return null;
    }

    private static int slot(List<TerrainAtlasCapture.Atlas> textures, TerrainAtlasCapture.Atlas value) {
        if (value == null) return -1;
        int existing = textures.indexOf(value);
        if (existing >= 0) return existing;
        if (textures.size() >= EntityGeometryManager.TEXTURES) return -1;
        textures.add(value);
        return textures.size() - 1;
    }

    public void upload(List<dev.xys.vulkanrt.render.ViewmodelCapture.Upload> uploads) {
        if (uploads.isEmpty()) return;
        try (CommandBatch batch = new CommandBatch(context)) {
            for (var upload : uploads) {
                var sampler = texture(upload.material(), "Sampler0");
                if (sampler == null) continue;
                int layerFlags = flags(upload.material());
                boolean nonOpaque = (layerFlags & (ChunkMaterialTable.CUTOUT | ChunkMaterialTable.TRANSLUCENT)) != 0;
                var blas = batch.own(acceleration.buildCapturedBlas(batch, upload.source(), upload.offset(),
                        upload.layout(), nonOpaque, null, null));
                var metadata = new EntityGeometryManager.Material(
                        -1, EntityGeometryManager.cutoff(upload.material().pipeline()), 0, -1,
                        EntityCapture.Colors.uniform(0xffffffff), -1, false, -1, false);
                current.add(new Piece(blas, upload.layout(), upload.material(), sampler, layerFlags, metadata));
                built++;
            }
            VulkanRayTracingContext.memoryBarrier(batch.commands,
                    org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                    org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                    org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,
                    org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_MEMORY_WRITE_BIT_KHR);
            batch.commit();
        }
    }

    /**
     * Existing entity texture slots are passed in so the final shared material
     * table/descriptor array remains one table and one TLAS. The first-person
     * geometry is camera-relative; only its camera-to-anchor translation is
     * instanced here, not a second camera or renderer.
     */
    public Frame frame(ChunkCoordinates.Anchor anchor, double cameraX, double cameraY, double cameraZ,
                       Matrix4fc cameraViewRotation, float worldFov, float handFov,
                       List<TerrainAtlasCapture.Atlas> existingTextures) {
        var textures = new ArrayList<>(existingTextures);
        var instances = new ArrayList<AccelerationStructureManager.Instance>();
        var materials = new ArrayList<ChunkMaterialTable.Entry>();
        float handFovRadians = handFov * ((float)Math.PI / 180.0f);
        float fovScale = (Float.isFinite(worldFov) && Float.isFinite(handFovRadians) && handFovRadians > 0.001f)
                ? (float)(Math.tan(worldFov * 0.5f) / Math.tan(handFovRadians * 0.5f)) : 1.0f;
        if (!Float.isFinite(fovScale) || fovScale <= 0.0f) fovScale = 1.0f;
        // Vanilla hand vertices are camera-oriented by PoseStack.mulPose(inverse view).
        // Apply FOV compensation in that same local basis, then translate once to the
        // anchor used by the shared world TLAS.
        Matrix4f viewToWorld = new Matrix4f(cameraViewRotation).invert();
        Matrix4f cameraRelative = viewToWorld.mul(new Matrix4f().scaling(fovScale, fovScale, 1.0f)).mul(cameraViewRotation);
        Matrix4f translation = new Matrix4f().translation(anchor.cameraX(cameraX), anchor.cameraY(cameraY), anchor.cameraZ(cameraZ)).mul(cameraRelative);
        for (Piece piece : current) {
            int texture = slot(textures, piece.texture());
            if (texture < 0) continue;
            instances.add(new AccelerationStructureManager.Instance(piece.blas(), translation, 2));
            var source = piece.metadata();
            var metadata = new EntityGeometryManager.Material(texture, source.cutoff(), source.overlay(), source.record(),
                    source.colors(), source.overlayTexture(), source.mirror(), source.id(), source.falling());
            materials.add(new ChunkMaterialTable.Entry(0, piece.blas().vertexAddress(),
                    piece.layout(), null, piece.flags(), 0, 0, metadata));
        }
        return new Frame(instances, materials, textures);
    }

    public int built() { return built; }
    @Override public void close() { for (Piece piece : current) piece.blas().close(); current.clear(); }
}
