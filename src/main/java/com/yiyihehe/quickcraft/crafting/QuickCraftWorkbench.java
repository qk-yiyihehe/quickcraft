package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeFinder;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.context.ContextParameterMap;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工作台快速合成：普通配方走原版配方书点击，特殊配方按锁定格子手动补料。
 */
public class QuickCraftWorkbench implements ClientModInitializer {
    private static QuickCraftWorkbench INSTANCE;

    private static final int RAPID_INTERVAL = 1;

    private static final int MAX_CONSECUTIVE_FAILURES =3;

    // 配方书请求只发网络包；最多等 10 Tick，但产物一到就立即继续。
    private static final int CRAFTING_RESULT_WAIT_TICKS = 10;

    private static final int OUTPUT_SLOT = 0;

    // 单轮最多处理一组原料，避免异常合成格导致无界发送产物槽点击。
    private static final int MAX_OUTPUT_THROW_BURST = 64;

    private boolean lastVDown = false;

    private boolean lastAltCDown = false;

    private boolean rapidCraftingActive = false;
    private boolean rapidCraftStartedByButton = false;

    private int rapidCooldown = 0;

    private int consecutiveFailures = 0;

    private int craftingResultWaitTicks = 0;

    private RecipeEntry<CraftingRecipe> lockedRecipe = null;

    private List<ItemStack> lockedCraftingPattern = new ArrayList<>();

    private NetworkRecipeId lockedNetworkRecipeId = null;

