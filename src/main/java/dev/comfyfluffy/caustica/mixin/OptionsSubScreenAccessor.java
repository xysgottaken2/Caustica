package dev.comfyfluffy.caustica.mixin;

import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the option list built by {@link OptionsSubScreen} so subscreen mixins can append entries. */
@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccessor {
    @Accessor("list")
    OptionsList getList();
}
