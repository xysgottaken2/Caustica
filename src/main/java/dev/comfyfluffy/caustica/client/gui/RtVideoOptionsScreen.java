package dev.comfyfluffy.caustica.client.gui;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.RtVideoOptions;
import dev.comfyfluffy.caustica.compat.DistantHorizonsCompat;
import dev.comfyfluffy.caustica.compat.VoxyCompat;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Ray Tracing settings hub, opened from Video Settings via the "Ray Tracing Settings..." button.
 *
 * <p>The hub is deliberately just a directory: practically everything Caustica lets you configure
 * per-frame lives in its own sub-screen — Quality, Upscaling (DLSS RR / FSR 3 / XeSS), Frame
 * Generation &amp; Latency, Lighting &amp; ReSTIR, Sky, World &amp; Entities, Effects, Water, POM,
 * Clouds, Exposure, Tonemapping, HDR, Terrain Streaming, Debug and the experimental SHaRC page — each
 * opened by one of the buttons below (see {@link RtSubScreens} for the factories).
 *
 * <p>Every sub-screen starts with a "Reset to Defaults" row covering only that page's settings, so a
 * botched experiment is never fatal; the hub itself carries the matching global "Reset All RT
 * Settings" whose list is exactly the union of every page's ({@link RtSubScreens#allResettableSettings()}).
 *
 * <p>Compat bridges (Distant Horizons / Voxy) keep their live controls here, since they are not
 * Caustica settings and have nothing to reset. Config persistence rides on removed(), as before.
 */
public class RtVideoOptionsScreen extends OptionsSubScreen {

    // Held locally (instead of relying on the superclass fields) so the global reset can reopen the
    // hub with the same parent/options after restoring the defaults.
    private final Screen rtParent;
    private final Options rtOptions;

    private static final Component TITLE = Component.translatable("caustica.options.rt.title");
    private static final Component IMAGE_HEADER = Component.translatable("caustica.options.rt.imageMenuHeader");
    private static final Component DISPLAY_HEADER = Component.translatable("caustica.options.rt.displayMenuHeader");
    private static final Component WORLD_HEADER = Component.translatable("caustica.options.rt.worldMenuHeader");
    private static final Component ADVANCED_HEADER = Component.translatable("caustica.options.rt.advancedMenuHeader");
    private static final Component DH_HEADER = Component.translatable("caustica.options.rt.dhHeader");
    private static final Component VOXY_HEADER = Component.translatable("caustica.options.voxy.header");

    public RtVideoOptionsScreen(Screen parent, Options options) {
        super(parent, options, TITLE);
        this.rtParent = parent;
        this.rtOptions = options;
    }

    @Override
    protected void addOptions() {
        if (!CausticaConfig.Rt.ENABLED.value()) {
            return;
        }
        OptionsList list = ((dev.comfyfluffy.caustica.mixin.OptionsSubScreenAccessor) (Object) this).getList();
        if (list == null) {
            return;
        }

        // The hub-level safety net for "I touched everything and forgot the defaults": restores the
        // union of every sub-screen's settings, then reopens so the hub re-reads them.
        list.addBig(resetAllButton());

        // --- Image & upscaling ---
        list.addHeader(IMAGE_HEADER);
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.qualityHeader", RtSubScreens::quality));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.upscalingHeader", RtSubScreens::upscaling));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.frameGenHeader", RtSubScreens::frameGen));
        list.addBig(RtVideoOptions.sharcSettingsButton(this));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.lightsHeader", RtSubScreens::lighting));

        // --- Display ---
        list.addHeader(DISPLAY_HEADER);
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.exposureHeader", RtSubScreens::exposure));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.tonemapHeader", RtSubScreens::tonemap));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.hdrHeader", RtSubScreens::hdr));

        // --- World ---
        list.addHeader(WORLD_HEADER);
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.skyHeader", RtSubScreens::sky));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.worldHeader", RtSubScreens::world));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.effectsHeader", RtSubScreens::effects));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.waterHeader", RtSubScreens::water));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.pomHeader", RtSubScreens::pom));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.cloudsHeader", RtSubScreens::clouds));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.fogHeader", RtSubScreens::fog));

        // --- Advanced ---
        list.addHeader(ADVANCED_HEADER);
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.streamingHeader", RtSubScreens::streaming));
        list.addBig(RtSubScreens.openButton(this, "caustica.options.rt.debugHeader", RtSubScreens::debug));

        // --- Distant Horizons (if present) ---
        if (DistantHorizonsCompat.enabled()) {
            list.addHeader(DH_HEADER);
            list.addBig(RtVideoOptions.distantHorizonsRefreshButton());
        }

        // --- Voxy LODs (if present) ---
        if (VoxyCompat.enabled()) {
            list.addHeader(VOXY_HEADER);
            list.addSmall(RtVideoOptions.voxyOptions());
            list.addBig(RtVideoOptions.voxyRefreshButton());
        }
    }

    /**
     * Global reset: restores every setting exposed anywhere in the sub-screens (the union of all
     * per-page lists, so "reset all" can never drift from what the pages can touch), persists and
     * reopens the hub so any displayed state re-reads the defaults.
     */
    private Button resetAllButton() {
        Button button = Button.builder(Component.translatable("caustica.options.rt.resetAll"), clicked -> {
            for (CausticaConfig.RuntimeSetting<?> setting : RtSubScreens.allResettableSettings()) {
                setting.resetToDefault();
            }
            CausticaConfig.save();
            this.minecraft.gui.setScreen(new RtVideoOptionsScreen(this.rtParent, this.rtOptions));
        }).width(310).build();
        button.setTooltip(Tooltip.create(Component.translatable("caustica.options.rt.resetAll.tooltip")));
        return button;
    }

    @Override
    public void removed() {
        CausticaConfig.save();
        super.removed();
    }
}
