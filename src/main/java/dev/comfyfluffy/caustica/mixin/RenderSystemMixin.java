package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import java.util.Locale;
import net.minecraft.util.TimeSource;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Selects the native Wayland video driver required for Linux HDR presentation. */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Inject(
            method = "initBackendSystem",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/sdl/SDLInit;SDL_Init(I)Z",
                    shift = At.Shift.BEFORE))
    private static void caustica$preferWaylandForHdr(CallbackInfoReturnable<TimeSource.NanoTimeSource> cir) {
        if (!CausticaConfig.Rt.Hdr.enabled() || !caustica$isLinux()) {
            return;
        }

        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        String sessionType = System.getenv("XDG_SESSION_TYPE");
        boolean waylandSession = waylandDisplay != null && !waylandDisplay.isBlank()
                || "wayland".equalsIgnoreCase(sessionType);
        if (!waylandSession) {
            CausticaMod.LOGGER.warn(
                    "HDR: no Wayland session detected; Linux HDR requires launching Minecraft in a native Wayland session");
            return;
        }

        // SDL tries X11 before Wayland when both are available; pin the video driver to Wayland for
        // HDR so the Vulkan surface can expose HDR formats. (SDL_HINT_VIDEO_DRIVER must be set before
        // SDL_Init initializes the video subsystem.)
        SDLHints.SDL_SetHint("SDL_VIDEO_DRIVER", "wayland");
        CausticaMod.LOGGER.info("HDR: selecting SDL's native Wayland backend for Linux HDR presentation");
    }

    @Inject(method = "initBackendSystem", at = @At("RETURN"))
    private static void caustica$logHdrVideoDriver(CallbackInfoReturnable<TimeSource.NanoTimeSource> cir) {
        if (!CausticaConfig.Rt.Hdr.enabled() || !caustica$isLinux()) {
            return;
        }

        String driver = SDLVideo.SDL_GetCurrentVideoDriver();
        if ("wayland".equalsIgnoreCase(driver)) {
            CausticaMod.LOGGER.info("HDR: SDL initialized with the native Wayland backend");
        } else {
            CausticaMod.LOGGER.warn(
                    "HDR: SDL initialized with the {} video driver instead of Wayland; the Vulkan surface is unlikely to expose HDR formats",
                    driver);
        }
    }

    @Unique
    private static boolean caustica$isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
