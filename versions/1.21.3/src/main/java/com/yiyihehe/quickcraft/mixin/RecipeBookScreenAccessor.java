package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.screen.ingame.RecipeBookScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 1.21.2+ 配方书控件，使程序化配方点击可以复现原版点击前的清理步骤。
 */
@Mixin(RecipeBookScreen.class)
public interface RecipeBookScreenAccessor {
    @Accessor("recipeBook")
    RecipeBookWidget<?> quickcraft$getRecipeBook();
}
