package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.Component;
import net.minecraft.util.context.ContextMap;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;

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

    private RecipeHolder<CraftingRecipe> lockedRecipe = null;

    private List<ItemStack> lockedCraftingPattern = new ArrayList<>();

    private RecipeDisplayId lockedNetworkRecipeId = null;

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
        return INSTANCE.handleCraftButton(Minecraft.getInstance(), rapidCraft);
    }

    private void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isWorkbenchQuickCraftEnabled()) {
            resetAll();
            return;
        }

        if (!isCraftingContextValid(client)) {
            resetAll();
            return;
        }

        CraftingMenu handler = (CraftingMenu) client.player.containerMenu;

        handleHotkeys(client, handler);

        if (rapidCraftingActive && rapidCraftStartedByButton && !isCraftButtonRapidModeHeld(client)) {
            stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.stopped"));
        }

        if (rapidCraftingActive && hasLockedCraftingPlan()) {
            rapidCooldown++;
            if (rapidCooldown >= RAPID_INTERVAL) {
                rapidCooldown = 0;
                processRapidCraftTick(client, handler, lockedRecipe);
            }
        }
    }

    private void processRapidCraftTick(Minecraft client,
                                       CraftingMenu handler,
                                       RecipeHolder<CraftingRecipe> recipe) {
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
                stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
            }
        }

    }

    private boolean runOneCraftSubLoop(Minecraft client,
                                       CraftingMenu handler,
                                       RecipeHolder<CraftingRecipe> recipe) {

        if (client.player == null || client.gameMode == null || client.level == null) {
            return false;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (resultTemplate.isEmpty() && handler.getSlot(OUTPUT_SLOT).hasItem()) {
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getItem().copy();
        }

        if (handler.getSlot(OUTPUT_SLOT).hasItem()) {
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
            if (rapidCraftingActive && !handler.getSlot(OUTPUT_SLOT).hasItem()) {
                craftingResultWaitTicks = CRAFTING_RESULT_WAIT_TICKS;
            }
            tryTakeOutputForRecipe(client, handler, recipe);
            return true;
        }

        return false;
    }

    private boolean waitForCraftingResult(Minecraft client, CraftingMenu handler) {
        if (craftingResultWaitTicks <= 0) {
            return false;
        }

        if (handler.getSlot(OUTPUT_SLOT).hasItem()) {
            craftingResultWaitTicks = 0;
            return false;
        }

        craftingResultWaitTicks--;
        if (craftingResultWaitTicks <= 0) {
            stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
        }
        return true;
    }

    private void handleSingleCraft(Minecraft client, CraftingMenu handler) {

        RecipeHolder<CraftingRecipe> currentRecipe = getCurrentCraftingRecipe(client, handler);
        if (currentRecipe != null || handler.getSlot(OUTPUT_SLOT).hasItem()) {
            lockCurrentRecipe(currentRecipe, handler);
        }

        if (!hasLockedCraftingPlan()) {
            sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.no_recipe"));
            return;
        }

        boolean success = runOneCraftSubLoop(client, handler, lockedRecipe);
        if (!success) {
            sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
        }
    }

    private boolean handleCraftButton(Minecraft client, boolean rapidCraft) {
        if (!isCraftingContextValid(client)) {
            return false;
        }

        CraftingMenu handler = (CraftingMenu) client.player.containerMenu;
        if (rapidCraft) {
            return startRapidCraft(client, handler, true);
        }

        handleSingleCraft(client, handler);
        return true;
    }

    private boolean clickRecipe(Minecraft client,
                                CraftingMenu handler,
                                RecipeHolder<CraftingRecipe> recipe) {
        if (client.player == null || client.gameMode == null || lockedNetworkRecipeId == null) {
            return false;
        }
        try {
            client.gameMode.handlePlaceRecipe(handler.containerId, lockedNetworkRecipeId, true);
            client.player.removeRecipeHighlight(lockedNetworkRecipeId);
            if (rapidCraftingActive && !handler.getSlot(OUTPUT_SLOT).hasItem()) {
                craftingResultWaitTicks = CRAFTING_RESULT_WAIT_TICKS;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean restockCraftingGrid(Minecraft client,
                                        CraftingMenu handler,
                                        RecipeHolder<CraftingRecipe> recipe) {
        if (!shouldManualRestock(recipe)) {
            return clickRecipe(client, handler, recipe);
        }

        return restockCraftingGridFromPattern(client, handler);
    }

    private boolean restockCraftingGridFromPattern(Minecraft client,
                                                   CraftingMenu handler) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        if (!handler.getCarried().isEmpty() || lockedCraftingPattern.isEmpty()) {
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
            ItemStack existing = handler.getSlot(gridSlot).getItem();
            if (!existing.isEmpty()) {
                if (!ItemStack.isSameItemSameComponents(existing, template)) {
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

        return hasPattern && (changed || handler.getSlot(OUTPUT_SLOT).hasItem());
    }

    private boolean moveIngredientStackToGridSlot(Minecraft client,
                                                  CraftingMenu handler,
                                                  int sourceSlot,
                                                  int gridSlot,
                                                  ItemStack template,
                                                  int patternIndex,
                                                  int sameMissingSlots) {
        int sourceCount = handler.getSlot(sourceSlot).getItem().getCount();
        if (sameMissingSlots > 1) {
            return quickCraftDistributeToMissingPatternSlots(client, handler, sourceSlot, patternIndex, template);
        }

        if (sourceCount >= template.getMaxStackSize() && canFillSamePatternSlotsWithFullStacks(handler, template)) {
            return moveFullStackToGridSlot(client, handler, sourceSlot, gridSlot, template);
        }

        if (sourceCount <= sameMissingSlots) {
            return moveOneItemToGridSlot(client, handler, sourceSlot, gridSlot, template);
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                sourceSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        if (handler.getCarried().isEmpty()) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                gridSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        return handler.getCarried().isEmpty()
                && handler.getSlot(gridSlot).hasItem()
                && ItemStack.isSameItemSameComponents(handler.getSlot(gridSlot).getItem(), template);
    }

    private boolean canFillSamePatternSlotsWithFullStacks(CraftingMenu handler,
                                                          ItemStack template) {
        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.isSameItemSameComponents(patternStack, template)) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getItem();
            if (existing.isEmpty()) {
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(existing, template)
                    || existing.getCount() < template.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    private boolean moveFullStackToGridSlot(Minecraft client,
                                            CraftingMenu handler,
                                            int sourceSlot,
                                            int gridSlot,
                                            ItemStack template) {
        client.gameMode.handleContainerInput(
                handler.containerId,
                sourceSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        if (handler.getCarried().isEmpty()) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                gridSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        return handler.getCarried().isEmpty()
                && handler.getSlot(gridSlot).hasItem()
                && ItemStack.isSameItemSameComponents(handler.getSlot(gridSlot).getItem(), template);
    }

    private boolean moveOneItemToGridSlot(Minecraft client,
                                          CraftingMenu handler,
                                          int sourceSlot,
                                          int gridSlot,
                                          ItemStack template) {
        client.gameMode.handleContainerInput(
                handler.containerId,
                sourceSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        if (handler.getCarried().isEmpty()) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                gridSlot,
                1,
                ContainerInput.PICKUP,
                client.player
        );
        boolean placed = handler.getSlot(gridSlot).hasItem()
                && ItemStack.isSameItemSameComponents(handler.getSlot(gridSlot).getItem(), template);
        boolean cursorReturned = returnCursorStack(client, handler, sourceSlot);
        return placed && cursorReturned;
    }

    private boolean quickCraftDistributeToMissingPatternSlots(Minecraft client,
                                                              CraftingMenu handler,
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

        if (handler.getCarried().isEmpty()) {
            return false;
        }

        if (handler.getCarried().getCount() < targetSlots.size()) {
            returnCursorStack(client, handler, sourceSlot);
            return false;
        }

        return distributeCursorStackToMissingPatternSlots(client, handler, sourceSlot, targetSlots, template);
    }

    private int pickUpMergedIngredientStack(Minecraft client,
                                            CraftingMenu handler,
                                            int sourceSlot,
                                            int targetSlotCount) {
        client.gameMode.handleContainerInput(
                handler.containerId,
                sourceSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        if (handler.getCarried().isEmpty()) {
            return -1;
        }

        if (handler.getCarried().getCount() < targetSlotCount
                || handler.getCarried().getCount() < handler.getCarried().getMaxStackSize()) {
            mergeMatchingPlayerInventoryStacksToCursor(client, handler, sourceSlot);
        }

        return sourceSlot;
    }

    private void mergeMatchingPlayerInventoryStacksToCursor(Minecraft client,
                                                            CraftingMenu handler,
                                                            int sourceSlot) {
        if (client.player == null || handler.getCarried().isEmpty()) {
            return;
        }

        // 高版本里 PICKUP_ALL 的并堆行为不够稳定，这里显式把背包里的同类材料并到鼠标上。
        ItemStack template = handler.getCarried().copy();
        int maxCount = template.getMaxStackSize();
        if (template.getCount() >= maxCount) {
            return;
        }

        Inventory inventory = client.player.getInventory();
        for (int invIndex = 0; invIndex < inventory.getContainerSize(); invIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1 || handlerSlot == sourceSlot) {
                continue;
            }

            ItemStack slotStack = handler.getSlot(handlerSlot).getItem();
            if (slotStack.isEmpty() || !ItemStack.isSameItemSameComponents(slotStack, template)) {
                continue;
            }

            client.gameMode.handleContainerInput(
                    handler.containerId,
                    handlerSlot,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );

            ItemStack cursorStack = handler.getCarried();
            if (cursorStack.isEmpty()
                    || !ItemStack.isSameItemSameComponents(cursorStack, template)
                    || cursorStack.getCount() >= maxCount) {
                return;
            }
        }
    }

    private boolean distributeCursorStackToMissingPatternSlots(Minecraft client,
                                                               CraftingMenu handler,
                                                               int sourceSlot,
                                                               List<Integer> targetSlots,
                                                               ItemStack template) {
        if (handler.getCarried().isEmpty()) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                -999,
                AbstractContainerMenu.getQuickcraftMask(0, 0),
                ContainerInput.QUICK_CRAFT,
                client.player
        );
        for (int targetSlot : targetSlots) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    targetSlot,
                    AbstractContainerMenu.getQuickcraftMask(1, 0),
                    ContainerInput.QUICK_CRAFT,
                    client.player
            );
        }
        client.gameMode.handleContainerInput(
                handler.containerId,
                -999,
                AbstractContainerMenu.getQuickcraftMask(2, 0),
                ContainerInput.QUICK_CRAFT,
                client.player
        );

        boolean cursorReturned = returnCursorStack(client, handler, sourceSlot);
        if (!cursorReturned) {
            return false;
        }

        for (int targetSlot : targetSlots) {
            ItemStack placed = handler.getSlot(targetSlot).getItem();
            if (placed.isEmpty() || !ItemStack.isSameItemSameComponents(placed, template)) {
                return false;
            }
        }
        return true;
    }

    private List<Integer> getMissingPatternSlots(CraftingMenu handler,
                                                 int startPatternIndex,
                                                 ItemStack template) {
        List<Integer> slots = new ArrayList<>();
        for (int i = startPatternIndex; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.isSameItemSameComponents(patternStack, template)) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getItem();
            if (existing.isEmpty()) {
                slots.add(1 + i);
            }
        }
        return slots;
    }

    private boolean returnCursorStack(Minecraft client,
                                      CraftingMenu handler,
                                      int preferredSlot) {
        if (handler.getCarried().isEmpty()) {
            return true;
        }

        if (canAcceptStack(handler.getSlot(preferredSlot).getItem(), handler.getCarried())) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    preferredSlot,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
        }
        if (handler.getCarried().isEmpty()) {
            return true;
        }

        int returnSlot = findAcceptingPlayerInventoryHandlerSlot(client.player.getInventory(), handler, handler.getCarried());
        if (returnSlot == -1) {
            return false;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                returnSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        return handler.getCarried().isEmpty();
    }

    private boolean canAcceptStack(ItemStack targetStack, ItemStack cursorStack) {
        return targetStack.isEmpty()
                || (ItemStack.isSameItemSameComponents(targetStack, cursorStack)
                && targetStack.getCount() + cursorStack.getCount() <= targetStack.getMaxStackSize());
    }

    private int countMissingPatternSlots(CraftingMenu handler,
                                         int startPatternIndex,
                                         ItemStack template) {
        int total = 0;
        for (int i = startPatternIndex; i < lockedCraftingPattern.size(); i++) {
            ItemStack patternStack = lockedCraftingPattern.get(i);
            if (patternStack.isEmpty() || !ItemStack.isSameItemSameComponents(patternStack, template)) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getItem();
            if (existing.isEmpty()) {
                total++;
            }
        }
        return total;
    }

    private boolean hasItemsForMissingPatternSlots(Inventory inventory,
                                                   CraftingMenu handler) {
        List<ItemStack> availableStacks = new ArrayList<>();
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (!stack.isEmpty()) {
                availableStacks.add(stack.copy());
            }
        }

        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getItem();
            if (!existing.isEmpty()) {
                if (!ItemStack.isSameItemSameComponents(existing, template)) {
                    return false;
                }
                continue;
            }

            int availableIndex = findMatchingStackIndex(availableStacks, template);
            if (availableIndex == -1) {
                return false;
            }
            availableStacks.get(availableIndex).shrink(1);
        }

        return true;
    }

    private boolean isManualPatternMissingItems(CraftingMenu handler) {
        if (lockedCraftingPattern.isEmpty()) {
            return false;
        }

        for (int i = 0; i < lockedCraftingPattern.size(); i++) {
            ItemStack template = lockedCraftingPattern.get(i);
            if (template.isEmpty()) {
                continue;
            }

            ItemStack existing = handler.getSlot(1 + i).getItem();
            if (existing.isEmpty()) {
                return true;
            }
            if (!ItemStack.isSameItemSameComponents(existing, template)) {
                return false;
            }
        }

        return false;
    }

    private int findMatchingStackIndex(List<ItemStack> stacks, ItemStack template) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                return i;
            }
        }
        return -1;
    }

    private int findMatchingPlayerInventoryHandlerSlot(Inventory inventory,
                                                       CraftingMenu handler,
                                                       ItemStack template) {
        int bestSlot = -1;
        int bestCount = -1;
        for (int invIndex = 0; invIndex < inventory.getNonEquipmentItems().size(); invIndex++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(invIndex);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) {
                continue;
            }

            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot != -1
                    && !QuickContainerLock.isLockedSlot(handler, handlerSlot)
                    && handler.getSlot(handlerSlot).hasItem()) {
                int stackCount = handler.getSlot(handlerSlot).getItem().getCount();
                if (stackCount > bestCount) {
                    bestCount = stackCount;
                    bestSlot = handlerSlot;
                }
            }
        }
        return bestSlot;
    }

    private int findAcceptingPlayerInventoryHandlerSlot(Inventory inventory,
                                                        CraftingMenu handler,
                                                        ItemStack cursorStack) {
        for (int invIndex = 0; invIndex < inventory.getNonEquipmentItems().size(); invIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1) {
                continue;
            }
            if (QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }

            ItemStack stack = handler.getSlot(handlerSlot).getItem();
            if (canAcceptStack(stack, cursorStack)) {
                return handlerSlot;
            }
        }
        return -1;
    }

    private boolean canPlayerInventoryAccept(CraftingMenu handler, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        for (int invIndex = 0; invIndex < 36; invIndex++) {
            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }

            Slot slot = handler.getSlot(handlerSlot);
            ItemStack existing = slot.getItem();
            if (slot.mayPlace(stack) && canAcceptStack(existing, stack)) {
                return true;
            }
        }

        return false;
    }

    private boolean shouldManualRestock(RecipeHolder<CraftingRecipe> recipe) {
        if (lockedNetworkRecipeId == null) {
            return true;
        }
        if (recipe == null) {
            return false;
        }
        try {
            return recipe.value().isSpecial();
        } catch (Throwable t) {
            return true;
        }
    }

    private void lockCurrentRecipe(RecipeHolder<CraftingRecipe> recipe,
                                   CraftingMenu handler) {
        lockedRecipe = recipe;
        lockedCraftingPattern = snapshotCraftingGrid(handler);
        lockedResultTemplate = handler.getSlot(OUTPUT_SLOT).hasItem()
                ? handler.getSlot(OUTPUT_SLOT).getItem().copy()
                : ItemStack.EMPTY;
        lockedNetworkRecipeId = findCurrentNetworkRecipeId(Minecraft.getInstance(), handler, lockedResultTemplate);
    }

    private boolean hasLockedCraftingPlan() {
        return !lockedCraftingPattern.isEmpty() && !lockedResultTemplate.isEmpty();
    }

    private RecipeDisplayId findCurrentNetworkRecipeId(Minecraft client,
                                                       CraftingMenu handler,
                                                       ItemStack resultTemplate) {
        if (client == null || client.player == null || client.level == null || resultTemplate.isEmpty()) {
            return null;
        }

        StackedItemContents finder = new StackedItemContents();
        client.player.getInventory().fillStackedContents(finder);
        handler.fillCraftSlotsStackedContents(finder);
        ContextMap context = SlotDisplayContext.fromLevel(client.level);

        for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
            collection.selectRecipes(finder, display -> true);
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (!collection.isCraftable(entry.id())) {
                    continue;
                }
                for (ItemStack stack : entry.resultItems(context)) {
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
                && ItemStack.isSameItemSameComponents(displayed, template);
    }

    private List<ItemStack> snapshotCraftingGrid(CraftingMenu handler) {
        List<ItemStack> pattern = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            ItemStack stack = handler.getSlot(i).getItem().copy();
            if (!stack.isEmpty()) {
                stack.setCount(1);
            }
            pattern.add(stack);
        }
        return pattern;
    }

    private boolean tryQuickMoveOutput(Minecraft client, CraftingMenu handler) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getItem().copy();
        int beforeResultCount = countMatchingItems(client.player.getInventory(), before);
        client.gameMode.handleContainerInput(
                handler.containerId,
                OUTPUT_SLOT,
                0,
                ContainerInput.QUICK_MOVE,
                client.player
        );

        ItemStack after = handler.getSlot(OUTPUT_SLOT).getItem();
        boolean moved = after.isEmpty()
                || !ItemStack.isSameItemSameComponents(before, after)
                || after.getCount() != before.getCount()
                || countMatchingItems(client.player.getInventory(), before) > beforeResultCount;
        return moved;
    }

    private boolean tryTakeOutputForRecipe(Minecraft client,
                                           CraftingMenu handler,
                                           RecipeHolder<CraftingRecipe> recipe) {

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

    private boolean tryTakeOneOutput(Minecraft client, CraftingMenu handler) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getItem().copy();
        int returnSlot = findAcceptingPlayerInventoryHandlerSlot(
                client.player.getInventory(),
                handler,
                before
        );
        if (returnSlot == -1) {
            return false;
        }

        int beforeResultCount = countMatchingItems(client.player.getInventory(), before);
        client.gameMode.handleContainerInput(
                handler.containerId,
                OUTPUT_SLOT,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        boolean pickedOutput = !handler.getCarried().isEmpty()
                && ItemStack.isSameItemSameComponents(handler.getCarried(), before);
        if (pickedOutput) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    returnSlot,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
        }

        ItemStack after = handler.getSlot(OUTPUT_SLOT).getItem();
        return handler.getCarried().isEmpty()
                && (after.isEmpty()
                || !ItemStack.isSameItemSameComponents(before, after)
                || after.getCount() != before.getCount()
                || countMatchingItems(client.player.getInventory(), before) > beforeResultCount);
    }

    private boolean hasUnevenManualPatternStacks(CraftingMenu handler) {
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
                if (otherTemplate.isEmpty() || !ItemStack.isSameItemSameComponents(template, otherTemplate)) {
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

    private int getPatternSlotCount(CraftingMenu handler, int patternIndex, ItemStack template) {
        ItemStack stack = handler.getSlot(1 + patternIndex).getItem();
        if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) {
            return 0;
        }
        return stack.getCount();
    }

    private int dropMatchingResultsFromInventory(Minecraft client,
                                                 CraftingMenu handler,
                                                 ItemStack resultTemplate) {
        if (client.player == null || client.gameMode == null || resultTemplate.isEmpty()) {
            return 0;
        }

        int droppedSlots = 0;

        Inventory inventory = client.player.getInventory();
        for (int invIndex = 0; invIndex < inventory.getNonEquipmentItems().size(); invIndex++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(invIndex);
            if (stack.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(stack, resultTemplate)) continue;

            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1) continue;
            if (QuickContainerLock.isLockedSlot(handler, handlerSlot)) continue;
            if (!handler.getSlot(handlerSlot).hasItem()) continue;
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    handlerSlot,
                    1,
                    ContainerInput.THROW,
                    client.player
            );
            droppedSlots++;
        }

        return droppedSlots;
    }

    private boolean throwCraftingOutput(Minecraft client, CraftingMenu handler) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
            return false;
        }

        int attempts = getOutputThrowBurstAttempts(handler);
        for (int attempt = 0; attempt < attempts; attempt++) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    OUTPUT_SLOT,
                    1,
                    ContainerInput.THROW,
                    client.player
            );
        }
        return true;
    }

    private int getOutputThrowBurstAttempts(CraftingMenu handler) {
        int attempts = MAX_OUTPUT_THROW_BURST;
        boolean hasIngredient = false;

        for (int slotId = 1; slotId <= 9; slotId++) {
            ItemStack stack = handler.getSlot(slotId).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            hasIngredient = true;
            attempts = Math.min(attempts, stack.getCount());
        }

        return hasIngredient ? attempts : 1;
    }

    private ItemStack getRecipeResultStack(Minecraft client, RecipeHolder<CraftingRecipe> recipe) {
        if (!lockedResultTemplate.isEmpty()) {
            return lockedResultTemplate.copy();
        }
        if (recipe == null) {
            return ItemStack.EMPTY;
        }
        try {
            if (client.player == null || !(client.player.containerMenu instanceof CraftingMenu handler)) {
                return ItemStack.EMPTY;
            }
            return recipe.value().assemble(getCraftingRecipeInput(handler)).copy();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private RecipeHolder<CraftingRecipe> getCurrentCraftingRecipe(Minecraft client, CraftingMenu handler) {
        if (client.level == null) {
            return null;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
            return null;
        }

        // 26.1 客户端只同步 RecipeDisplayEntry；普通配方由配方书 ID 驱动，特殊配方使用锁定的格子快照。
        return null;
    }

    private CraftingInput getCraftingRecipeInput(CraftingMenu handler) {
        List<ItemStack> inputStacks = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            inputStacks.add(handler.getSlot(i).getItem().copy());
        }
        return CraftingInput.of(3, 3, inputStacks);
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

    private void handleHotkeys(Minecraft client, CraftingMenu handler) {

        boolean vDown = QuickCraftConfigs.getSingleCraftHotkey().isKeybindHeld();
        boolean rapidDown = QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld();

        if (vDown && !lastVDown) {
            handleSingleCraft(client, handler);
        }

        if (rapidDown && !lastAltCDown) {
            startRapidCraft(client, handler, false);
        }

        if (!rapidDown && rapidCraftingActive && !rapidCraftStartedByButton) {
            stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.stopped"));
        }

        lastVDown = vDown;
        lastAltCDown = rapidDown;
    }

    private boolean startRapidCraft(Minecraft client,
                                    CraftingMenu handler,
                                    boolean fromButton) {
        RecipeHolder<CraftingRecipe> currentRecipe = getCurrentCraftingRecipe(client, handler);
        if (currentRecipe != null || handler.getSlot(OUTPUT_SLOT).hasItem()) {
            lockCurrentRecipe(currentRecipe, handler);
        }

        if (!hasLockedCraftingPlan()) {
            rapidCraftingActive = false;
            rapidCraftStartedByButton = false;
            sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.no_recipe"));
            return false;
        }

        rapidCraftingActive = true;
        rapidCraftStartedByButton = fromButton;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        craftingResultWaitTicks = 0;
        sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.started"));
        return true;
    }

    private boolean isCraftingContextValid(Minecraft client) {
        if (client.player == null || client.level == null) {
            return false;
        }

        if (!(client.screen instanceof CraftingScreen)) {
            return false;
        }

        return client.player.containerMenu instanceof CraftingMenu;
    }

    private int countMatchingItems(Inventory inventory, ItemStack template) {
        if (template.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (stack.isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void sendStatusMessage(Minecraft client, Component message) {
        if (client.player != null) {
            client.player.sendOverlayMessage(message);
        }
    }

    private void stopRapidCraft(Minecraft client, Component message) {
        if (hasLockedCraftingPlan() && QuickCraftConfigs.isDropCraftResultsOnStopEnabled()) {
            dropCraftResultsAfterStop(client, (CraftingMenu) client.player.containerMenu, lockedRecipe);
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

    private boolean isAltDown(Minecraft client) {
        long windowHandle = client.getWindow().handle();
        return GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    private boolean isCraftButtonRapidModeHeld(Minecraft client) {
        long windowHandle = client.getWindow().handle();
        return isAltDown(client)
                && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private void dropCraftResultsAfterStop(Minecraft client,
                                           CraftingMenu handler,
                                           RecipeHolder<CraftingRecipe> recipe) {
        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (!resultTemplate.isEmpty()) {
            dropMatchingResultsFromInventory(client, handler, resultTemplate);
        }
    }

}
