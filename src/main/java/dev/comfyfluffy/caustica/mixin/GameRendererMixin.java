package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.client.WorldRenderScaler;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtReflex;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.overlay.RtWorldOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the level-rendering section of {@link GameRenderer#render} with the
 * render-scale window: low-res textures are swapped into the main target just
 * before {@code renderLevel} (so the level frame graph, sky, entity outline and
 * post chains all run at reduced resolution) and restored + upscaled right
 * before the pre-GUI depth clear.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/** Vanilla's fixed viewmodel FOV, per {@code Camera.calculateHudFov()}. */
	private static final float CAUSTICA$VANILLA_HAND_FOV = 70.0f;

	/** The 5-arg color+depth render-pass overload the hand / screen-effect passes are built with. */
	private static final String CREATE_COLOR_DEPTH_PASS =
			"Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;";

	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	// Reset the UI overlay's per-frame clear latch at the very start of the frame (before the world, hand,
	// or GUI render into it).
	@Inject(method = "render()V", at = @At("HEAD"))
	private void caustica$beginOverlayFrame(CallbackInfo ci) {
		RtUiOverlay.beginFrame();
		// Clear the stale HDR-present flag every frame: composite() only runs while a level renders, so on
		// menu frames it would otherwise stay true from the last world frame and present a black HDR image.
		RtComposite.INSTANCE.beginFrame();
		// Reflex RENDERSUBMIT_START: render-graph recording begins here; RENDERSUBMIT_END is set at
		// VulkanGpuSurface.present() HEAD (VulkanGpuSurfaceMixin), just before the real present.
		if (RtReflex.enabled()) {
			long swapchain = RtReflex.INSTANCE.appliedSwapchain();
			if (swapchain != 0L
					&& ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
				RtReflex.INSTANCE.marker(device.vkDevice(), swapchain, RtReflex.MARKER_RENDERSUBMIT_START,
						RtReflex.INSTANCE.currentSimFrameId());
			}
		}
	}

	@Inject(method = "render()V", at = @At("TAIL"))
	private void caustica$endRtFrameStats(CallbackInfo ci) {
		RtComposite.INSTANCE.endFrame();
	}

	@Inject(method = "render()V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
	private void caustica$beginWorldScale(CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.begin(this.mainRenderTarget);
	}

	// Redirect the held-item/hand render into the combined UI overlay. SDR and HDR then feed DLSS-FG the same
	// shape: hudless excludes the screen-fixed hand, while pUI carries hand + screen effects + GUI overlays.
	// (26.3 has no render-system output overrides, so the pass's own color/depth view arguments are swapped
	// for the overlay's; no pairing call is needed, so nothing can leak if the hand render throws.)
	@ModifyArg(method = "renderItemInHand",
			at = @At(value = "INVOKE", target = CREATE_COLOR_DEPTH_PASS),
			index = 1)
	private GpuTextureView caustica$redirectHandColorToOverlay(GpuTextureView colorTextureView) {
		return RtUiOverlay.redirectColor(colorTextureView);
	}

	@ModifyArg(method = "renderItemInHand",
			at = @At(value = "INVOKE", target = CREATE_COLOR_DEPTH_PASS),
			index = 3)
	private GpuTextureView caustica$redirectHandDepthToOverlay(GpuTextureView depthTextureView) {
		return RtUiOverlay.redirectDepth(depthTextureView);
	}

	// Redirect the screen-effect flush (fire, underwater, view-blocking-block overlays submitted by
	// ScreenEffectRenderer.submit) into the same combined UI overlay as the hand. This is the createRenderPass
	// call in the "screenEffects" section of render3dHud — distinct from the one inside renderItemInHand,
	// and from integrate3DHudDepth (which uses the descriptor overload and runs before the hand, untouched).
	// The spyglass scope and worn-pumpkin blur are drawn by the GUI/HUD instead, so they already reach the
	// overlay via the GuiRenderer redirect.
	@ModifyArg(method = "render3dHud",
			at = @At(value = "INVOKE", target = CREATE_COLOR_DEPTH_PASS),
			index = 1)
	private GpuTextureView caustica$redirectScreenEffectsColorToOverlay(GpuTextureView colorTextureView) {
		return RtUiOverlay.redirectColor(colorTextureView);
	}

	@ModifyArg(method = "render3dHud",
			at = @At(value = "INVOKE", target = CREATE_COLOR_DEPTH_PASS),
			index = 3)
	private GpuTextureView caustica$redirectScreenEffectsDepthToOverlay(GpuTextureView depthTextureView) {
		return RtUiOverlay.redirectDepth(depthTextureView);
	}

	// Safety net only: the primary end-of-window is caustica$endWorldScaleBeforeHand
	// inside render3dHud. This catches any path where the level render bailed early
	// (end() no-ops when the window is already closed).
	@Inject(method = "render()V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V",
					shift = At.Shift.AFTER))
	private void caustica$endWorldScale(CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.endSafetyNet(this.mainRenderTarget);
	}

	// Capture the frame's camera for the RT composite at the exact point the level projection is built
	// (this projection already includes view bobbing, exactly as rendered). The RT path jitters the
	// primary ray in the shader, so the projection matrix itself is left unmodified — we only read it.
	@ModifyArg(method = "renderLevel",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
			index = 0)
	private Matrix4f caustica$captureLevelProjection(Matrix4f projection) {
		if (!VanillaRenderController.rtRuntimeWorkRequested()) {
			return projection;
		}

		var cameraState = this.gameRenderState().levelRenderState.cameraRenderState;
		RtComposite.INSTANCE.captureFrame(projection, cameraState.viewRotationMatrix,
				cameraState.pos.x, cameraState.pos.y, cameraState.pos.z);
		VanillaRenderController.INSTANCE.markProjectionCaptured();
		return projection;
	}

	// Primary end-of-window: right after the 3D-HUD projection is set and *before*
	// vanilla's pre-hand depth clear. The world (incl. entity outline targets and
	// translucency compositing) has fully rendered at low res by this point; the
	// upscale runs here, then the hand, screen effects and 3D crosshair draw at
	// native resolution on top — keeping the screen-fixed hand out of the FSR
	// inputs entirely (camera-reprojection MVs would be exactly wrong for it).
	@Inject(method = "render3dHud",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
					shift = At.Shift.AFTER))
	private void caustica$endWorldScaleBeforeHand(CameraRenderState cameraState, PlayerRenderState playerState,
			OptionsRenderState optionsState, boolean renderCrosshair, CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.end(this.mainRenderTarget);
		// Fold RT world overlays into the shared transparent UI image before hand/screen effects and the GUI
		// add their own layers. RtUiOverlay then performs the single final blend to SDR/HDR.
		try {
			RtWorldOverlay.INSTANCE.compositeIntoUiOverlay(
					this.mainRenderTarget, RtComposite.INSTANCE.currentGraphicsUse());
		} finally {
			// The block-outline ray query consumes this frame's TLAS. Signal the shared RT frame token only
			// after its transient command buffer has been placed later in the same graphics submission.
			RtComposite.INSTANCE.finishGraphicsUse();
		}
	}

	// ---- Hand FOV (viewmodel FOV) ----------------------------------------------------------------
	//
	// Vanilla draws the first-person viewmodel through a dedicated "3d hud" perspective built from
	// CameraRenderState.hudFov, which Camera.calculateHudFov() pins to a constant 70 degrees
	// (modulated only by the death/fluid factor). That deliberately isolates the arm from the FOV
	// slider so it never changes size.
	//
	// With Hand FOV enabled we rescale that single argument by fovSlider / 70 before the projection is
	// built. Because hudFov is exactly modifyFovBasedOnDeathOrFluid(70) and that helper only ever
	// multiplies/divides its input, hudFov * (slider / 70) is identical to feeding the slider value
	// through the same helper — the death and underwater/lava FOV modulation stays intact, and the
	// viewmodel now widens with a higher FOV (arm pushed away) and narrows with a lower one.
	//
	// Deliberately keyed to the FOV slider rather than Camera.getFov(): the latter also carries the
	// transient sprint/zoom multiplier (and the spyglass' ~0.1x zoom), which would make the arm pump
	// while sprinting and balloon while scoped. Legacy Minecraft, back when the hand did follow FOV,
	// likewise built the hand projection with changingFov = false.
	//
	// Hooking the setupPerspective argument (rather than the hand render itself) keeps the change in
	// exactly one place: the hand, the screen effects and the 3D crosshair all share this "3d hud"
	// projection and stay mutually consistent, matching how those overlays behaved historically. When
	// the toggle is off the argument passes through untouched and the projection is bit-identical to
	// vanilla's.
	@ModifyArg(method = "render3dHud",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V"),
			index = 2)
	private float caustica$applyHandFov(float hudFov) {
		if (!CausticaConfig.Rt.Hand.FOV_FOLLOWS_CAMERA.value()) {
			return hudFov;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.options == null) {
			return hudFov;
		}
		Integer sliderFov = minecraft.options.fov().get();
		if (sliderFov == null || sliderFov <= 0) {
			return hudFov;
		}
		// Clamped so a hostile config value can never produce a degenerate projection matrix.
		return Math.clamp(hudFov * (sliderFov / CAUSTICA$VANILLA_HAND_FOV), 1.0f, 179.0f);
	}

	// Composite the redirected UI overlay back over the world once the GUI has fully rendered into it.
	// Done here (not at GuiRenderer.draw TAIL) because that TAIL inject did not fire on in-game HUD frames;
	// this INVOKE-after seam runs unconditionally once per frame in both gameplay and menus.
	@Inject(method = "render()V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V",
					shift = At.Shift.AFTER))
	private void caustica$compositeUiOverlay(CallbackInfo ci) {
		// DLSS-FG quality: snapshot the main target before the combined UI overlay composites back below.
		// Hand/screen effects, world overlays and GUI are carried by the optional DLSSG UI resource.
		RtComposite.INSTANCE.captureFgHudless(this.mainRenderTarget);
		dev.comfyfluffy.caustica.rt.RtUiOverlay.compositeIfUsed();
	}

	@Shadow
	public abstract net.minecraft.client.renderer.state.GameRenderState gameRenderState();
}
