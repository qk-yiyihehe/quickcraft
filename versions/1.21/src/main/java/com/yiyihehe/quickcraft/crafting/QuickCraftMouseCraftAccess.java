package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.world.World;

import java.util.List;

final class QuickCraftMouseCraftAccess {
    private QuickCraftMouseCraftAccess() {
    }

    public static boolean isClientWorld(World world) {
        return world != null && world.isClient;
    }

    public static List<ItemStack> getMainStacks(PlayerInventory inventory) {
        return inventory.main;
    }

    public static boolean hasRecipeRemainder(RecipeEntry<CraftingRecipe> recipe, CraftingRecipeInput input) {
        if (recipe == null) {
            return false;
        }
        try {
            for (ItemStack remainder : recipe.value().getRemainder(input)) {
                if (!remainder.isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    public static RecipeEntry<CraftingRecipe> findCraftingRecipe(MinecraftClient client,
                                                                 CraftingRecipeInput input,
                                                                 ItemStack expectedResult) {
        if (client == null || client.world == null || input == null || input.isEmpty()
                || expectedResult == null || expectedResult.isEmpty()) {
            return null;
        }
        try {
            List<RecipeEntry<CraftingRecipe>> matches = client.world.getRecipeManager()
                    .getAllMatches(RecipeType.CRAFTING, input, client.world);
            // 只有唯一匹配且产物与锁定结果一致时才把该实例当作返还物证据，
            // 避免同输入多配方时把另一配方的返还语义当成真实返还。
            if (matches.size() != 1) {
                return null;
            }
            RecipeEntry<CraftingRecipe> match = matches.get(0);
            ItemStack result = match.value().craft(input, client.world.getRegistryManager());
            return result != null && !result.isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(result, expectedResult)
                    ? match
                    : null;
        } catch (RuntimeException exception) {
            // 配方管理器/第三方配方属于外部边界：读取失败按“无法确认”处理，由调用方安全回收。
            return null;
        }
    }

    public static List<ItemStack> getRecipeRemainders(RecipeEntry<CraftingRecipe> recipe,
                                                      CraftingRecipeInput input) {
        if (recipe == null || input == null) {
            return null;
        }
        try {
            return new java.util.ArrayList<>(recipe.value().getRemainder(input));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public static boolean hasMatchingLockedRecipe(MinecraftClient client,
                                               ScreenHandler handler,
                                               RecipeEntry<CraftingRecipe> recipe,
                                               ItemStack lockedResultTemplate,
                                               int gridWidth,
                                               int gridHeight) {
        return recipe != null;
    }

    public static int throwOutput(ClientPlayerInteractionManager interactionManager,
                                  MinecraftClient client,
                                  ScreenHandler handler,
                                  List<ItemStack> pattern,
                                  int[] gridBeforeOutput,
                                  Runnable onEachThrow) {
        boolean[] requiredSlots = new boolean[pattern.size()];
        for (int i = 0; i < requiredSlots.length; i++) {
            requiredSlots[i] = !pattern.get(i).isEmpty();
        }
        int attempts = QuickCraftMouseCraftAckExecutor.plannedOutputBurst(gridBeforeOutput, requiredSlots);
        for (int i = 0; i < attempts; i++) {
            interactionManager.clickSlot(
                    handler.syncId,
                    QuickCraftMouseCraftLayout.OUTPUT_SLOT,
                    1,
                    SlotActionType.THROW,
                    client.player
            );
            onEachThrow.run();
        }
        return attempts;
    }

    public static int outputMovedToInventoryResult() {
        return 1;
    }

    public static int resolveOutputOperations(int plannedThrows, int actualOperations) {
        return actualOperations;
    }

    public static boolean isFastOutputDrainAck(QuickCraftMouseCraftAckExecutor.BatchPath path, int batchOutputSlotClicks) {
        return path == QuickCraftMouseCraftAckExecutor.BatchPath.OUTPUT_DRAIN && batchOutputSlotClicks == 1;
    }

    public static boolean canFastConfirmAuthoritative(boolean authoritativeFullState, int batchOutputSlotClicks) {
        return authoritativeFullState && batchOutputSlotClicks == 1;
    }

    public static String getAckBoundaryReason() {
        return "整批输出与补货边界";
    }
}
