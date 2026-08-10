package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.screen.recipebook.RecipeBookGhostSlots;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 1.21 配方书的幽灵槽集合，使程序化配方点击可以复现原版点击前的清理步骤。
 */
@Mixin(RecipeBookWidget.class)
public interface RecipeBookWidgetAccessor {
    @Accessor("ghostSlots")
    RecipeBookGhostSlots quickcraft$getGhostSlots();
}
