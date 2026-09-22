package dev.comfyfluffy.caustica.client.gui;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.RtVideoOptions;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Dedicated experimental SHaRC settings sub-screen, opened from the main Ray Tracing Settings hub by
 * the "SHaRC Settings..." button (the same nested-sub-screen pattern Video Settings uses to open Ray
 * Tracing Settings).
 *
 * <p>Built on {@link RtSettingsSubScreen}, so its top row is the page-local "Reset to Defaults" button
 * covering every SHaRC knob. Below it: the experimental enable toggle, then all the cache tuning rows,
 * then the button that clears the radiance cache itself. Every control writes straight into
 * {@link CausticaConfig.Rt.Sharc} and applies on the next frame; the cache buffer is allocated/cleared
 * by {@code RtComposite.syncSharcResources}.
 */
public final class RtSharcOptionsScreen extends RtSettingsSubScreen {
    private static final Component HEADER = Component.translatable("caustica.options.sharc.header");
    private static final Component CACHE_HEADER = Component.translatable("caustica.options.sharc.cacheHeader");

    public RtSharcOptionsScreen(Screen parent, Options options) {
        super(parent, options, Component.translatable("caustica.options.sharc.title"),
                resettableSettings(),
                // Reopening needs the options again; the SHaRC screen is always opened in-game, so the
                // client singleton's options are the same instance the hub passed in.
                parent2 -> new RtSharcOptionsScreen(parent2, Minecraft.getInstance().options),
                reopen -> List.of(
                        Section.of(HEADER, new net.minecraft.client.OptionInstance<?>[0],
                                RtVideoOptions.sharcToggleButton(reopen)),
                        Section.of(CACHE_HEADER, RtVideoOptions.sharcOptions()),
                        Section.buttons(RtVideoOptions.sharcResetButton())));
    }

    /** Every SHaRC tuning setting, driving both this page's reset and the hub's global reset. */
    public static List<CausticaConfig.RuntimeSetting<?>> resettableSettings() {
        return List.of(
                CausticaConfig.Rt.Sharc.ENABLED,
                CausticaConfig.Rt.Sharc.CELL_SIZE,
                CausticaConfig.Rt.Sharc.CACHE_ENTRIES,
                CausticaConfig.Rt.Sharc.UPDATE_COVERAGE,
                CausticaConfig.Rt.Sharc.TEMPORAL_BLEND,
                CausticaConfig.Rt.Sharc.START_BOUNCE,
                CausticaConfig.Rt.Sharc.STRENGTH,
                CausticaConfig.Rt.Sharc.MAX_DISTANCE,
                CausticaConfig.Rt.Sharc.FRAME_LIFETIME,
                CausticaConfig.Rt.Sharc.NORMAL_THRESHOLD,
                CausticaConfig.Rt.Sharc.STABLE_FRAMES,
                CausticaConfig.Rt.Sharc.DEBUG);
    }
}
