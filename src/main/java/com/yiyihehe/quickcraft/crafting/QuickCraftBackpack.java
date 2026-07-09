package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 背包 2x2 快速合成：普通配方走原版配方书点击，特殊配方按锁定格子手动补料。
 */
public class QuickCraftBackpack implements ClientModInitializer {
    private static QuickCraftBackpack INSTANCE;

    private static final int RAPID_INTERVAL = 1;

    private static final int OUTPUT_TAKE_ATTEMPTS_AFTER_DROP = 2;

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final int RECIPE_BOOK_RESULT_WAIT_TICKS = 3;

    private static final int OUTPUT_SLOT = 0;

    private static final int CRAFTING_GRID_SIZE = 4;

    private static final int CRAFTING_GRID_WIDTH = 2;

    private static final int CRAFTING_GRID_HEIGHT = 2;

    private boolean lastVDown = false;

    private boolean lastAltCDown = false;

    private boolean rapidCraftingActive = false;
    private boolean rapidCraftStartedByButton = false;

    private int rapidCooldown = 0;

    private int consecutiveFailures = 0;

    private int recipeBookResultWaitTicks = 0;

    private RecipeEntry<CraftingRecipe> lockedRecipe = null;

    private List<ItemStack> lockedCraftingPattern = new ArrayList<>();

    private ItemStack lockedResultTemplate = ItemStack.EMPTY;

    private boolean ingredientDropLocked = false;

