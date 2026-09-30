package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.screen.CraftingScreenHandler;

import java.util.List;
import java.util.Optional;

final class QuickCraftWorkbenchShulkerAccess {
    private QuickCraftWorkbenchShulkerAccess() {
    }

    public static boolean shouldProbeReconciledRefill(QuickCraftWorkbenchShulkerCraft.AckBatchKind kind,
                                                      int fullInventoryUpdates,
                                                      boolean outputOnlyMismatch,
                                                      boolean terminalStateSafe,
                                                      boolean baselinePending,
                                                      boolean baselineAvailable,
                                                      boolean probePending) {
        return false;
    }

    public static boolean isRecipeCaptured(RecipeEntry<CraftingRecipe> currentRecipe, boolean capturedVisibleRecipe) {
        return currentRecipe != null && capturedVisibleRecipe;
    }

    public static boolean isSnapshotComplete(RecipeEntry<CraftingRecipe> recipe, List<ItemStack> pattern, ItemStack resultTemplate) {
        return recipe != null && !pattern.isEmpty() && !resultTemplate.isEmpty();
    }

    public static RecipeEntry<CraftingRecipe> resolveCapturedRecipe(RecipeEntry<CraftingRecipe> currentRecipe) {
        return currentRecipe;
    }

    public static boolean isRemainderIncompatible(boolean recipeHasRemainder,
                                                  boolean patternGridCompatible,
                                                  RecipeEntry<CraftingRecipe> currentRecipe,
                                                  CraftingRecipeInput input) {
        return false;
    }

    public static boolean isUnidentifiedRecipe(MinecraftClient client,
                                               CraftingScreenHandler handler,
                                               RecipeEntry<CraftingRecipe> recipe,
                                               ItemStack resultTemplate) {
        return QuickCraftWorkbenchShulkerCraft.isIgnoredInRecipeBook(recipe);
    }

    public static boolean isRecipeRequiredForLocalPrime() {
        return true;
    }

    public static RecipeEntry<CraftingRecipe> findCurrentRecipe(MinecraftClient client,
                                                               CraftingRecipeInput input) {
        if (client == null || client.world == null) {
            return null;
        }
        try {
            Optional<RecipeEntry<CraftingRecipe>> match = client.world.getRecipeManager().getFirstMatch(
                    RecipeType.CRAFTING, input, client.world);
            return match.orElse(null);
        } catch (Throwable throwable) {
            return null;
        }
    }

    public static boolean hasRemainder(RecipeEntry<CraftingRecipe> currentRecipe,
                                       CraftingRecipeInput input) {
        return false;
    }

    public static List<ItemStack> snapshotRemainderPattern(RecipeEntry<CraftingRecipe> currentRecipe,
                                                           CraftingRecipeInput input) {
        return currentRecipe.value().getRemainder(input);
    }

    public static int getMainStacksSize(PlayerInventory inventory) {
        return inventory.main.size();
    }
}
