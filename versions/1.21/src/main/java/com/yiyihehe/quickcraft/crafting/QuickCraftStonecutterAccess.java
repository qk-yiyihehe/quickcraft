package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.text.Text;

import java.util.List;

final class QuickCraftStonecutterAccess {
    private static final int RECIPE_RESULT_WAIT_TICKS = 3;
    private int recipeResultWaitTicks = 0;

    QuickCraftStonecutterAccess() {
    }

    void onTick(QuickCraftStonecutter stonecutter, MinecraftClient client, StonecutterScreenHandler handler) {
    }

    boolean onRapidCraftTickStart(QuickCraftStonecutter stonecutter, MinecraftClient client, StonecutterScreenHandler handler) {
        if (recipeResultWaitTicks <= 0) {
            return false;
        }

        if (handler.getSlot(1).hasStack()) {
            recipeResultWaitTicks = 0;
            return false;
        }

        recipeResultWaitTicks--;
        if (recipeResultWaitTicks <= 0) {
            stonecutter.stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
        }
        return true;
    }

    boolean shouldBreakRapidLoop(QuickCraftStonecutter stonecutter) {
        return !stonecutter.isRapidCraftingActive() || recipeResultWaitTicks > 0;
    }

    boolean isOutputSlotReady(QuickCraftStonecutter stonecutter, StonecutterScreenHandler handler) {
        if (recipeResultWaitTicks > 0) {
            return true;
        }
        return handler.getSlot(1).hasStack();
    }

    boolean onOutputSlotEmptyDuringRapid(StonecutterScreenHandler handler) {
        return false;
    }

    boolean isSingleCraftPending() {
        return false;
    }

    boolean selectRecipeForCraft(MinecraftClient client, StonecutterScreenHandler handler, int recipeIndex) {
        if (handler.getSelectedRecipe() == recipeIndex && handler.getSlot(1).hasStack()) {
            return true;
        }
        if (handler.onButtonClick(client.player, recipeIndex)) {
            client.interactionManager.clickButton(handler.syncId, recipeIndex);
            return true;
        }
        return false;
    }

    void handleSingleCraftFailure(QuickCraftStonecutter stonecutter, MinecraftClient client, StonecutterScreenHandler handler, RecipeEntry<StonecuttingRecipe> recipe) {
        stonecutter.sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
    }

    int resolveRecipeIndex(QuickCraftStonecutter stonecutter, MinecraftClient client, StonecutterScreenHandler handler, RecipeEntry<StonecuttingRecipe> recipe, int lockedRecipeIndex, ItemStack lockedResultTemplate) {
        int recipeIndex = findAvailableRecipeIndex(handler, recipe);
        if (recipeIndex < 0) {
            recipeIndex = stonecutter.findAvailableRecipeIndexByResult(client, handler, lockedResultTemplate);
        }
        if (recipeIndex < 0) {
            recipeIndex = lockedRecipeIndex;
        }
        return recipeIndex;
    }

    void onRecipeSelectedForOutput(QuickCraftStonecutter stonecutter, MinecraftClient client, StonecutterScreenHandler handler, int recipeIndex) {
        if (handler.getSelectedRecipe() != recipeIndex || !handler.getSlot(1).hasStack()) {
            handler.onButtonClick(client.player, recipeIndex);
            client.interactionManager.clickButton(handler.syncId, recipeIndex);
        }
        if (stonecutter.isRapidCraftingActive() && !handler.getSlot(1).hasStack()) {
            recipeResultWaitTicks = RECIPE_RESULT_WAIT_TICKS;
        }
    }

    boolean canAcceptOutput(PlayerInventory inventory, ItemStack output) {
        return false;
    }

    void clearPendingSingleCraft() {
        recipeResultWaitTicks = 0;
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
}
