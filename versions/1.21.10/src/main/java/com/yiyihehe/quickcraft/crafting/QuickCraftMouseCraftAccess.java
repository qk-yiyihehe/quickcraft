package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.world.World;

import java.util.List;

final class QuickCraftMouseCraftAccess {
    private QuickCraftMouseCraftAccess() {
    }

    public static boolean isClientWorld(World world) {
        return world != null && world.isClient();
    }

    public static List<ItemStack> getMainStacks(PlayerInventory inventory) {
        return inventory.getMainStacks();
    }

    public static boolean hasRecipeRemainder(RecipeEntry<CraftingRecipe> recipe, CraftingRecipeInput input) {
        if (recipe == null) {
            return false;
        }
        try {
            for (ItemStack remainder : recipe.value().getRecipeRemainders(input)) {
                if (!remainder.isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    public static Object resolveLockedRecipeId(MinecraftClient client,
                                               ScreenHandler handler,
                                               RecipeEntry<CraftingRecipe> recipe,
                                               ItemStack lockedResultTemplate,
                                               int gridWidth,
                                               int gridHeight) {
        return QuickCraftClientRecipeMatcher.findUniqueRecipeId(
                client,
                handler,
                lockedResultTemplate,
                gridWidth,
                gridHeight
        );
    }

    public static int throwOutput(ClientPlayerInteractionManager interactionManager,
                                  MinecraftClient client,
                                  ScreenHandler handler,
                                  List<ItemStack> pattern,
                                  int[] gridBeforeOutput,
                                  Runnable onEachThrow) {
        interactionManager.clickSlot(
                handler.syncId,
                QuickCraftMouseCraftLayout.OUTPUT_SLOT,
                1,
                SlotActionType.THROW,
                client.player
        );
        return 1;
    }

    public static int outputMovedToInventoryResult() {
        return 0;
    }

    public static int resolveOutputOperations(int plannedThrows, int actualOperations) {
        return plannedThrows;
    }

    public static boolean isFastOutputDrainAck(QuickCraftMouseCraftAckExecutor.BatchPath path, int batchOutputSlotClicks) {
        return path == QuickCraftMouseCraftAckExecutor.BatchPath.OUTPUT_DRAIN;
    }

    public static boolean canFastConfirmAuthoritative(boolean authoritativeFullState, int batchOutputSlotClicks) {
        return authoritativeFullState;
    }

    public static String getAckBoundaryReason() {
        return "纯补货批次边界";
    }
}
