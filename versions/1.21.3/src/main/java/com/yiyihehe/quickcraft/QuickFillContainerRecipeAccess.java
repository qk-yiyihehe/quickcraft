package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipePropertySet;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;

/** 1.21.3+ 使用同步的配方属性集和燃料表判定工作站输入。 */
public final class QuickFillContainerRecipeAccess {
    private QuickFillContainerRecipeAccess() {}

    static boolean isFurnaceFuel(MinecraftClient client, ItemStack stack) {
        return client.world != null && client.world.getFuelRegistry().isFuel(stack);
    }

    static boolean isStonecutterInput(MinecraftClient client, ItemStack stack) {
        return client.world != null && client.world.getRecipeManager().getStonecutterRecipes().contains(stack);
    }

    public static boolean isAccepted(MinecraftClient client, ScreenHandler handler, ItemStack stack) {
        if (client.world == null) {
            return false;
        }
        RegistryKey<RecipePropertySet> key = handler instanceof BlastFurnaceScreenHandler
                ? RecipePropertySet.BLAST_FURNACE_INPUT
                : handler instanceof SmokerScreenHandler ? RecipePropertySet.SMOKER_INPUT
                        : RecipePropertySet.FURNACE_INPUT;
        return client.world.getRecipeManager().getPropertySet(key).canUse(stack);
    }
}