    private int lastObservedOutputSignature = 0;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean handleBackpackCraftButton(boolean rapidCraft) {
        if (INSTANCE == null) {
            return false;
        }
        return INSTANCE.handleCraftButton(MinecraftClient.getInstance(), rapidCraft);
    }

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isBackpackQuickCraftEnabled()) {
            resetAll();
            return;
        }

        if (!isCraftingContextValid(client)) {
            resetAll();
            return;
        }

        PlayerScreenHandler handler = (PlayerScreenHandler) client.player.currentScreenHandler;

        updateIngredientDropLock(handler);

        handleHotkeys(client, handler);

        if (rapidCraftingActive && rapidCraftStartedByButton && !isCraftButtonRapidModeHeld(client)) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.backpack.stopped"));
        }

        if (rapidCraftingActive && hasLockedCraftingPlan()) {
            rapidCooldown++;
            if (rapidCooldown >= RAPID_INTERVAL) {
                rapidCooldown = 0;
                processRapidCraftTick(client, handler, lockedRecipe);
            }
        }
    }

    private void processRapidCraftTick(MinecraftClient client,
                                       PlayerScreenHandler handler,
                                       RecipeEntry<CraftingRecipe> recipe) {
        if (waitForRecipeBookResult(client, handler)) {
            return;
        }

        boolean anyProgress = false;
        int craftLoopsPerTick = QuickCraftConfigs.getCraftLoopsPerTick();

        for (int loop = 0; loop < craftLoopsPerTick; loop++) {
            boolean progressed = runOneCraftSubLoop(client, handler, recipe);
            if (progressed) {
                anyProgress = true;
            }
            if (!rapidCraftingActive || recipeBookResultWaitTicks > 0) {
                break;
            }
            if (!progressed) {

                boolean fallbackSuccess = resolveOutputSlotBlockageStrict(
                    client,
                    handler,
                    getRecipeResultStack(client, recipe),
                    recipe
                );
                if (fallbackSuccess) {
                    anyProgress = true;
                }
            }
        }

        if (anyProgress) {
            consecutiveFailures = 0;
        } else {
            consecutiveFailures++;

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                stopRapidCraft(client, Text.translatable("quickcraft.message.backpack.no_progress"));
            }
        }
    }

    private boolean runOneCraftSubLoop(MinecraftClient client,
                                       PlayerScreenHandler handler,
                                       RecipeEntry<CraftingRecipe> recipe) {

        if (client.player == null || client.interactionManager == null || client.world == null) {
            return false;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (resultTemplate.isEmpty() && handler.getSlot(OUTPUT_SLOT).hasStack()) {
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getStack().copy();
        }

        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
            if (shouldManualRestock(recipe) && isManualPatternMissingItems(handler)) {
                if (restockCraftingGridFromPattern(client, handler)) {
                    tryTakeOutputForRecipe(client, handler, recipe);
                    return true;
                }
                return false;
            }

            if (tryTakeOutputForRecipe(client, handler, recipe)) {
                return true;
            }

            if (!resultTemplate.isEmpty()) {
                int droppedOutput = dropMatchingItemsFromInventoryBurst(client, handler, resultTemplate);
                if (droppedOutput > 0) {

                    if (tryTakeOutputForRecipe(client, handler, recipe)) {
                        return true;
                    }
                }
            }

            if (!hasMatchingItemInInventory(client.player.getInventory(), resultTemplate)) {
                int droppedIng = dropIngredientBurst(client, handler, recipe, 1);
                if (droppedIng > 0) {
                    if (tryTakeOutputForRecipe(client, handler, recipe)) {
                        return true;
                    } else {
                        return true;
                    }
                }
            }

            return false;
        }

        if (restockCraftingGrid(client, handler, recipe)) {
            tryTakeOutputForRecipe(client, handler, recipe);
            return true;
        }

        return false;
    }

    private boolean waitForRecipeBookResult(MinecraftClient client, PlayerScreenHandler handler) {
        if (recipeBookResultWaitTicks <= 0) {
            return false;
        }

        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
            recipeBookResultWaitTicks = 0;
            return false;
        }

        recipeBookResultWaitTicks--;
        if (recipeBookResultWaitTicks <= 0) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
        }
        return true;
    }

    private boolean hasMatchingItemInInventory(PlayerInventory inventory, ItemStack template) {
        if (template.isEmpty()) {
            return false;
        }

        for (ItemStack stack : inventory.main) {
            if (stack.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return true;
            }
        }
        return false;
    }

    private boolean resolveOutputSlotBlockageStrict(MinecraftClient client,
                                                    PlayerScreenHandler handler,
                                                    ItemStack resultTemplate,
                                                    RecipeEntry<CraftingRecipe> recipe) {

        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        if (tryTakeOutputForRecipe(client, handler, recipe)) {
            ingredientDropLocked = false;
            return true;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            ingredientDropLocked = false;
            return false;
        }

        if (dropOutputsBeforeTakingAndTryTake(client, handler, resultTemplate, OUTPUT_TAKE_ATTEMPTS_AFTER_DROP, recipe)) {
            if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                ingredientDropLocked = false;
            }
            return true;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            ingredientDropLocked = false;
            return false;
        }

        if (!ingredientDropLocked) {

            int droppedIng = dropIngredientBurst(client, handler, recipe, 1);
            if (droppedIng > 0) {
                ingredientDropLocked = true;

                boolean tookOutput = dropOutputsBeforeTakingAndTryTake(
                        client,
                        handler,
                        resultTemplate,
                        OUTPUT_TAKE_ATTEMPTS_AFTER_DROP,
                        recipe
                );
                if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                    ingredientDropLocked = false;
                }
                return tookOutput || droppedIng > 0;
            }
        }

        return false;
    }

    private void handleSingleCraft(MinecraftClient client, PlayerScreenHandler handler) {

        RecipeEntry<CraftingRecipe> currentRecipe = getCurrentCraftingRecipe(client, handler);
        if (currentRecipe != null || handler.getSlot(OUTPUT_SLOT).hasStack()) {
            lockCurrentRecipe(currentRecipe, handler);
        }

        if (!hasLockedCraftingPlan()) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_recipe"));
            return;
        }

        boolean success = runOneCraftSubLoop(client, handler, lockedRecipe);
        if (!success) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.single_no_progress"));
        }
    }

    private boolean handleCraftButton(MinecraftClient client, boolean rapidCraft) {
        if (!isCraftingContextValid(client)) {
            return false;
        }

        PlayerScreenHandler handler = (PlayerScreenHandler) client.player.currentScreenHandler;
        if (rapidCraft) {
            return startRapidCraft(client, handler, true);
        }

        handleSingleCraft(client, handler);
        return true;
    }

    private boolean clickRecipe(MinecraftClient client,
                                PlayerScreenHandler handler,
                                RecipeEntry<CraftingRecipe> recipe) {
        if (client.player == null || client.interactionManager == null || recipe == null) {
            return false;
        }
        try {
            client.interactionManager.clickRecipe(handler.syncId, recipe, true);
            if (rapidCraftingActive && !handler.getSlot(OUTPUT_SLOT).hasStack()) {
                recipeBookResultWaitTicks = RECIPE_BOOK_RESULT_WAIT_TICKS;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean restockCraftingGrid(MinecraftClient client,
                                        PlayerScreenHandler handler,
                                        RecipeEntry<CraftingRecipe> recipe) {
        if (!shouldManualRestock(recipe)) {
            return clickRecipe(client, handler, recipe);
        }

        return restockCraftingGridFromPattern(client, handler);
    }

    private boolean restockCraftingGridFromPattern(MinecraftClient client,
                                                   PlayerScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        if (!handler.getCursorStack().isEmpty() || lockedCraftingPattern.isEmpty()) {
            return false;
        }
        if (!hasItemsForMissingPatternSlots(client.player.getInventory(), handler)) {
            return false;
        }

        boolean hasPattern = false;
        boolean changed = false;
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            hasPattern = true;
            int gridSlot = 1 + i;
            ItemStack existing = handler.getSlot(gridSlot).getStack();
            if (!existing.isEmpty()) {
                if (!ItemStack.areItemsAndComponentsEqual(existing, template)) {
                    return false;
                }
                continue;
            }

            int sourceSlot = findMatchingPlayerInventoryHandlerSlot(
                    client.player.getInventory(),
                    handler,
                    template
            );
            int sameMissingSlots = 1 + countMissingPatternSlots(handler, i + 1, template);
            if (sourceSlot == -1 || !moveIngredientStackToGridSlot(client, handler, sourceSlot, gridSlot, template, i, sameMissingSlots)) {
                return false;
            }
            changed = true;
        }

        return hasPattern && (changed || handler.getSlot(OUTPUT_SLOT).hasStack());
    }

    private boolean moveIngredientStackToGridSlot(MinecraftClient client,
                                                  PlayerScreenHandler handler,
                                                  int sourceSlot,
                                                  int gridSlot,
                                                  ItemStack template,
                                                  int patternIndex,
                                                  int sameMissingSlots) {
        int sourceCount = handler.getSlot(sourceSlot).getStack().getCount();
        if (sameMissingSlots > 1) {
            return quickCraftDistributeToMissingPatternSlots(client, handler, sourceSlot, patternIndex, template);
        }

        if (sourceCount >= template.getMaxCount() && canFillSamePatternSlotsWithFullStacks(handler, template)) {
            return moveFullStackToGridSlot(client, handler, sourceSlot, gridSlot, template);
        }

        if (sourceCount <= sameMissingSlots) {
            return moveOneItemToGridSlot(client, handler, sourceSlot, gridSlot, template);
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                sourceSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                gridSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        return handler.getCursorStack().isEmpty()
                && handler.getSlot(gridSlot).hasStack()
                && ItemStack.areItemsAndComponentsEqual(handler.getSlot(gridSlot).getStack(), template);
    }

    private boolean canFillSamePatternSlotsWithFullStacks(PlayerScreenHandler handler,
                                                          ItemStack template) {
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(patternStack, template)) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getStack();
            if (existing.isEmpty()) {
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, template)
                    || existing.getCount() < template.getMaxCount()) {
                return false;
            }
        }
        return true;
    }

    private boolean moveFullStackToGridSlot(MinecraftClient client,
                                            PlayerScreenHandler handler,
                                            int sourceSlot,
                                            int gridSlot,
                                            ItemStack template) {
        client.interactionManager.clickSlot(
                handler.syncId,
                sourceSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                gridSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        return handler.getCursorStack().isEmpty()
                && handler.getSlot(gridSlot).hasStack()
                && ItemStack.areItemsAndComponentsEqual(handler.getSlot(gridSlot).getStack(), template);
    }

    private boolean moveOneItemToGridSlot(MinecraftClient client,
                                          PlayerScreenHandler handler,
                                          int sourceSlot,
                                          int gridSlot,
                                          ItemStack template) {
        client.interactionManager.clickSlot(
                handler.syncId,
                sourceSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                gridSlot,
                1,
                SlotActionType.PICKUP,
                client.player
        );

        boolean placed = handler.getSlot(gridSlot).hasStack()
                && ItemStack.areItemsAndComponentsEqual(handler.getSlot(gridSlot).getStack(), template);
        boolean cursorReturned = returnCursorStack(client, handler, sourceSlot);
        return placed && cursorReturned;
    }

    private boolean quickCraftDistributeToMissingPatternSlots(MinecraftClient client,
                                                              PlayerScreenHandler handler,
                                                              int sourceSlot,
                                                              int startPatternIndex,
                                                              ItemStack template) {
        List<Integer> targetSlots = getMissingPatternSlots(handler, startPatternIndex, template);
        if (targetSlots.size() <= 1) {
            return moveOneItemToGridSlot(client, handler, sourceSlot, 1 + startPatternIndex, template);
        }

        sourceSlot = pickUpMergedIngredientStack(client, handler, sourceSlot, targetSlots.size());
        if (sourceSlot == -1) {
            return false;
        }

        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        if (handler.getCursorStack().getCount() < targetSlots.size()) {
            returnCursorStack(client, handler, sourceSlot);
            return false;
        }

        return distributeCursorStackToMissingPatternSlots(client, handler, sourceSlot, targetSlots, template);
    }

    private int pickUpMergedIngredientStack(MinecraftClient client,
                                            PlayerScreenHandler handler,
                                            int sourceSlot,
                                            int targetSlotCount) {
        client.interactionManager.clickSlot(
                handler.syncId,
                sourceSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        if (handler.getCursorStack().isEmpty()) {
            return -1;
        }

        if (handler.getCursorStack().getCount() < targetSlotCount
                || handler.getCursorStack().getCount() < handler.getCursorStack().getMaxCount()) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    sourceSlot,
                    0,
                    SlotActionType.PICKUP_ALL,
                    client.player
            );
        }

        return sourceSlot;
    }

    private boolean distributeCursorStackToMissingPatternSlots(MinecraftClient client,
                                                               PlayerScreenHandler handler,
                                                               int sourceSlot,
                                                               List<Integer> targetSlots,
                                                               ItemStack template) {
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                -999,
                ScreenHandler.packQuickCraftData(0, 0),
                SlotActionType.QUICK_CRAFT,
                client.player
        );
        for (int targetSlot : targetSlots) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    targetSlot,
                    ScreenHandler.packQuickCraftData(1, 0),
                    SlotActionType.QUICK_CRAFT,
                    client.player
            );
        }
        client.interactionManager.clickSlot(
                handler.syncId,
                -999,
                ScreenHandler.packQuickCraftData(2, 0),
                SlotActionType.QUICK_CRAFT,
                client.player
        );

        boolean cursorReturned = returnCursorStack(client, handler, sourceSlot);
        if (!cursorReturned) {
            return false;
        }

        for (int targetSlot : targetSlots) {
            ItemStack placed = handler.getSlot(targetSlot).getStack();
            if (placed.isEmpty() || !ItemStack.areItemsAndComponentsEqual(placed, template)) {
                return false;
            }
        }
        return true;
    }

    private List<Integer> getMissingPatternSlots(PlayerScreenHandler handler,
                                                 int startPatternIndex,
                                                 ItemStack template) {
        List<Integer> slots = new ArrayList<>();
        for (int i = startPatternIndex; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(patternStack, template)) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getStack();
            if (existing.isEmpty()) {
                slots.add(1 + i);
            }
        }
        return slots;
    }

    private boolean returnCursorStack(MinecraftClient client,
                                      PlayerScreenHandler handler,
                                      int preferredSlot) {
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }

        if (canAcceptStack(handler.getSlot(preferredSlot).getStack(), handler.getCursorStack())) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    preferredSlot,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
        }
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }

        int returnSlot = findAcceptingPlayerInventoryHandlerSlot(client.player.getInventory(), handler, handler.getCursorStack());
        if (returnSlot == -1) {
            return false;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                returnSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        return handler.getCursorStack().isEmpty();
    }

    private boolean canAcceptStack(ItemStack targetStack, ItemStack cursorStack) {
        return targetStack.isEmpty()
                || (ItemStack.areItemsAndComponentsEqual(targetStack, cursorStack)
                && targetStack.getCount() + cursorStack.getCount() <= targetStack.getMaxCount());
    }

    private int countMissingPatternSlots(PlayerScreenHandler handler,
                                         int startPatternIndex,
                                         ItemStack template) {
        int total = 0;
        for (int i = startPatternIndex; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(patternStack, template)) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getStack();
            if (existing.isEmpty()) {
                total++;
            }
        }
        return total;
    }

    private boolean hasItemsForMissingPatternSlots(PlayerInventory inventory,
                                                   PlayerScreenHandler handler) {
        List<ItemStack> availableStacks = new ArrayList<>();
        for (ItemStack stack : inventory.main) {
            if (!stack.isEmpty()) {
                availableStacks.add(stack.copy());
            }
        }

        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getStack();
            if (!existing.isEmpty()) {
                if (!ItemStack.areItemsAndComponentsEqual(existing, template)) {
                    return false;
                }
                continue;
            }

            int availableIndex = findMatchingStackIndex(availableStacks, template);
            if (availableIndex == -1) {
                return false;
            }
            availableStacks.get(availableIndex).decrement(1);
        }

        return true;
    }

    private boolean isManualPatternMissingItems(PlayerScreenHandler handler) {
        if (lockedCraftingPattern.isEmpty()) {
            return false;
        }

        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getStack();
            if (existing.isEmpty()) {
                return true;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, template)) {
                return false;
            }
        }

        return false;
    }

    private int findMatchingStackIndex(List<ItemStack> stacks, ItemStack template) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return i;
            }
        }
        return -1;
    }

    private int findMatchingPlayerInventoryHandlerSlot(PlayerInventory inventory,
                                                       PlayerScreenHandler handler,
                                                       ItemStack template) {
        int bestSlot = -1;
        int bestCount = -1;
        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            ItemStack stack = inventory.main.get(invIndex);
            if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, template)) {
                continue;
            }

            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot != -1 && handler.getSlot(handlerSlot).hasStack()) {
                int stackCount = handler.getSlot(handlerSlot).getStack().getCount();
                if (stackCount > bestCount) {
                    bestCount = stackCount;
                    bestSlot = handlerSlot;
                }
            }
        }
        return bestSlot;
    }

    private int findAcceptingPlayerInventoryHandlerSlot(PlayerInventory inventory,
                                                        PlayerScreenHandler handler,
                                                        ItemStack cursorStack) {
        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1) {
                continue;
            }

            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (canAcceptStack(stack, cursorStack)) {
                return handlerSlot;
            }
        }
        return -1;
    }

    private boolean shouldManualRestock(RecipeEntry<CraftingRecipe> recipe) {
        if (recipe == null) {
            return false;
        }
        try {
            return recipe.value().isIgnoredInRecipeBook();
        } catch (Throwable t) {
            return false;
        }
    }

    private void lockCurrentRecipe(RecipeEntry<CraftingRecipe> recipe,
                                   PlayerScreenHandler handler) {
        lockedRecipe = recipe;
        lockedCraftingPattern = snapshotCraftingGrid(handler);
        lockedResultTemplate = handler.getSlot(OUTPUT_SLOT).hasStack()
                ? handler.getSlot(OUTPUT_SLOT).getStack().copy()
                : ItemStack.EMPTY;
    }

    private boolean hasLockedCraftingPlan() {
        return !lockedCraftingPattern.isEmpty() && !lockedResultTemplate.isEmpty();
    }

    private List<ItemStack> snapshotCraftingGrid(PlayerScreenHandler handler) {
        List<ItemStack> pattern = new ArrayList<>();
        for (int i = 1; i <= CRAFTING_GRID_SIZE; i++) {
            ItemStack stack = handler.getSlot(i).getStack().copy();
            if (!stack.isEmpty()) {
                stack.setCount(1);
            }
            pattern.add(stack);
        }
        return pattern;
    }

    private boolean tryQuickMoveOutput(MinecraftClient client, PlayerScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getStack().copy();
        int beforeResultCount = countMatchingItems(client.player.getInventory(), before);

        client.interactionManager.clickSlot(
                handler.syncId,
                OUTPUT_SLOT,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        ItemStack after = handler.getSlot(OUTPUT_SLOT).getStack();

        return after.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(before, after)
                || after.getCount() != before.getCount()
                || countMatchingItems(client.player.getInventory(), before) > beforeResultCount;
    }

    private boolean tryTakeOutputForRecipe(MinecraftClient client,
                                           PlayerScreenHandler handler,
                                           RecipeEntry<CraftingRecipe> recipe) {

        if (shouldManualRestock(recipe)) {
            if (isManualPatternMissingItems(handler)) {
                return false;
            }
            if (hasUnevenManualPatternStacks(handler)) {
                return tryTakeOneOutput(client, handler);
            }
        }
        return tryQuickMoveOutput(client, handler);
    }

    private boolean tryTakeOneOutput(MinecraftClient client, PlayerScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getStack().copy();
        int returnSlot = findAcceptingPlayerInventoryHandlerSlot(
                client.player.getInventory(),
                handler,
                before
        );
        if (returnSlot == -1) {
            return false;
        }

        int beforeResultCount = countMatchingItems(client.player.getInventory(), before);
        client.interactionManager.clickSlot(
                handler.syncId,
                OUTPUT_SLOT,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        boolean pickedOutput = !handler.getCursorStack().isEmpty()
                && ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), before);
        if (pickedOutput) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    returnSlot,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
        }

        ItemStack after = handler.getSlot(OUTPUT_SLOT).getStack();
        return handler.getCursorStack().isEmpty()
                && (after.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(before, after)
                || after.getCount() != before.getCount()
                || countMatchingItems(client.player.getInventory(), before) > beforeResultCount);
    }

    private boolean hasUnevenManualPatternStacks(PlayerScreenHandler handler) {
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            int count = getPatternSlotCount(handler, i, template);
            if (count <= 0) {
                continue;
            }

            for (int j = i + 1; j < lockedCraftingPattern.size(); j++) {
                ItemStack otherTemplate = lockedCraftingPattern.get(j);
                if (otherTemplate.isEmpty() || !ItemStack.areItemsAndComponentsEqual(template, otherTemplate)) {
                    continue;
                }
                int otherCount = getPatternSlotCount(handler, j, otherTemplate);
                if (otherCount > 0 && otherCount != count) {
                    return true;
                }
            }
        }
        return false;
    }

    private int getPatternSlotCount(PlayerScreenHandler handler, int patternIndex, ItemStack template) {
        ItemStack stack = handler.getSlot(1 + patternIndex).getStack();
        if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, template)) {
            return 0;
        }
        return stack.getCount();
    }

    private int getOutputSignature(PlayerScreenHandler handler) {
        if (handler == null || !handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return 0;
        }

        ItemStack stack = handler.getSlot(OUTPUT_SLOT).getStack();
        int hash = 17;
        hash = 31 * hash + System.identityHashCode(stack.getItem());

        try {
            hash = 31 * hash + stack.getCount();
            hash = 31 * hash + stack.getComponents().hashCode();
        } catch (Throwable ignored) {
        }

        return hash;
    }

    private void updateIngredientDropLock(PlayerScreenHandler handler) {
        int currentSignature = getOutputSignature(handler);

        if (currentSignature == 0) {
            ingredientDropLocked = false;
            lastObservedOutputSignature = 0;
            return;
        }

        if (lastObservedOutputSignature != 0 && lastObservedOutputSignature != currentSignature) {
            ingredientDropLocked = false;
        }

        lastObservedOutputSignature = currentSignature;
    }

    private boolean dropOutputsBeforeTakingAndTryTake(MinecraftClient client,
                                                      PlayerScreenHandler handler,
                                                      ItemStack resultTemplate,
                                                      int takeAttemptsAfterDrop,
                                                      RecipeEntry<CraftingRecipe> recipe) {
        boolean progressed = false;

        if (!resultTemplate.isEmpty()) {
            int droppedOutput = dropMatchingItemsFromInventoryBurst(client, handler, resultTemplate);
            if (droppedOutput > 0) {
                progressed = true;
            }
        }

        for (int i = 0; i < takeAttemptsAfterDrop; i++) {
            if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                break;
            }
            if (!tryTakeOutputForRecipe(client, handler, recipe)) {
                continue;
            }
            progressed = true;
            if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                break;
            }
        }

        return progressed;
    }

    private int dropMatchingItemsFromInventoryBurst(MinecraftClient client,
                                                    PlayerScreenHandler handler,
                                                    ItemStack resultTemplate) {
        if (client.player == null || client.interactionManager == null || resultTemplate.isEmpty()) {
            return 0;
        }

        int droppedSlots = 0;

        PlayerInventory inventory = client.player.getInventory();
        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            ItemStack stack = inventory.main.get(invIndex);
            if (stack.isEmpty()) continue;
            if (!ItemStack.areItemsAndComponentsEqual(stack, resultTemplate)) continue;

            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1) continue;
            if (!handler.getSlot(handlerSlot).hasStack()) continue;

            client.interactionManager.clickSlot(
                    handler.syncId,
                    handlerSlot,
                    1,
                    SlotActionType.THROW,
                    client.player
            );
            droppedSlots++;
        }

        return droppedSlots;
    }

    private int dropIngredientBurst(MinecraftClient client,
                                    PlayerScreenHandler handler,
                                    RecipeEntry<CraftingRecipe> recipe,
                                    int maxDrops) {
        if (client.player == null || client.interactionManager == null || maxDrops <= 0) {
            return 0;
        }

        int dropped = 0;

        for (int i = 0; i < maxDrops; i++) {

            int invIndex = findBestDroppableIngredientSlot(client.player.getInventory(), recipe);
            if (invIndex == -1) {
                break;
            }

            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1) {
                break;
            }

            if (!handler.getSlot(handlerSlot).hasStack()) {
                break;
            }

            client.interactionManager.clickSlot(
                    handler.syncId,
                    handlerSlot,
                    1,
                    SlotActionType.THROW,
                    client.player
            );
            dropped++;
        }

        return dropped;
    }

    private int findBestDroppableIngredientSlot(PlayerInventory inventory,
                                                RecipeEntry<CraftingRecipe> recipe) {
        if (!lockedCraftingPattern.isEmpty()) {
            return findBestDroppablePatternIngredientSlot(inventory);
        }

        List<Ingredient> ingredients = recipe.value().getIngredients();

        int bestIndex = -1;
        int bestCount = -1;

        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            ItemStack stack = inventory.main.get(invIndex);
            if (stack.isEmpty()) continue;

            if (stack.getCount() <= 1) continue;

            if (!matchesAnyIngredient(stack, ingredients)) continue;

            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                bestIndex = invIndex;
            }
        }

        return bestIndex;
    }

    private int findBestDroppablePatternIngredientSlot(PlayerInventory inventory) {
        int bestIndex = -1;
        int bestCount = -1;

        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            ItemStack stack = inventory.main.get(invIndex);
            if (stack.isEmpty()) continue;
            if (stack.getCount() <= 1) continue;
            if (!matchesAnyPatternIngredient(stack)) continue;

            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                bestIndex = invIndex;
            }
        }

        return bestIndex;
    }

    private boolean matchesAnyPatternIngredient(ItemStack stack) {
        for (ItemStack template : lockedCraftingPattern) {
            if (template.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyIngredient(ItemStack stack, List<Ingredient> ingredients) {
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            if (ingredient.test(stack)) return true;
        }
        return false;
    }

    private ItemStack getRecipeResultStack(MinecraftClient client, RecipeEntry<CraftingRecipe> recipe) {
        if (!lockedResultTemplate.isEmpty()) {
            return lockedResultTemplate.copy();
        }
        if (recipe == null) {
            return ItemStack.EMPTY;
        }
        try {
            DynamicRegistryManager registryManager = client.world.getRegistryManager();
            if (client.player == null || !(client.player.currentScreenHandler instanceof PlayerScreenHandler handler)) {
                return ItemStack.EMPTY;
            }
            return recipe.value().craft(getCraftingRecipeInput(handler), registryManager).copy();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private RecipeEntry<CraftingRecipe> getCurrentCraftingRecipe(MinecraftClient client, PlayerScreenHandler handler) {
        if (client.world == null) {
            return null;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return null;
        }

        return tryFindCurrentRecipe(client.world, handler);
    }

    private RecipeEntry<CraftingRecipe> tryFindCurrentRecipe(World world, PlayerScreenHandler handler) {
        try {
            CraftingRecipeInput input = getCraftingRecipeInput(handler);

            RecipeManager recipeManager = world.getRecipeManager();
            Optional<RecipeEntry<CraftingRecipe>> match = recipeManager.getFirstMatch(
                    RecipeType.CRAFTING,
                    input,
                    world
            );

            return match.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private CraftingRecipeInput getCraftingRecipeInput(PlayerScreenHandler handler) {
        List<ItemStack> inputStacks = new ArrayList<>();
        for (int i = 1; i <= CRAFTING_GRID_SIZE; i++) {
            inputStacks.add(handler.getSlot(i).getStack().copy());
        }
        return CraftingRecipeInput.create(CRAFTING_GRID_WIDTH, CRAFTING_GRID_HEIGHT, inputStacks);
    }

    private int playerInventoryIndexToHandlerSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) {
            return 36 + invIndex;
        }
        if (invIndex >= 9 && invIndex <= 35) {
            return 9 + (invIndex - 9);
        }
        return -1;
    }

    private void handleHotkeys(MinecraftClient client, PlayerScreenHandler handler) {

        boolean vDown = QuickCraftConfigs.getSingleCraftHotkey().isKeybindHeld();
        boolean rapidDown = QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld();

        if (vDown && !lastVDown) {
            handleSingleCraft(client, handler);
        }

        if (rapidDown && !lastAltCDown) {
            startRapidCraft(client, handler, false);
        }

        if (!rapidDown && rapidCraftingActive && !rapidCraftStartedByButton) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.backpack.stopped"));
        }

        lastVDown = vDown;
        lastAltCDown = rapidDown;
    }

    private boolean startRapidCraft(MinecraftClient client,
                                    PlayerScreenHandler handler,
                                    boolean fromButton) {
        RecipeEntry<CraftingRecipe> currentRecipe = getCurrentCraftingRecipe(client, handler);
        if (currentRecipe != null || handler.getSlot(OUTPUT_SLOT).hasStack()) {
            lockCurrentRecipe(currentRecipe, handler);
        }

        if (!hasLockedCraftingPlan()) {
            rapidCraftingActive = false;
            rapidCraftStartedByButton = false;
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_recipe"));
            return false;
        }

        rapidCraftingActive = true;
        rapidCraftStartedByButton = fromButton;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        recipeBookResultWaitTicks = 0;
        ingredientDropLocked = false;
        lastObservedOutputSignature = 0;

        sendStatusMessage(client, Text.translatable("quickcraft.message.backpack.started"));
        return true;
    }

    private boolean isCraftingContextValid(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return false;
        }

        if (!(client.currentScreen instanceof InventoryScreen)) {
            return false;
        }

        return client.player.currentScreenHandler instanceof PlayerScreenHandler;
    }

    private int countMatchingItems(PlayerInventory inventory, ItemStack template) {
        if (template.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (ItemStack stack : inventory.main) {
            if (stack.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void sendStatusMessage(MinecraftClient client, Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    private void stopRapidCraft(MinecraftClient client, Text message) {
        if (hasLockedCraftingPlan() && QuickCraftConfigs.isDropCraftResultsOnStopEnabled()) {
            dropCraftResultsAfterStop(client, (PlayerScreenHandler) client.player.currentScreenHandler, lockedRecipe);
        }

        rapidCraftingActive = false;
        rapidCraftStartedByButton = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        recipeBookResultWaitTicks = 0;
        ingredientDropLocked = false;
        lastObservedOutputSignature = 0;
        sendStatusMessage(client, message);
    }

    private void resetAll() {
        rapidCraftingActive = false;
        rapidCraftStartedByButton = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        recipeBookResultWaitTicks = 0;
        lockedRecipe = null;
        lockedCraftingPattern.clear();
        lockedResultTemplate = ItemStack.EMPTY;
        ingredientDropLocked = false;
        lastObservedOutputSignature = 0;
        lastVDown = false;
        lastAltCDown = false;
    }

    private boolean isAltDown(MinecraftClient client) {
        long windowHandle = client.getWindow().getHandle();
        return GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    private boolean isCraftButtonRapidModeHeld(MinecraftClient client) {
        long windowHandle = client.getWindow().getHandle();
        return isAltDown(client)
                && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private void dropCraftResultsAfterStop(MinecraftClient client,
                                           PlayerScreenHandler handler,
                                           RecipeEntry<CraftingRecipe> recipe) {
        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (!resultTemplate.isEmpty()) {
            dropMatchingItemsFromInventoryBurst(client, handler, resultTemplate);
        }
    }
}
