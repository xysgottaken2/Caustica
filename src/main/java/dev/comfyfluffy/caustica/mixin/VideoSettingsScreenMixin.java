package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.CausticaConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Surfaces RT controls inside Video Settings when RT is enabled.
 * <ul>
 *   <li>The Quality section drops vanilla AO and Entity Shadows (superseded by RT).</li>
 *   <li>A single "Ray Tracing Settings..." button opens a dedicated sub-screen that contains
 *       all Caustica sliders, toggles and refresh buttons, keeping Video Settings clean.</li>
 * </ul>
 * When RT is disabled the screen is left exactly as vanilla built it.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Shadow
    private static OptionInstance<?>[] qualityOptions(Options options) {
        throw new AssertionError("mixin stub");
    }

    private static final Component CAUSTICA$RT_HEADER = Component.translatable("caustica.options.rt.header");
    private static final Component CAUSTICA$RT_SETTINGS_BUTTON = Component.translatable("caustica.options.rt.settingsButton");

    @Redirect(
        method = "addOptions",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/options/VideoSettingsScreen;qualityOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;"))
    private OptionInstance<?>[] caustica$filterQualityOptions(Options options) {
        OptionInstance<?>[] base = qualityOptions(options);
        if (!CausticaConfig.Rt.ENABLED.value()) {
            return base;
        }
        List<OptionInstance<?>> kept = new ArrayList<>(base.length);
        for (OptionInstance<?> option : base) {
            // Path-traced GI + RT shadows make these vanilla raster controls inert under RT.
            if (option == options.ambientOcclusion() || option == options.entityShadows()) {
                continue;
            }
            kept.add(option);
        }
        return kept.toArray(OptionInstance<?>[]::new);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void caustica$addRtSettingsButton(CallbackInfo ci) {
        if (!CausticaConfig.Rt.ENABLED.value()) {
            return;
        }
        OptionsList list = ((OptionsSubScreenAccessor) (Object) this).getList();
        if (list == null) {
            return;
        }
        // Trailing section with the dedicated RT screen button, plus a direct SHaRC sub-screen button
        // so the experimental cache is reachable without first opening the full RT settings.
        list.addHeader(CAUSTICA$RT_HEADER);
        list.addBig(caustica$createRtSettingsButton());
        list.addBig(caustica$createSharcSettingsButton());
    }

    private net.minecraft.client.gui.components.Button caustica$createRtSettingsButton() {
        return net.minecraft.client.gui.components.Button.builder(
                        CAUSTICA$RT_SETTINGS_BUTTON,
                        btn -> {
                            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
                            net.minecraft.client.gui.screens.Screen current = (net.minecraft.client.gui.screens.Screen) (Object) this;
                            net.minecraft.client.Options opts = minecraft.options;
                            net.minecraft.client.gui.screens.Screen rtScreen =
                                    new dev.comfyfluffy.caustica.client.gui.RtVideoOptionsScreen(current, opts);
                            // In 26.2, setScreen moved from Minecraft to Gui: Minecraft.gui.setScreen()
                            minecraft.gui.setScreen(rtScreen);
                        })
                .width(310)
                .build();
    }

    private static final Component CAUSTICA$SHARC_BUTTON =
            Component.translatable("caustica.options.sharc.settingsButton");

    private net.minecraft.client.gui.components.Button caustica$createSharcSettingsButton() {
        return net.minecraft.client.gui.components.Button.builder(
                        CAUSTICA$SHARC_BUTTON,
                        btn -> {
                            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
                            net.minecraft.client.gui.screens.Screen current = (net.minecraft.client.gui.screens.Screen) (Object) this;
                            net.minecraft.client.Options opts = minecraft.options;
                            minecraft.gui.setScreen(
                                    new dev.comfyfluffy.caustica.client.gui.RtSharcOptionsScreen(current, opts));
                        })
                .width(310)
                .build();
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void caustica$saveConfig(CallbackInfo ci) {
        // Persist any RT settings the player changed in this screen to the TOML config.
        CausticaConfig.save();
    }
}
