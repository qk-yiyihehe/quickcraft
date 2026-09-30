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
import net.minecraft.text.Text;

import java.util.List;

final class QuickCraftStonecutterAccess {
    private static final int SERVER_SYNC_TIMEOUT_TICKS = 40;

    private boolean singleCraftPending = false;
    private int singleCraftWaitTicks = 0;

    QuickCraftStonecutterAccess() {
    }

    void onTick(QuickCraftStonecutter stonecutter, MinecraftClient client, StonecutterScreenHandler handler) {
        processPendingSingleCraft(stonecutter, client, handler);
    }

    private void processPendingSingleCraft(QuickCraftStonecutter stonecutter, MinecraftClient client, StonecutterScreenHandler handler) {
        if (!singleCraftPending || stonecutter.isRapidCraftingActive()) {
            return;
        }

        if (handler.getSlot(1).hasStack()) {
            ItemStack output = handler.getSlot(1).getStack();
            if (!ItemStack.areItemsAndComponentsEqual(output, stonecutter.getLockedResultTemplate())) {
                clearPendingSingleCraft();
                return;
            }

            if (stonecutter.runOneCraftSubLoop(client, handler, stonecutter.getLockedRecipe())) {
                clearPendingSingleCraft();
                return;
            }
        }

        singleCraftWaitTicks++;
        if (singleCraftWaitTicks >= SERVER_SYNC_TIMEOUT_TICKS) {
            clearPendingSingleCraft();
            stonecutter.sendStatusMessage(client, Text.translatable("quickcraft.message.stonecutter.sync_timeout"));
        }
    }

    boolean onRapidCraftTickStart(QuickCraftStonecutter stonecutter, MinecraftClient client, StonecutterScreenHandler handler) {
        return false;
    }

    boolean shouldBreakRapidLoop(QuickCraftStonecutter stonecutter) {
        return !stonecutter.isRapidCraftingActive();
    }

    boolean isOutputSlotReady(QuickCraftStonecutter stonecutter, StonecutterScreenHandler handler) {
        return handler.getSlot(1).hasStack();
    }

    boolean onOutputSlotEmptyDuringRapid(StonecutterScreenHandler handler) {
        return true;
    }

    boolean isSingleCraftPending() {
        return singleCraftPending;
    }

    boolean selectRecipeForCraft(MinecraftClient client, StonecutterScreenHandler handler, int recipeIndex) {
        boolean selectionChanged = handler.getSelectedRecipe() != recipeIndex;
        if (selectionChanged) {
            handler.onButtonClick(client.player, recipeIndex);
            client.interactionManager.clickButton(handler.syncId, recipeIndex);
        }
        return true;
    }

    void handleSingleCraftFailure(QuickCraftStonecutter stonecutter, MinecraftClient client, StonecutterScreenHandler handler, RecipeEntry<StonecuttingRecipe> recipe) {
        if (stonecutter.isIngredientUnavailable(client, handler, recipe)) {
            stonecutter.sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
        } else {
            singleCraftPending = true;
            singleCraftWaitTicks = 0;
        }
    }

    int resolveRecipeIndex(QuickCraftStonecutter stonecutter, MinecraftClient client, StonecutterScreenHandler handler, RecipeEntry<StonecuttingRecipe> recipe, int lockedRecipeIndex, ItemStack lockedResultTemplate) {
        int count = availableRecipeCount(handler);
        int recipeIndex = (lockedRecipeIndex >= 0 && lockedRecipeIndex < count) ? lockedRecipeIndex : -1;
        if (recipeIndex < 0) {
            recipeIndex = findAvailableRecipeIndex(handler, recipe);
        }
        if (recipeIndex < 0) {
            recipeIndex = stonecutter.findAvailableRecipeIndexByResult(client, handler, lockedResultTemplate);
        }
        return recipeIndex;
    }

    void onRecipeSelectedForOutput(QuickCraftStonecutter stonecutter, MinecraftClient client, StonecutterScreenHandler handler, int recipeIndex) {
        boolean selectionChanged = handler.getSelectedRecipe() != recipeIndex;
        if (selectionChanged) {
            handler.onButtonClick(client.player, recipeIndex);
            client.interactionManager.clickButton(handler.syncId, recipeIndex);
        }
    }

    boolean canAcceptOutput(PlayerInventory inventory, ItemStack output) {
        if (output.isEmpty()) {
            return false;
        }

        for (ItemStack stack : getMainStacks(inventory)) {
            if (stack.isEmpty()) {
                return true;
            }
            if (ItemStack.areItemsAndComponentsEqual(stack, output)
                    && stack.getCount() + output.getCount() <= stack.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    void clearPendingSingleCraft() {
        singleCraftPending = false;
        singleCraftWaitTicks = 0;
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
        return inventory.main;
    }
}
