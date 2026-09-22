package dev.comfyfluffy.caustica.client.gui;

import dev.comfyfluffy.caustica.CausticaConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Generic Caustica settings sub-screen. The hub ({@link RtVideoOptionsScreen}) opens one of these per
 * feature group — Quality, Upscaling, Frame Generation, Lighting &amp; ReSTIR, Water, POM, Clouds and
 * so on — so practically everything the renderer lets you reach per-frame lives behind its own button
 * instead of one endless list.
 *
 * <p>The top row of every sub-screen is its own "Reset to Defaults" button: it restores exactly the
 * settings this page exposes (via {@link CausticaConfig.RuntimeSetting#resetToDefault()}, which snaps
 * back to the factory value without re-applying any input transform) and reopens the screen so the
 * widgets re-read the restored values. Pages never touch each other's settings; the hub additionally
 * offers a global reset whose list is {@link RtSubScreens#allResettableSettings()}.
 *
 * <p>Config persistence rides on {@link #removed()}, same as the hub and the SHaRC screen: leaving the
 * page (Done, reset, or a mode-swap rebuild) serializes the current values to {@code caustica.toml}.
 */
public class RtSettingsSubScreen extends OptionsSubScreen {

    /** One option-list block: an optional header, then paired small options, then big buttons. */
    public record Section(Component header, OptionInstance<?>[] options, List<Button> buttons) {
        /** Header + small options, no big buttons. */
        public static Section of(Component header, OptionInstance<?>[] options) {
            return new Section(header, options, List.of());
        }

        /** Header + small options + big buttons; null buttons are dropped (conditional rows). */
        public static Section of(Component header, OptionInstance<?>[] options, Button... buttons) {
            return new Section(header, options, nonNullButtons(buttons));
        }

        /** Big buttons only (toggles and action rows); null buttons are dropped. */
        public static Section buttons(Button... buttons) {
            return new Section(null, new OptionInstance<?>[0], nonNullButtons(buttons));
        }

        private static List<Button> nonNullButtons(Button... buttons) {
            List<Button> kept = new ArrayList<>();
            for (Button button : buttons) {
                // Several RtVideoOptions button builders return null when the row is not applicable
                // (e.g. Frame Generation with no viable backend): treat those as "no row".
                if (button != null) {
                    kept.add(button);
                }
            }
            return kept;
        }
    }

    /**
     * Builds this sub-screen's option-list blocks. The argument reopens the sub-screen through its
     * factory — the same rebuild-on-mode-swap pattern the upscaler selector and the Frame Generation
     * engine selector rely on so dependent rows never lag a screen behind.
     */
    @FunctionalInterface
    public interface SectionBuilder {
        List<Section> build(Runnable reopen);
    }

    private final Screen rtParent;
    private final List<CausticaConfig.RuntimeSetting<?>> resettable;
    private final Function<Screen, Screen> factory;
    private final SectionBuilder sectionBuilder;

    public RtSettingsSubScreen(Screen parent, Options options, Component title,
            List<CausticaConfig.RuntimeSetting<?>> resettable,
            Function<Screen, Screen> factory, SectionBuilder sectionBuilder) {
        super(parent, options, title);
        this.rtParent = parent;
        this.resettable = resettable;
        this.factory = factory;
        this.sectionBuilder = sectionBuilder;
    }

    @Override
    protected void addOptions() {
        OptionsList list = ((dev.comfyfluffy.caustica.mixin.OptionsSubScreenAccessor) (Object) this).getList();
        if (list == null) {
            return;
        }
        // The top row of EVERY Caustica sub-screen. The hub offers the matching global reset.
        list.addBig(resetToDefaultsButton());
        Runnable reopen = () -> this.minecraft.gui.setScreen(this.factory.apply(this.rtParent));
        for (Section section : this.sectionBuilder.build(reopen)) {
            if (section.header() != null) {
                list.addHeader(section.header());
            }
            if (section.options().length > 0) {
                list.addSmall(section.options());
            }
            for (Button button : section.buttons()) {
                list.addBig(button);
            }
        }
    }

    /**
     * This page's "emergency exit": restore every setting the page exposes to its factory default
     * (only this page's settings), persist, and reopen so all widgets show the restored values.
     */
    private Button resetToDefaultsButton() {
        Button button = Button.builder(Component.translatable("caustica.options.rt.resetToDefaults"), clicked -> {
            for (CausticaConfig.RuntimeSetting<?> setting : this.resettable) {
                setting.resetToDefault();
            }
            CausticaConfig.save();
            // Reopen through the factory so every widget re-reads its restored default; the removal
            // caused by the screen swap also persists the file once more via removed().
            this.minecraft.gui.setScreen(this.factory.apply(this.rtParent));
        }).width(310).build();
        button.setTooltip(Tooltip.create(Component.translatable("caustica.options.rt.resetToDefaults.tooltip")));
        return button;
    }

    @Override
    public void removed() {
        CausticaConfig.save();
        super.removed();
    }
}