    private ItemStack lockedResultTemplate = ItemStack.EMPTY;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean handleWorkbenchCraftButton(boolean rapidCraft) {
        if (INSTANCE == null) {
            return false;
        }
        return INSTANCE.handleCraftButton(MinecraftClient.getInstance(), rapidCraft);
    }

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isWorkbenchQuickCraftEnabled()) {
            resetAll();
            return;
        }

        if (!isCraftingContextValid(client)) {
            resetAll();
            return;
        }

        CraftingScreenHandler handler = (CraftingScreenHandler) client.player.currentScreenHandler;

        handleHotkeys(client, handler);

        if (rapidCraftingActive && rapidCraftStartedByButton && !isCraftButtonRapidModeHeld(client)) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.stopped"));
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
                                       CraftingScreenHandler handler,
                                       RecipeEntry<CraftingRecipe> recipe) {
        if (waitForCraftingResult(client, handler)) {
            return;
        }

        boolean anyProgress = false;
        int craftLoopsPerTick = QuickCraftConfigs.getCraftLoopsPerTick();
        for (int loop = 0; loop < craftLoopsPerTick; loop++) {
            boolean progressed = runOneCraftSubLoop(client, handler, recipe);
            if (progressed) {
                anyProgress = true;
            }
            if (!rapidCraftingActive || craftingResultWaitTicks > 0) {
                break;
            }
            if (!progressed) {
                break;
            }
        }

        if (anyProgress) {
            consecutiveFailures = 0;
        } else {
            consecutiveFailures++;

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
            }
        }

    }

    private boolean runOneCraftSubLoop(MinecraftClient client,
                                       CraftingScreenHandler handler,
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

            if (canPlayerInventoryAccept(handler, resultTemplate)) {
                return tryTakeOutputForRecipe(client, handler, recipe);
            }

            int droppedOutput = dropMatchingResultsFromInventory(
                    client,
                    handler,
                    resultTemplate
            );
            if (droppedOutput > 0) {
                tryTakeOutputForRecipe(client, handler, recipe);
                return true;
            }

            return throwCraftingOutput(client, handler);
        }

        if (restockCraftingGrid(client, handler, recipe)) {
            if (rapidCraftingActive && !handler.getSlot(OUTPUT_SLOT).hasStack()) {
                craftingResultWaitTicks = CRAFTING_RESULT_WAIT_TICKS;
            }
            tryTakeOutputForRecipe(client, handler, recipe);
            return true;
        }

        return false;
    }

    private boolean waitForCraftingResult(MinecraftClient client, CraftingScreenHandler handler) {
        if (craftingResultWaitTicks <= 0) {
            return false;
        }

        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
            craftingResultWaitTicks = 0;
            return false;
        }

        craftingResultWaitTicks--;
        if (craftingResultWaitTicks <= 0) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
        }
        return true;
    }

    private void handleSingleCraft(MinecraftClient client, CraftingScreenHandler handler) {

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
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
        }
    }

    private boolean handleCraftButton(MinecraftClient client, boolean rapidCraft) {
        if (!isCraftingContextValid(client)) {
            return false;
        }

        CraftingScreenHandler handler = (CraftingScreenHandler) client.player.currentScreenHandler;
        if (rapidCraft) {
            return startRapidCraft(client, handler, true);
        }

        handleSingleCraft(client, handler);
        return true;
    }

    private boolean clickRecipe(MinecraftClient client,
                                CraftingScreenHandler handler,
                                RecipeEntry<CraftingRecipe> recipe) {
        if (client.player == null || client.interactionManager == null || lockedNetworkRecipeId == null) {
            return false;
        }
        try {
            client.interactionManager.clickRecipe(handler.syncId, lockedNetworkRecipeId, true);
            client.player.onRecipeDisplayed(lockedNetworkRecipeId);
            if (rapidCraftingActive && !handler.getSlot(OUTPUT_SLOT).hasStack()) {
                craftingResultWaitTicks = CRAFTING_RESULT_WAIT_TICKS;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean restockCraftingGrid(MinecraftClient client,
                                        CraftingScreenHandler handler,
                                        RecipeEntry<CraftingRecipe> recipe) {
        if (!shouldManualRestock(recipe)) {
            return clickRecipe(client, handler, recipe);
        }

        return restockCraftingGridFromPattern(client, handler);
    }

    private boolean restockCraftingGridFromPattern(MinecraftClient client,
                                                   CraftingScreenHandler handler) {
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
                                                  CraftingScreenHandler handler,
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

    private boolean canFillSamePatternSlotsWithFullStacks(CraftingScreenHandler handler,
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
                                            CraftingScreenHandler handler,
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
                                          CraftingScreenHandler handler,
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
                                                              CraftingScreenHandler handler,
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
                                            CraftingScreenHandler handler,
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
            mergeMatchingPlayerInventoryStacksToCursor(client, handler, sourceSlot);
        }

        return sourceSlot;
    }

    private void mergeMatchingPlayerInventoryStacksToCursor(MinecraftClient client,
                                                            CraftingScreenHandler handler,
                                                            int sourceSlot) {
        if (client.player == null || handler.getCursorStack().isEmpty()) {
            return;
        }

        // 高版本里 PICKUP_ALL 的并堆行为不够稳定，这里显式把背包里的同类材料并到鼠标上。
        ItemStack template = handler.getCursorStack().copy();
        int maxCount = template.getMaxCount();
        if (template.getCount() >= maxCount) {
            return;
        }

        PlayerInventory inventory = client.player.getInventory();
        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1 || handlerSlot == sourceSlot) {
                continue;
            }

            ItemStack slotStack = handler.getSlot(handlerSlot).getStack();
            if (slotStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(slotStack, template)) {
                continue;
            }

            client.interactionManager.clickSlot(
                    handler.syncId,
                    handlerSlot,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );

            ItemStack cursorStack = handler.getCursorStack();
            if (cursorStack.isEmpty()
                    || !ItemStack.areItemsAndComponentsEqual(cursorStack, template)
                    || cursorStack.getCount() >= maxCount) {
                return;
            }
        }
    }

    private boolean distributeCursorStackToMissingPatternSlots(MinecraftClient client,
                                                               CraftingScreenHandler handler,
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

    private List<Integer> getMissingPatternSlots(CraftingScreenHandler handler,
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
                                      CraftingScreenHandler handler,
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

    private int countMissingPatternSlots(CraftingScreenHandler handler,
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
                                                   CraftingScreenHandler handler) {
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

    private boolean isManualPatternMissingItems(CraftingScreenHandler handler) {
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
                                                       CraftingScreenHandler handler,
                                                       ItemStack template) {
        int bestSlot = -1;
        int bestCount = -1;
        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            ItemStack stack = inventory.main.get(invIndex);
            if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, template)) {
                continue;
            }

            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot != -1
                    && !QuickContainerLock.isLockedSlot(handler, handlerSlot)
                    && handler.getSlot(handlerSlot).hasStack()) {
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
                                                        CraftingScreenHandler handler,
                                                        ItemStack cursorStack) {
        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1) {
                continue;
            }
            if (QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }

            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (canAcceptStack(stack, cursorStack)) {
                return handlerSlot;
            }
        }
        return -1;
    }

    private boolean canPlayerInventoryAccept(CraftingScreenHandler handler, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        for (int invIndex = 0; invIndex < 36; invIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }

            Slot slot = handler.getSlot(handlerSlot);
            ItemStack existing = slot.getStack();
            if (slot.canInsert(stack) && canAcceptStack(existing, stack)) {
                return true;
            }
        }

        return false;
    }

    private boolean shouldManualRestock(RecipeEntry<CraftingRecipe> recipe) {
        if (lockedNetworkRecipeId == null) {
            return true;
        }
        if (recipe == null) {
            return false;
        }
        try {
            return recipe.value().isIgnoredInRecipeBook();
        } catch (Throwable t) {
            return true;
        }
    }

    private void lockCurrentRecipe(RecipeEntry<CraftingRecipe> recipe,
                                   CraftingScreenHandler handler) {
        lockedRecipe = recipe;
        lockedCraftingPattern = snapshotCraftingGrid(handler);
        lockedResultTemplate = handler.getSlot(OUTPUT_SLOT).hasStack()
                ? handler.getSlot(OUTPUT_SLOT).getStack().copy()
                : ItemStack.EMPTY;
        lockedNetworkRecipeId = findCurrentNetworkRecipeId(MinecraftClient.getInstance(), handler, lockedResultTemplate);
    }

    private boolean hasLockedCraftingPlan() {
        return !lockedCraftingPattern.isEmpty() && !lockedResultTemplate.isEmpty();
    }

    private NetworkRecipeId findCurrentNetworkRecipeId(MinecraftClient client,
                                                       CraftingScreenHandler handler,
                                                       ItemStack resultTemplate) {
        if (client == null || client.player == null || client.world == null || resultTemplate.isEmpty()) {
            return null;
        }

        RecipeFinder finder = new RecipeFinder();
        client.player.getInventory().populateRecipeFinder(finder);
        handler.populateRecipeFinder(finder);
        ContextParameterMap context = SlotDisplayContexts.createParameters(client.world);

        for (RecipeResultCollection collection : client.player.getRecipeBook().getOrderedResults()) {
            collection.populateRecipes(finder, display -> true);
            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                if (!collection.isCraftable(entry.id())) {
                    continue;
                }
                for (ItemStack stack : entry.getStacks(context)) {
                    if (isSameRecipeBookResult(stack, resultTemplate)) {
                        return entry.id();
                    }
                }
            }
        }

        return null;
    }

    private boolean isSameRecipeBookResult(ItemStack displayed, ItemStack template) {
        return !displayed.isEmpty()
                && !template.isEmpty()
                && ItemStack.areItemsAndComponentsEqual(displayed, template);
    }

    private List<ItemStack> snapshotCraftingGrid(CraftingScreenHandler handler) {
        List<ItemStack> pattern = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            ItemStack stack = handler.getSlot(i).getStack().copy();
            if (!stack.isEmpty()) {
                stack.setCount(1);
            }
            pattern.add(stack);
        }
        return pattern;
    }

    private boolean tryQuickMoveOutput(MinecraftClient client, CraftingScreenHandler handler) {
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
        boolean moved = after.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(before, after)
                || after.getCount() != before.getCount()
                || countMatchingItems(client.player.getInventory(), before) > beforeResultCount;
        return moved;
    }

    private boolean tryTakeOutputForRecipe(MinecraftClient client,
                                           CraftingScreenHandler handler,
                                           RecipeEntry<CraftingRecipe> recipe) {

        boolean moved;
        if (shouldManualRestock(recipe)) {
            if (isManualPatternMissingItems(handler)) {
                return false;
            }
            if (hasUnevenManualPatternStacks(handler)) {
                moved = tryTakeOneOutput(client, handler);
            } else {
                moved = tryQuickMoveOutput(client, handler);
            }
        } else {
            moved = tryQuickMoveOutput(client, handler);
        }

        if (moved && rapidCraftingActive) {
            dropMatchingResultsFromInventory(
                    client,
                    handler,
                    getRecipeResultStack(client, recipe)
            );
        }
        return moved;
    }

    private boolean tryTakeOneOutput(MinecraftClient client, CraftingScreenHandler handler) {
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

    private boolean hasUnevenManualPatternStacks(CraftingScreenHandler handler) {
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

    private int getPatternSlotCount(CraftingScreenHandler handler, int patternIndex, ItemStack template) {
        ItemStack stack = handler.getSlot(1 + patternIndex).getStack();
        if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, template)) {
            return 0;
        }
        return stack.getCount();
    }

    private int dropMatchingResultsFromInventory(MinecraftClient client,
                                                 CraftingScreenHandler handler,
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
            if (QuickContainerLock.isLockedSlot(handler, handlerSlot)) continue;
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

    private boolean throwCraftingOutput(MinecraftClient client, CraftingScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return false;
        }

        int attempts = getOutputThrowBurstAttempts(handler);
        for (int attempt = 0; attempt < attempts; attempt++) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    OUTPUT_SLOT,
                    1,
                    SlotActionType.THROW,
                    client.player
            );
        }
        return true;
    }

    private int getOutputThrowBurstAttempts(CraftingScreenHandler handler) {
        int attempts = MAX_OUTPUT_THROW_BURST;
        boolean hasIngredient = false;

        for (int slotId = 1; slotId <= 9; slotId++) {
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            hasIngredient = true;
            attempts = Math.min(attempts, stack.getCount());
        }

        return hasIngredient ? attempts : 1;
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
            if (client.player == null || !(client.player.currentScreenHandler instanceof CraftingScreenHandler handler)) {
                return ItemStack.EMPTY;
            }
            return recipe.value().craft(getCraftingRecipeInput(handler), registryManager).copy();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private RecipeEntry<CraftingRecipe> getCurrentCraftingRecipe(MinecraftClient client, CraftingScreenHandler handler) {
        if (client.world == null) {
            return null;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return null;
        }

        return tryFindCurrentRecipe(client.world, handler);
    }

    private RecipeEntry<CraftingRecipe> tryFindCurrentRecipe(World world, CraftingScreenHandler handler) {
        try {
            CraftingRecipeInput input = getCraftingRecipeInput(handler);

            if (!(world.getRecipeManager() instanceof ServerRecipeManager recipeManager)) {
                return null;
            }
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

    private CraftingRecipeInput getCraftingRecipeInput(CraftingScreenHandler handler) {
        List<ItemStack> inputStacks = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            inputStacks.add(handler.getSlot(i).getStack().copy());
        }
        return CraftingRecipeInput.create(3, 3, inputStacks);
    }

    private int playerInventoryIndexToHandlerSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) {
            return 37 + invIndex;
        }
        if (invIndex >= 9 && invIndex <= 35) {
            return 10 + (invIndex - 9);
        }
        return -1;
    }

    private void handleHotkeys(MinecraftClient client, CraftingScreenHandler handler) {

        boolean vDown = QuickCraftConfigs.getSingleCraftHotkey().isKeybindHeld();
        boolean rapidDown = QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld();

        if (vDown && !lastVDown) {
            handleSingleCraft(client, handler);
        }

        if (rapidDown && !lastAltCDown) {
            startRapidCraft(client, handler, false);
        }

        if (!rapidDown && rapidCraftingActive && !rapidCraftStartedByButton) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.stopped"));
        }

        lastVDown = vDown;
        lastAltCDown = rapidDown;
    }

    private boolean startRapidCraft(MinecraftClient client,
                                    CraftingScreenHandler handler,
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
        craftingResultWaitTicks = 0;
        sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.started"));
        return true;
    }

    private boolean isCraftingContextValid(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return false;
        }

        if (!(client.currentScreen instanceof CraftingScreen)) {
            return false;
        }

        return client.player.currentScreenHandler instanceof CraftingScreenHandler;
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
            dropCraftResultsAfterStop(client, (CraftingScreenHandler) client.player.currentScreenHandler, lockedRecipe);
        }

        rapidCraftingActive = false;
        rapidCraftStartedByButton = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        craftingResultWaitTicks = 0;
        sendStatusMessage(client, message);
    }

    private void resetAll() {
        rapidCraftingActive = false;
        rapidCraftStartedByButton = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        craftingResultWaitTicks = 0;
        lockedRecipe = null;
        lockedCraftingPattern.clear();
        lockedNetworkRecipeId = null;
        lockedResultTemplate = ItemStack.EMPTY;
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
                                           CraftingScreenHandler handler,
                                           RecipeEntry<CraftingRecipe> recipe) {
        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (!resultTemplate.isEmpty()) {
            dropMatchingResultsFromInventory(client, handler, resultTemplate);
        }
    }

}
