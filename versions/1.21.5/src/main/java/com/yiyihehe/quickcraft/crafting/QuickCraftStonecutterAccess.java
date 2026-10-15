package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.recipe.display.CuttingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.screen.StonecutterScreenHandler;

import java.util.List;

final class QuickCraftStonecutterAccess {
    void selectRecipeForCraft(MinecraftClient client, StonecutterScreenHandler handler, int recipeIndex) {
        if (handler.onButtonClick(client.player, recipeIndex)) {
            client.interactionManager.clickButton(handler.syncId, recipeIndex);
        }
    }

    List<Ingredient> getIngredients(RecipeEntry<StonecuttingRecipe> recipe) {
        return recipe.value().getIngredientPlacement().getIngredients();
    }

    boolean isIngredientEmpty(Ingredient ingredient) {
        return ingredient == null || ingredient.isEmpty();
    }

    RecipeEntry<StonecuttingRecipe> getRecipeAt(StonecutterScreenHandler handler, int recipeIndex) {
        List<CuttingRecipeDisplay.GroupEntry<StonecuttingRecipe>> entries = handler.getAvailableRecipes().entries();
        if (recipeIndex >= 0 && recipeIndex < entries.size()) {
            return entries.get(recipeIndex).recipe().recipe().orElse(null);
        }
        return null;
    }

    int findAvailableRecipeIndex(StonecutterScreenHandler handler, RecipeEntry<StonecuttingRecipe> recipe) {
        if (recipe == null) {
            return -1;
        }
        List<CuttingRecipeDisplay.GroupEntry<StonecuttingRecipe>> entries = handler.getAvailableRecipes().entries();
        for (int i = 0; i < entries.size(); i++) {
            RecipeEntry<StonecuttingRecipe> availableRecipe = entries.get(i).recipe().recipe().orElse(null);
            if (availableRecipe != null && availableRecipe.id().equals(recipe.id())) {
                return i;
            }
        }
        return -1;
    }

    int availableRecipeCount(StonecutterScreenHandler handler) {
        return handler.getAvailableRecipeCount();
    }

    ItemStack getDisplayResultStack(MinecraftClient client, StonecutterScreenHandler handler, int recipeIndex) {
        if (client.world == null || recipeIndex < 0 || recipeIndex >= availableRecipeCount(handler)) {
            return ItemStack.EMPTY;
        }
        try {
            return handler.getAvailableRecipes()
                    .entries()
                    .get(recipeIndex)
                    .recipe()
                    .optionDisplay()
                    .getFirst(SlotDisplayContexts.createParameters(client.world))
                    .copy();
        } catch (Throwable throwable) {
            return ItemStack.EMPTY;
        }
    }

    List<ItemStack> getMainStacks(PlayerInventory inventory) {
        return inventory.getMainStacks();
    }

    void throwOutput(MinecraftClient client, StonecutterScreenHandler handler, ItemStack template) {
        // 1.21.2+ 原版 Ctrl+Q 连续取出同种产物，一包即可耗尽当前输入。
        client.interactionManager.clickSlot(handler.syncId, 1, 1, net.minecraft.screen.slot.SlotActionType.THROW, client.player);
    }
}
