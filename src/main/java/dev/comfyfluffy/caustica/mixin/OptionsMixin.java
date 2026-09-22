package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's video-settings screen already shows a red "restart required" banner
 * ({@code Options.isRestartRequiredToApplyVideoSettings}) when the Graphics API or exclusive-fullscreen
 * choice differs from what was active at startup. Our HDR toggle has the exact same constraint — the
 * swapchain's pixel format is fixed at surface-creation time — so this folds it into the same check,
 * reusing vanilla's existing banner instead of building a parallel one.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @ModifyReturnValue(method = "isRestartRequiredToApplyVideoSettings", at = @At("RETURN"))
    private boolean caustica$alsoRestartForHdr(boolean original) {
        return original || CausticaConfig.Rt.Hdr.pendingRestart();
    }
}
