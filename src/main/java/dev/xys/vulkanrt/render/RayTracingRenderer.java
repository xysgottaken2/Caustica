package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.xys.vulkanrt.geometry.ChunkCoordinates;
import dev.xys.vulkanrt.geometry.TerrainDrawCapture;
import net.minecraft.client.renderer.LevelRenderer;
import dev.xys.vulkanrt.geometry.TriangleMesh;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_ERROR_DEVICE_LOST;
import static org.lwjgl.vulkan.KHRSynchronization2.*;

/** RT color replaces the world AFTER post effects, BEFORE GUI. Vanilla owns presentation/depth.
 * Triangle bring-up is independent of the chunk camera/projection. RUNTIME NOT VERIFIED. */
public final class RayTracingRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("native_vulkan_rt");
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();
    private static boolean projectionCaptured, failed, frameLogged, passLogged, traceLogged, submitLogged;
    private static boolean tracePending;
    private static String lastGate, stage = "startup";
    private static volatile boolean resetRequested;
    private static VulkanRayTracingContext context;
    private static RayTracingPipeline pipeline;
    private static RtOutputImage output;
    private static TriangleReadback proof;
    private static RayTracingPipeline.Bindings bindings;
    private static AccelerationStructureManager.Structure boundTlas, testBlas, testTlas;
    private static WorldGeometryManager world;
    private static WorldGeometryManager.Prepared chunkScene;
    private static ChunkMaterialTable boundMaterials;
    private static TerrainAtlasCapture.Atlas boundAtlas;

    /** Called when RenderSystem has installed Minecraft's actual GpuDevice, not from bootstrap. */
    public static void deviceReady() {
        if (!RtOptions.ENABLED || failed || context != null) return;
        stage = "RenderSystem.initRenderer / borrow actual device";
        LOG.info("[RT] {}", stage);
        try { context = VulkanRayTracingContext.borrow(); }
        catch (VulkanRayTracingContext.VulkanFailure failure) { handleFailure(failure); }
        catch (RuntimeException failure) { disable(failure); }
    }

    private static EntityGeometryManager entities;
    private static ParticleGeometryManager particles;
    private static java.util.List<TerrainAtlasCapture.Atlas> boundEntityTextures=java.util.List.of();
    private static TerrainAtlasCapture.Atlas boundParticleTexture;
    private static GpuBuffer boundEntityHud;
    public static void uploadEntities(java.util.List<dev.xys.vulkanrt.geometry.EntityCapture.Upload> uploads) {
        if(!chunkCaptureEnabled() || context==null) return;
        stage="entity staging GPU capture / local BLAS";
        try { if(entities==null) entities=new EntityGeometryManager(context);entities.upload(uploads); }
        catch(VulkanRayTracingContext.VulkanFailure failure) { handleFailure(failure); }
        catch(RuntimeException failure) { disable(failure); }
    }
    public static void uploadParticles(java.util.List<dev.xys.vulkanrt.geometry.ParticleCapture.Upload> uploads) {
        if(!chunkCaptureEnabled() || context==null) return;
        stage="particle staging GPU capture / shared-scene BLAS";
        try { if(particles==null) particles=new ParticleGeometryManager(context);particles.upload(uploads); }
        catch(VulkanRayTracingContext.VulkanFailure failure) { handleFailure(failure); }
        catch(RuntimeException failure) { disable(failure); }
    }
    public static void beginFrame() {
        if (!RtOptions.ENABLED) return;
        if (!frameLogged) { frameLogged = true; LOG.info("[RT] GameRenderer.render frame hook reached"); }
        projectionCaptured = false;
        if (RtOptions.CHUNKS) { chunkScene = null; TerrainDrawCapture.beginFrame(); TerrainAtlasCapture.beginFrame(); dev.xys.vulkanrt.geometry.EntityCapture.beginFrame(); dev.xys.vulkanrt.geometry.ParticleCapture.beginFrame(); if(entities!=null) entities.beginFrame(); if(particles!=null) particles.beginFrame(); if(world!=null) world.beginFrame(); }
        if (resetRequested) { resetRequested = false; retireScene(); }
        if (!failed) {
            if (context == null) deviceReady(); // explicit recovery path if initRenderer was already called
            if (proof != null) {
                stage = "triangle GPU completion / readback";
                try { proof.poll(); }
                catch (VulkanRayTracingContext.VulkanFailure failure) { handleFailure(failure); }
                catch (RuntimeException failure) { disable(failure); }
            }
        }
    }
    public static boolean chunkCaptureEnabled() { return RtOptions.ENABLED && RtOptions.CHUNKS && !failed; }
    public static void chunkCaptureFailed(RuntimeException failure) {
        stage = "terrain draw capture";
        if (failure instanceof VulkanRayTracingContext.VulkanFailure vk) handleFailure(vk);
        else disable(failure);
    }

    /** Called by actual terrain draw extraction RETURN, BEFORE later compile/upload can reuse a heap range. */
    public static void prepareChunkGeometry(LevelRenderer level, GameRenderer renderer) {
        if (!RtOptions.ENABLED || !RtOptions.CHUNKS || failed) return;
        if (context == null) deviceReady();
        if (failed || context == null) return;
        stage = "capture actual terrain draw / prepare chunk geometry before later uploads";
        var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
        var dispatcher = level.sectionRenderDispatcher();
        if (dispatcher != null) dispatcher.lock();
        try {
            if (world == null) world = new WorldGeometryManager(context, camera.pos.x, camera.pos.y, camera.pos.z);
            var reused = world.reuseUnchangedDraws(level,camera.pos.x,camera.pos.y,camera.pos.z);
            if (reused != null) { reused.commit(); chunkScene = reused; return; }
            try (CommandBatch batch = new CommandBatch(context)) {
                var prepared = world.prepare(batch, level, camera.pos.x, camera.pos.y, camera.pos.z);
                // WAR: the borrowed Uber range may be recycled by the later vanilla uploads.
                // Queue order alone does not prevent a subsequent transfer write overtaking our read.
                VulkanRayTracingContext.memoryBarrier(batch.commands,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR);
                batch.commit(); // source copy + BLAS/TLAS are ordered before subsequent vanilla uploads
                if ((boundTlas != prepared.tlas || boundMaterials != prepared.materials) && bindings != null) {
                    context.retire(bindings); bindings = null; boundTlas = null;
                }
                prepared.commit();
                chunkScene = prepared;
            }
        } catch (VulkanRayTracingContext.VulkanFailure failure) { handleFailure(failure); }
        catch (RuntimeException failure) { disable(failure); }
        finally { if (dispatcher != null) dispatcher.unlock(); }
    }

    public static void captureProjection(Matrix4fc projection) { PROJECTION.set(projection); projectionCaptured = true; }
    public static void worldChanged() { resetRequested = true; }

    private static void waiting(String reason) {
        if (!reason.equals(lastGate)) { lastGate = reason; LOG.info("[RT] Pass waiting: {}", reason); }
    }

    static String blockedReason(boolean chunks, boolean hasWorld, boolean projection, boolean camera, int width, int height) {
        if (!hasWorld) return "enter a world; menu GUI would cover the diagnostic image";
        if (chunks && (!projection || !camera)) return "chunks require a captured projection and initialized world camera";
        if (width < 4 || height < 4) return "main target is minimized or smaller than 4x4";
        return null;
    }

    public static void render(GameRenderer renderer) {
        if (!RtOptions.ENABLED) return;
        if (!passLogged) { passLogged = true; LOG.info("[RT] Post-world/pre-GUI RT pass hook reached"); }
        if (failed) return; // failure stage and exception have already been logged
        if (context == null) deviceReady();
        if (failed || context == null) return;
        var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
        var target = renderer.mainRenderTarget();
        String blocked = blockedReason(RtOptions.CHUNKS, renderer.gameRenderState().shouldRenderLevel,
                projectionCaptured, camera.initialized, target.width, target.height);
        if (blocked != null) { waiting(blocked); return; }
        try {
            stage = "acquire Minecraft main color target";
            if (!(target.getColorTexture() instanceof VulkanGpuTexture color))
                throw new IllegalStateException("Main color target is not VulkanGpuTexture: " + target.getColorTexture());
            if (lastGate != null) { LOG.info("[RT] Pass resumed; main target={}x{}", target.width, target.height); lastGate = null; }
            if (RtOptions.CHUNKS && chunkScene == null) {
                waiting("chunks have no completed terrain extraction callback: " + TerrainDrawCapture.failure()); return;
            }
            var atlas = RtOptions.CHUNKS ? TerrainAtlasCapture.current() : null;
            if (RtOptions.CHUNKS && atlas == null && world.hasTerrain()) { waiting("SOLID Sampler0 atlas capture absent/closed this frame"); return; }
            stage = "allocate vanilla transient RT command buffer";
            try (CommandBatch batch = new CommandBatch(context)) {
                stage = "create/validate RT output and Minecraft blit target";
                RtOutputImage nextOutput = output;
                if (nextOutput == null || nextOutput.width != target.width || nextOutput.height != target.height) {
                    nextOutput = batch.own(new RtOutputImage(context, target.width, target.height));
                    LOG.info("[RT] Storage image created: {}x{}; destination Minecraft image=0x{}", target.width, target.height, Long.toHexString(color.vkImage()));
                }
                nextOutput.validateTarget(color);
                if (RtOptions.CHUNKS) {
                    var window=renderer.gameRenderState().windowRenderState;
                    TextureQualityDiagnostics.output(nextOutput.width,nextOutput.height,target.width,target.height,
                            color.getWidth(0),color.getHeight(0),window.width,window.height);
                }
                WorldGeometryManager.Prepared prepared = null;
                var nextTestBlas = testBlas;
                var nextTestTlas = testTlas;
                AccelerationStructureManager.Structure nextTlas;
                if (RtOptions.CHUNKS) {
                    stage = "prepare chunk acceleration structures";
                    if(entities==null) entities=new EntityGeometryManager(context);
                    var entityFrame=entities.frame(batch,chunkScene.anchor,camera.pos.x,camera.pos.y,camera.pos.z);
                    var particleFrame=particles==null ? new ParticleGeometryManager.Frame(java.util.List.of(),java.util.List.of(),null,0,0,0,0)
                            : particles.frame(chunkScene.anchor,camera.pos.x,camera.pos.y,camera.pos.z);
                    // Entity-only sky/void views must not require an opaque terrain draw. This fills
                    // an unused descriptor; active entity materials still select their original textures.
                    if(atlas==null && !entityFrame.textures().isEmpty()) atlas=entityFrame.textures().getFirst();
                    if(atlas==null && particleFrame.texture()!=null) atlas=particleFrame.texture();
                    prepared = world.compose(batch,entityFrame,particleFrame); nextTlas = prepared.tlas;
                    ChunkCoordinates.inverse(INVERSE, PROJECTION, camera.viewRotationMatrix, prepared.anchor, camera.pos.x, camera.pos.y, camera.pos.z);
                } else {
                    if (nextTestTlas == null) {
                        var mesh = TriangleMesh.testTriangle();
                        var manager = new AccelerationStructureManager(context);
                        stage = "test BLAS allocation/build";
                        LOG.info("[RT] Creating BLAS...");
                        nextTestBlas = batch.own(manager.buildBlas(batch, mesh.positions(), mesh.indices()));
                        LOG.info("[RT] BLAS created: 0x{}; GPU build recorded (not completed yet)", Long.toHexString(nextTestBlas.handle()));
                        stage = "test TLAS allocation/build";
                        LOG.info("[RT] Creating TLAS...");
                        nextTestTlas = batch.own(manager.buildTlas(batch, List.of(new AccelerationStructureManager.Instance(nextTestBlas, 0, 0, 0, 0))));
                        LOG.info("[RT] TLAS created: 0x{}; GPU build recorded (not completed yet)", Long.toHexString(nextTestTlas.handle()));
                    }
                    nextTlas = nextTestTlas; INVERSE.identity();
                }
                stage = "ray tracing pipeline / shader modules / SBT";
                if (pipeline == null) pipeline = new RayTracingPipeline(context);
                stage = "descriptor allocation/update";
                var nextBindings = bindings;
                if (nextTlas == null) nextBindings = null;
                else if (nextBindings == null || boundTlas != nextTlas || nextOutput != output
                        || (RtOptions.CHUNKS && (boundMaterials != prepared.materials || !atlas.equals(boundAtlas) || !prepared.entities.textures().equals(boundEntityTextures) || prepared.entities.hud()!=boundEntityHud || prepared.particles.texture()!=boundParticleTexture)))
                    nextBindings = batch.own(RtOptions.CHUNKS ? pipeline.bind(nextTlas,nextOutput,prepared.materials,atlas,prepared.entities,prepared.particles.texture()) : pipeline.bind(nextTlas,nextOutput));
                TriangleReadback nextProof = proof;
                boolean recordProof = !RtOptions.CHUNKS && nextProof == null;
                if (recordProof) {
                    stage = "create one-shot triangle proof buffer and vanilla fence";
                    nextProof = batch.own(new TriangleReadback(context, target.width, target.height));
                }
                if (nextTlas != null) {
                    stage = "record vkCmdTraceRaysKHR";
                    nextOutput.beginTrace(batch.commands);
                    pipeline.trace(batch.commands, nextBindings, INVERSE,
                            RtOptions.CHUNKS ? prepared.anchor.cameraX(camera.pos.x) : 0,
                            RtOptions.CHUNKS ? prepared.anchor.cameraY(camera.pos.y) : 0,
                            RtOptions.CHUNKS ? prepared.anchor.cameraZ(camera.pos.z) : 2,
                            target.width, target.height, !RtOptions.CHUNKS);
                    stage = "record RT image blit to Minecraft main target";
                    nextOutput.copyToMainTarget(batch.commands, color, recordProof ? nextProof : null);
                }
                stage = "end/enqueue RT command buffer in Minecraft encoder";
                batch.commit();
                // Transfer persistent ownership immediately after successful enqueue.
                var oldBindings = bindings; var oldOutput = output;
                output = nextOutput; bindings = nextBindings; boundTlas = nextTlas;
                if (RtOptions.CHUNKS) { prepared.commit();boundMaterials = prepared.materials; boundAtlas = atlas;boundEntityTextures=prepared.entities.textures();boundEntityHud=prepared.entities.hud();boundParticleTexture=prepared.particles.texture(); }
                testBlas = nextTestBlas; testTlas = nextTestTlas; proof = nextProof;
                if (oldBindings != null && oldBindings != nextBindings) context.retire(oldBindings);
                if (oldOutput != null && oldOutput != nextOutput) context.retire(oldOutput);
                if (nextTlas != null) tracePending = true;
                if (nextTlas != null && !traceLogged) {
                    traceLogged = true;
                    LOG.info("[RT] vkCmdTraceRaysKHR + main-target blit recorded and queued; awaiting vanilla queue submission ({})", RtOptions.CHUNKS ? "shared terrain + entities" : "test triangle");
                }
            }
        } catch (VulkanRayTracingContext.VulkanFailure failure) { handleFailure(failure); }
        catch (RuntimeException failure) { disable(failure); }
    }

    /** RETURN of the real encoder.submit, after Submission.close has submitted on the graphics queue. */
    public static void afterSubmit(VulkanCommandEncoder encoder) {
        if (!tracePending || context == null || context.encoder() != encoder) return;
        tracePending = false;
        if (!submitLogged) {
            submitLogged = true;
            LOG.info("[RT] vkCmdTraceRaysKHR submitted through Minecraft graphics queue; GPU completion pending");
        }
    }

    private static void handleFailure(VulkanRayTracingContext.VulkanFailure failure) {
        if (failure.result == VK_ERROR_DEVICE_LOST) throw failure;
        disable(failure);
    }
    private static void disable(RuntimeException failure) {
        // Vanilla command allocation/fence polling translates VK_ERROR_DEVICE_LOST to this type.
        // Never swallow it as an optional RT failure on the shared Minecraft device.
        if (failure instanceof GpuDeviceLossException) {
            LOG.error("[RT] Shared Vulkan device lost at '{}'; vanilla fallback is not safe", stage, failure);
            throw failure;
        }
        failed = true;
        LOG.error("[RT] Integration failed at '{}'; RT disabled, retaining vanilla renderer. RUNTIME NOT VERIFIED", stage, failure);
        retireScene();
        if (context != null && pipeline != null) { context.retire(pipeline); pipeline = null; }
    }
    private static void retireScene() {
        if (context != null) {
            if (bindings != null) context.retire(bindings);
            if (proof != null) context.retire(proof);
            if (world != null) context.retire(world);
            if (entities != null) context.retire(entities);
            if (particles != null) context.retire(particles);
            if (testTlas != null) context.retire(testTlas);
            if (testBlas != null) context.retire(testBlas);
            if (output != null) context.retire(output);
        }
        entities=null; particles=null; boundEntityTextures=java.util.List.of();boundEntityHud=null;boundParticleTexture=null;
        bindings = null; boundTlas = null; boundMaterials = null; boundAtlas = null; world = null; chunkScene = null; testTlas = null; testBlas = null; output = null; proof = null;
        traceLogged = false; tracePending = false; submitLogged = false;
    }
    public static void deviceClosing(VulkanDevice backend) {
        if (context != null && context.backend() == backend) {
            retireScene();
            if (pipeline != null) { context.retire(pipeline); pipeline = null; }
            context = null;
        }
        failed = false; lastGate = null;
    }
    private RayTracingRenderer() {}
}
