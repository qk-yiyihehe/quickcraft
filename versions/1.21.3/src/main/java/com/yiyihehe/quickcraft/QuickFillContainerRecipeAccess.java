package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipePropertySet;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;

/** 1.21.3+ 使用配方属性集判定炉子输入；后续 1.21.x 目标继承此实现。 */
public final class QuickFillContainerRecipeAccess {
    private QuickFillContainerRecipeAccess() {
    }

    public static boolean isAccepted(MinecraftClient client, ScreenHandler handler, ItemStack stack) {
        if (client.world == null) {
            return false;
        }
        RegistryKey<RecipePropertySet> key = handler instanceof BlastFurnaceScreenHandler
                ? RecipePropertySet.BLAST_FURNACE_INPUT
                : handler instanceof SmokerScreenHandler
                ? RecipePropertySet.SMOKER_INPUT
                : RecipePropertySet.FURNACE_INPUT;
        return client.world.getRecipeManager().getPropertySet(key).canUse(stack);
    }
}
