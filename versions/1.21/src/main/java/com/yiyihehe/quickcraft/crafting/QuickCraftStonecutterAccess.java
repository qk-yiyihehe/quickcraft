package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.screen.StonecutterScreenHandler;

import java.util.List;

final class QuickCraftStonecutterAccess {
    void selectRecipeForCraft(MinecraftClient client, StonecutterScreenHandler handler, int recipeIndex) {
        if (handler.onButtonClick(client.player, recipeIndex)) {
            client.interactionManager.clickButton(handler.syncId, recipeIndex);
        }
    }

    List<Ingredient> getIngredients(RecipeEntry<StonecuttingRecipe> recipe) {
        return recipe.value().getIngredients();
    }

    boolean isIngredientEmpty(Ingredient ingredient) {
        return ingredient == null || ingredient.isEmpty();
    }

    RecipeEntry<StonecuttingRecipe> getRecipeAt(StonecutterScreenHandler handler, int recipeIndex) {
        List<RecipeEntry<StonecuttingRecipe>> recipes = handler.getAvailableRecipes();
        if (recipeIndex >= 0 && recipeIndex < recipes.size()) {
            return recipes.get(recipeIndex);
        }
        return null;
    }

    int findAvailableRecipeIndex(StonecutterScreenHandler handler, RecipeEntry<StonecuttingRecipe> recipe) {
        if (recipe == null) {
            return -1;
        }
        List<RecipeEntry<StonecuttingRecipe>> recipes = handler.getAvailableRecipes();
        for (int i = 0; i < recipes.size(); i++) {
            if (recipes.get(i).id().equals(recipe.id())) {
                return i;
            }
        }
        return -1;
    }

    int availableRecipeCount(StonecutterScreenHandler handler) {
        return handler.getAvailableRecipes().size();
    }

    ItemStack getDisplayResultStack(MinecraftClient client, StonecutterScreenHandler handler, int recipeIndex) {
        RecipeEntry<StonecuttingRecipe> recipe = getRecipeAt(handler, recipeIndex);
        if (recipe == null || client.player == null || client.world == null) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack input = client.player.currentScreenHandler.getSlot(0).getStack().copy();
            return recipe.value().craft(new net.minecraft.recipe.input.SingleStackRecipeInput(input), client.world.getRegistryManager()).copy();
        } catch (Throwable throwable) {
            return ItemStack.EMPTY;
        }
    }

    List<ItemStack> getMainStacks(PlayerInventory inventory) {
        return inventory.main;
    }

    void throwOutput(MinecraftClient client, StonecutterScreenHandler handler, ItemStack template) {
        // 1.21/1.21.1 的 Ctrl+Q 只取当前结果；按输入数量发送有界批次。
        int attempts = Math.min(64, handler.getSlot(0).getStack().getCount());
        for (int i = 0; i < attempts; i++) {
            ItemStack output = handler.getSlot(1).getStack();
            if (output.isEmpty() || !ItemStack.areItemsAndComponentsEqual(output, template)) {
                break;
            }
            client.interactionManager.clickSlot(handler.syncId, 1, 1, net.minecraft.screen.slot.SlotActionType.THROW, client.player);
        }
    }
}
