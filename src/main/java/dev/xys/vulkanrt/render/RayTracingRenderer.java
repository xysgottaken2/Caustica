package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.xys.vulkanrt.geometry.GeometryInbox;
import dev.xys.vulkanrt.geometry.TriangleMesh;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_ERROR_DEVICE_LOST;

/** Connected, opt-in visibility renderer. RUNTIME VERIFIED: NO.
 * Vanilla world rendering is NOT cancelled: this bring-up pass overwrites color before the 3D HUD,
 * retains vanilla depth and leaves SDL/swapchain/present alone. Not a finished world/material renderer. */
public final class RayTracingRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("native_vulkan_rt");
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();
    private static boolean projectionCaptured, failed, unavailableLogged, traceLogged;
    private static volatile boolean resetRequested;
    private static VulkanRayTracingContext context;
    private static RayTracingPipeline pipeline;
    private static RtOutputImage output;
    private static RayTracingPipeline.Bindings bindings;
    private static AccelerationStructureManager.Structure boundTlas, testBlas, testTlas;
    private static WorldGeometryManager world;

    public static void beginFrame() {
        projectionCaptured = false;
        if (resetRequested) { resetRequested = false; retireScene(); }
    }
    public static void captureProjection(Matrix4fc projection) { PROJECTION.set(projection); projectionCaptured = true; }
    public static void worldChanged() { GeometryInbox.reset(); resetRequested = true; }

    public static void render(GameRenderer renderer) {
        if (!RtOptions.ENABLED || failed || !projectionCaptured) return;
        var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
        if (!camera.initialized) return;
        var target = renderer.mainRenderTarget();
        if (target.width <= 0 || target.height <= 0 || !(target.getColorTexture() instanceof VulkanGpuTexture color)) return;
        try {
            if (context == null) context = VulkanRayTracingContext.borrow();
            if (context == null) {
                if (!unavailableLogged) { unavailableLogged = true; LOG.warn("[RT] Enabled Vulkan RT device unavailable; keeping vanilla renderer"); }
                return;
            }
            if (pipeline == null) pipeline = new RayTracingPipeline(context);
            if (RtOptions.CHUNKS && world == null) world = new WorldGeometryManager(context, camera.pos.x, camera.pos.y, camera.pos.z);
            try (CommandBatch batch = new CommandBatch(context)) {
                RtOutputImage nextOutput = output;
                if (nextOutput == null || nextOutput.width != target.width || nextOutput.height != target.height)
                    nextOutput = batch.own(new RtOutputImage(context, target.width, target.height));
                nextOutput.validateTarget(color);
                WorldGeometryManager.Prepared prepared = null;
                var nextTestBlas = testBlas;
                var nextTestTlas = testTlas;
                AccelerationStructureManager.Structure nextTlas;
                if (RtOptions.CHUNKS) {
                    prepared = world.prepare(batch); nextTlas = prepared.tlas;
                    float x = (float)(camera.pos.x - world.anchorX), y = (float)(camera.pos.y - world.anchorY), z = (float)(camera.pos.z - world.anchorZ);
                    INVERSE.set(PROJECTION).mul(camera.viewRotationMatrix).translate(-x, -y, -z).invert();
                } else {
                    if (nextTestTlas == null) {
                        var mesh = TriangleMesh.testTriangle();
                        var manager = new AccelerationStructureManager(context);
                        nextTestBlas = batch.own(manager.buildBlas(batch, mesh.positions(), mesh.indices()));
                        nextTestTlas = batch.own(manager.buildTlas(batch, List.of(new AccelerationStructureManager.Instance(nextTestBlas, 0, 0, 0, 0))));
                        LOG.info("[RT] Recorded test BLAS and TLAS builds");
                    }
                    nextTlas = nextTestTlas; INVERSE.identity();
                }
                var nextBindings = bindings;
                if (nextTlas == null) nextBindings = null;
                else if (nextBindings == null || boundTlas != nextTlas || nextOutput != output)
                    nextBindings = batch.own(pipeline.bind(nextTlas, nextOutput));
                if (nextTlas != null) {
                    nextOutput.beginTrace(batch.commands);
                    pipeline.trace(batch.commands, nextBindings, INVERSE,
                            RtOptions.CHUNKS ? (float)(camera.pos.x - world.anchorX) : 0,
                            RtOptions.CHUNKS ? (float)(camera.pos.y - world.anchorY) : 0,
                            RtOptions.CHUNKS ? (float)(camera.pos.z - world.anchorZ) : 2,
                            target.width, target.height, !RtOptions.CHUNKS);
                    nextOutput.copyToMainTarget(batch.commands, color);
                }
                batch.commit();
                // Old descriptor sets must retire before the images / TLAS they reference.
                if (bindings != null && bindings != nextBindings) context.retire(bindings);
                if (output != null && output != nextOutput) context.retire(output);
                if (prepared != null) prepared.commit();
                output = nextOutput; bindings = nextBindings; boundTlas = nextTlas;
                testBlas = nextTestBlas; testTlas = nextTestTlas;
                if (nextTlas != null && !traceLogged) {
                    traceLogged = true;
                    LOG.info("[RT] vkCmdTraceRaysKHR recorded and queued through Minecraft's Vulkan encoder ({})", RtOptions.CHUNKS ? "opaque chunks" : "test triangle");
                    LOG.info("[RT] Visibility debug output; vanilla depth retained. RUNTIME VERIFIED: NO (GPU completion/image not certified)");
                }
            }
        } catch (VulkanRayTracingContext.VulkanFailure failure) {
            if (failure.result == VK_ERROR_DEVICE_LOST) throw failure; // A shared lost device cannot safely run vanilla.
            disable(failure);
        } catch (RuntimeException failure) { disable(failure); }
    }

    private static void disable(RuntimeException failure) {
        failed = true;
        LOG.error("[RT] RT initialization/recording failed; keeping vanilla Vulkan renderer", failure);
        retireScene();
        if (context != null && pipeline != null) { context.retire(pipeline); pipeline = null; }
    }
    private static void retireScene() {
        if (context != null) {
            if (bindings != null) context.retire(bindings);
            if (world != null) context.retire(world);
            if (testTlas != null) context.retire(testTlas);
            if (testBlas != null) context.retire(testBlas);
            if (output != null) context.retire(output);
        }
        bindings = null; boundTlas = null; world = null; testTlas = null; testBlas = null; output = null; traceLogged = false;
    }
    public static void deviceClosing(VulkanDevice backend) {
        if (context != null && context.backend() == backend) {
            retireScene();
            if (pipeline != null) { context.retire(pipeline); pipeline = null; }
            // Vanilla encoder.destroy waits for the queue, drains retirements, then destroys the device.
            context = null;
        }
        failed = false; unavailableLogged = false;
    }
    private RayTracingRenderer() {}
}
