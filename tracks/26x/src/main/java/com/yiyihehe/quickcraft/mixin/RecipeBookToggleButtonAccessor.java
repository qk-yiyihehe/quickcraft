package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.components.ImageButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ImageButton.class)
public interface RecipeBookToggleButtonAccessor {
    @Accessor("sprites")
    WidgetSprites quickcraft$getRecipeBookToggleSprites();
}
