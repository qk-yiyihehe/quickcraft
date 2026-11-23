package com.yiyihehe.quickcraft;

import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;

/** 1.21.0-1.21.1 的工作站输入判定。 */
public final class QuickFillContainerRecipeAccess {
    private QuickFillContainerRecipeAccess() {
    }

    static boolean isFurnaceFuel(MinecraftClient client, ItemStack stack) {
        return AbstractFurnaceBlockEntity.canUseAsFuel(stack);
    }

    static boolean isStonecutterInput(MinecraftClient client, ItemStack stack) {
        return client.world != null && client.world.getRecipeManager()
                .getFirstMatch(RecipeType.STONECUTTING, new SingleStackRecipeInput(stack), client.world)
                .isPresent();
    }

    public static boolean isAccepted(MinecraftClient client, ScreenHandler handler, ItemStack stack) {
        if (client.world == null) {
            return false;
        }
        if (handler instanceof BlastFurnaceScreenHandler) {
            return hasRecipe(client, RecipeType.BLASTING, stack);
        }
        if (handler instanceof SmokerScreenHandler) {
            return hasRecipe(client, RecipeType.SMOKING, stack);
        }
        return hasRecipe(client, RecipeType.SMELTING, stack);
    }

    private static <T extends AbstractCookingRecipe> boolean hasRecipe(
            MinecraftClient client, RecipeType<T> recipeType, ItemStack stack) {
        return client.world.getRecipeManager()
                .getFirstMatch(recipeType, new SingleStackRecipeInput(stack), client.world)
                .isPresent();
    }
}
