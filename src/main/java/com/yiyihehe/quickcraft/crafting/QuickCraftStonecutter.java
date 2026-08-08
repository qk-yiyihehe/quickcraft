package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/**
 * 切石机快速合成：复用原版选配方与槽位点击交互。
 */
public class QuickCraftStonecutter implements ClientModInitializer {
    private static QuickCraftStonecutter INSTANCE;

    private static final int RAPID_INTERVAL = 1;
    private static final int OUTPUT_TAKE_ATTEMPTS_AFTER_DROP = 2;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final int MAX_NO_PROGRESS_TICKS = 3;
    private static final int INPUT_SLOT = 0;
    private static final int OUTPUT_SLOT = 1;
    private static final int MAX_FAKE_PROGRESS = 3;
    // 40 tick 给普通多人服务器约两秒时间返回权威切石机槽位。
    private static final int SERVER_SYNC_TIMEOUT_TICKS = 40;

    private boolean lastVDown = false;
    private boolean lastAltCDown = false;
    private boolean rapidCraftingActive = false;
    private boolean rapidCraftStartedByButton = false;
    private int rapidCooldown = 0;
    private int consecutiveFailures = 0;
    private RecipeHolder<StonecutterRecipe> lockedRecipe = null;
    private int lockedRecipeIndex = -1;
    private ItemStack lockedInputTemplate = ItemStack.EMPTY;
    private ItemStack lockedResultTemplate = ItemStack.EMPTY;
    private int noProgressTicks = 0;
    private int lastResultCount = -1;
    private int lastEmptySlots = -1;
    private boolean ingredientDropLocked = false;
    private int lastObservedOutputSignature = 0;
    private int fakeProgressTicks = 0;
    private boolean singleCraftPending = false;
    private int singleCraftWaitTicks = 0;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean handleStonecutterCraftButton(boolean rapidCraft) {
        if (INSTANCE == null) {
            return false;
        }
        return INSTANCE.handleCraftButton(Minecraft.getInstance(), rapidCraft);
    }

    private void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isStonecutterQuickCraftEnabled()) {
            resetAll();
            return;
        }

        if (!isCraftingContextValid(client)) {
            resetAll();
            return;
        }

        StonecutterMenu handler = (StonecutterMenu) client.player.containerMenu;
        updateIngredientDropLock(handler);
        handleHotkeys(client, handler);
        processPendingSingleCraft(client, handler);

        if (rapidCraftingActive && rapidCraftStartedByButton && !isCraftButtonRapidModeHeld(client)) {
            stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.stopped"));
        }

        if (rapidCraftingActive && hasLockedSelection()) {
            rapidCooldown++;
            if (rapidCooldown >= RAPID_INTERVAL) {
                rapidCooldown = 0;
                processRapidCraftTick(client, handler, lockedRecipe);
            }
        }
    }

    private void processRapidCraftTick(Minecraft client,
                                       StonecutterMenu handler,
                                       RecipeHolder<StonecutterRecipe> recipe) {
        boolean anyProgress = false;
        int craftLoopsPerTick = QuickCraftConfigs.getCraftLoopsPerTick();

        for (int loop = 0; loop < craftLoopsPerTick; loop++) {
            boolean progressed = runOneCraftSubLoop(client, handler, recipe);
            if (progressed) {
                anyProgress = true;
            }
            if (!rapidCraftingActive) {
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
            noProgressTicks = 0;
            refreshProgressSnapshot(client, recipe);
        } else {
            consecutiveFailures++;
            detectNoProgressAndMaybeStop(client, recipe);

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
            }
        }
    }

    private boolean runOneCraftSubLoop(Minecraft client,
                                       StonecutterMenu handler,
                                       RecipeHolder<StonecutterRecipe> recipe) {
        if (client.player == null || client.gameMode == null || client.level == null) {
            return false;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (resultTemplate.isEmpty() && handler.getSlot(OUTPUT_SLOT).hasItem()) {
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getItem().copy();
        }

        if (handler.getSlot(OUTPUT_SLOT).hasItem()) {
            if (tryQuickMoveOutput(client, handler)) {
                fakeProgressTicks = 0;
                return true;
            }

            if (!resultTemplate.isEmpty()) {
                int droppedOutput = dropMatchingItemsFromInventoryBurst(client, handler, resultTemplate, 1);
                if (droppedOutput > 0 && tryQuickMoveOutput(client, handler)) {
                    fakeProgressTicks = 0;
                    return true;
                }
            }

            if (!hasMatchingUnlockedItemInInventory(client.player.getInventory(), handler, resultTemplate)) {
                if (fakeProgressTicks >= MAX_FAKE_PROGRESS) {
                    return false;
                }

                int droppedIngredient = dropIngredientBurst(client, handler, recipe, 1);
                if (droppedIngredient > 0) {
                    if (tryQuickMoveOutput(client, handler)) {
                        fakeProgressTicks = 0;
                        return true;
                    }

                    fakeProgressTicks++;
                    return true;
                }
            }

            return false;
        }

        if (!handler.getSlot(INPUT_SLOT).hasItem()) {
            if (!quickMoveIngredientToInput(client, handler, recipe)) {
                if (rapidCraftingActive) {
                    stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
                }
                return false;
            }
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasItem() && !clickSelectedRecipe(client, handler, recipe)) {
            return false;
        }

        if (tryQuickMoveOutput(client, handler)) {
            fakeProgressTicks = 0;
            return true;
        }

        return handler.getSlot(OUTPUT_SLOT).hasItem();
    }

    private boolean resolveOutputSlotBlockageStrict(Minecraft client,
                                                    StonecutterMenu handler,
                                                    ItemStack resultTemplate,
                                                    RecipeHolder<StonecutterRecipe> recipe) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }

        if (tryQuickMoveOutput(client, handler)) {
            ingredientDropLocked = false;
            return true;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
            ingredientDropLocked = false;
            return true;
        }

        if (dropOutputsBeforeTakingAndTryTake(client, handler, resultTemplate, OUTPUT_TAKE_ATTEMPTS_AFTER_DROP)) {
            if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
                ingredientDropLocked = false;
            }
            return true;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
            ingredientDropLocked = false;
            return true;
        }

        if (!ingredientDropLocked) {
            int droppedIngredient = dropIngredientBurst(client, handler, recipe, 1);
            if (droppedIngredient > 0) {
                ingredientDropLocked = true;

                boolean tookOutput = dropOutputsBeforeTakingAndTryTake(
                        client,
                        handler,
                        resultTemplate,
                        OUTPUT_TAKE_ATTEMPTS_AFTER_DROP
                );
                if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
                    ingredientDropLocked = false;
                }
                return tookOutput || droppedIngredient > 0;
            }
        }

        return false;
    }

    private void handleSingleCraft(Minecraft client, StonecutterMenu handler) {
        if (rapidCraftingActive || singleCraftPending) {
            return;
        }
        if (!lockCurrentSelection(client, handler)) {
            sendStatusMessage(client, Component.translatable("quickcraft.message.stonecutter.no_selection"));
            return;
        }

        boolean success = runOneCraftSubLoop(client, handler, lockedRecipe);
        if (!success) {
            handleSingleCraftFailure(client, handler);
        }
    }

    private void processPendingSingleCraft(Minecraft client, StonecutterMenu handler) {
        if (!singleCraftPending || rapidCraftingActive) {
            return;
        }

        if (handler.getSlot(OUTPUT_SLOT).hasItem()) {
            ItemStack output = handler.getSlot(OUTPUT_SLOT).getItem();
            if (!ItemStack.isSameItemSameComponents(output, lockedResultTemplate)) {
                clearPendingSingleCraft();
                return;
            }

            if (runOneCraftSubLoop(client, handler, lockedRecipe)) {
                clearPendingSingleCraft();
                return;
            }
        }

        singleCraftWaitTicks++;
        if (singleCraftWaitTicks >= SERVER_SYNC_TIMEOUT_TICKS) {
            clearPendingSingleCraft();
            sendStatusMessage(client, Component.translatable("quickcraft.message.stonecutter.sync_timeout"));
        }
    }

    private void handleSingleCraftFailure(Minecraft client, StonecutterMenu handler) {
        if (isIngredientUnavailable(client, handler, lockedRecipe)) {
            sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
        } else {
            singleCraftPending = true;
            singleCraftWaitTicks = 0;
        }
    }

    private boolean isIngredientUnavailable(Minecraft client,
                                            StonecutterMenu handler,
                                            RecipeHolder<StonecutterRecipe> recipe) {
        return !handler.getSlot(OUTPUT_SLOT).hasItem()
                && !handler.getSlot(INPUT_SLOT).hasItem()
                && findBestSupplyIngredientSlot(client.player.getInventory(), handler, recipe) == -1;
    }

    private void clearPendingSingleCraft() {
        singleCraftPending = false;
        singleCraftWaitTicks = 0;
    }

    private boolean handleCraftButton(Minecraft client, boolean rapidCraft) {
        if (!isCraftingContextValid(client)) {
            return false;
        }

        StonecutterMenu handler = (StonecutterMenu) client.player.containerMenu;
        if (rapidCraft) {
            return startRapidCraft(client, handler, true);
        }

        handleSingleCraft(client, handler);
        return true;
    }

    private boolean clickSelectedRecipe(Minecraft client,
                                        StonecutterMenu handler,
                                        RecipeHolder<StonecutterRecipe> recipe) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }

        int recipeIndex = isRecipeIndexAvailable(handler, lockedRecipeIndex) ? lockedRecipeIndex : -1;
        if (recipeIndex < 0) {
            recipeIndex = findAvailableRecipeIndex(handler, recipe);
        }
        if (recipeIndex < 0) {
            recipeIndex = findAvailableRecipeIndexByResult(client, handler, lockedResultTemplate);
        }
        if (!isRecipeIndexAvailable(handler, recipeIndex)) {
            return false;
        }

        try {
            // 1.21.2+（包括 26.1）客户端只有配方展示数据，重复本地 clickMenuButton 不能生成真实产物。
            boolean selectionChanged = handler.getSelectedRecipeIndex() != recipeIndex;
            if (selectionChanged) {
                handler.clickMenuButton(client.player, recipeIndex);
                client.gameMode.handleInventoryButtonClick(handler.containerId, recipeIndex);
            }
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private boolean quickMoveIngredientToInput(Minecraft client,
                                               StonecutterMenu handler,
                                               RecipeHolder<StonecutterRecipe> recipe) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }

        int invIndex = findBestSupplyIngredientSlot(client.player.getInventory(), handler, recipe);
        if (invIndex == -1) {
            return false;
        }

        int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
        if (handlerSlot == -1 || !handler.getSlot(handlerSlot).hasItem()) {
            return false;
        }

        ItemStack beforeInput = handler.getSlot(INPUT_SLOT).getItem().copy();
        ItemStack beforeSource = handler.getSlot(handlerSlot).getItem().copy();

        client.gameMode.handleContainerInput(
                handler.containerId,
                handlerSlot,
                0,
                ContainerInput.QUICK_MOVE,
                client.player
        );

        ItemStack afterInput = handler.getSlot(INPUT_SLOT).getItem();
        ItemStack afterSource = handler.getSlot(handlerSlot).getItem();

        return !afterInput.isEmpty()
                || !ItemStack.isSameItemSameComponents(beforeInput, afterInput)
                || afterSource.getCount() != beforeSource.getCount();
    }

    private boolean tryQuickMoveOutput(Minecraft client, StonecutterMenu handler) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getItem().copy();
        boolean canAcceptOutput = canAcceptOutputInMainInventory(client.player.getInventory(), before);
        int beforeResultCount = countMatchingItems(client.player.getInventory(), before);
        client.gameMode.handleContainerInput(
                handler.containerId,
                OUTPUT_SLOT,
                0,
                ContainerInput.QUICK_MOVE,
                client.player
        );

        ItemStack after = handler.getSlot(OUTPUT_SLOT).getItem();
        return after.isEmpty()
                || !ItemStack.isSameItemSameComponents(before, after)
                || after.getCount() != before.getCount()
                || countMatchingItems(client.player.getInventory(), before) > beforeResultCount
                || canAcceptOutput;
    }

    private boolean canAcceptOutputInMainInventory(Inventory inventory, ItemStack output) {
        if (output.isEmpty()) {
            return false;
        }

        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(stack, output)
                    && stack.getCount() < Math.min(stack.getMaxStackSize(), output.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    private int getOutputSignature(StonecutterMenu handler) {
        if (handler == null || !handler.getSlot(OUTPUT_SLOT).hasItem()) {
            return 0;
        }

        ItemStack stack = handler.getSlot(OUTPUT_SLOT).getItem();
        int hash = 17;
        hash = 31 * hash + System.identityHashCode(stack.getItem());
        try {
            hash = 31 * hash + stack.getCount();
            hash = 31 * hash + stack.getComponents().hashCode();
        } catch (Throwable ignored) {
        }
        return hash;
    }

    private void updateIngredientDropLock(StonecutterMenu handler) {
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

    private boolean dropOutputsBeforeTakingAndTryTake(Minecraft client,
                                                      StonecutterMenu handler,
                                                      ItemStack resultTemplate,
                                                      int takeAttemptsAfterDrop) {
        boolean progressed = false;

        if (!resultTemplate.isEmpty()) {
            int droppedOutput = dropMatchingItemsFromInventoryBurst(client, handler, resultTemplate, 1);
            if (droppedOutput > 0) {
                progressed = true;
            }
        }

        for (int i = 0; i < takeAttemptsAfterDrop; i++) {
            if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
                break;
            }
            if (!tryQuickMoveOutput(client, handler)) {
                continue;
            }
            progressed = true;
            if (!handler.getSlot(OUTPUT_SLOT).hasItem()) {
                break;
            }
        }

        return progressed;
    }

    private int dropMatchingItemsFromInventoryBurst(Minecraft client,
                                                    StonecutterMenu handler,
                                                    ItemStack resultTemplate,
                                                    int burstCount) {
        if (client.player == null || client.gameMode == null || resultTemplate.isEmpty()) {
            return 0;
        }

        int droppedSlots = 0;
        for (int round = 0; round < burstCount; round++) {
            boolean anyDroppedInRound = false;
            Inventory inventory = client.player.getInventory();

            for (int invIndex = 0; invIndex < inventory.getNonEquipmentItems().size(); invIndex++) {
                ItemStack stack = inventory.getNonEquipmentItems().get(invIndex);
                if (stack.isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(stack, resultTemplate)) continue;

                int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
                if (handlerSlot == -1
                        || QuickContainerLock.isLockedSlot(handler, handlerSlot)
                        || !handler.getSlot(handlerSlot).hasItem()) continue;

                client.gameMode.handleContainerInput(
                        handler.containerId,
                        handlerSlot,
                        1,
                        ContainerInput.THROW,
                        client.player
                );
                anyDroppedInRound = true;
                droppedSlots++;
            }

            if (!anyDroppedInRound) {
                break;
            }
        }

        return droppedSlots;
    }

    private int dropIngredientBurst(Minecraft client,
                                    StonecutterMenu handler,
                                    RecipeHolder<StonecutterRecipe> recipe,
                                    int maxDrops) {
        if (client.player == null || client.gameMode == null || maxDrops <= 0) {
            return 0;
        }

        int dropped = 0;
        for (int i = 0; i < maxDrops; i++) {
            int invIndex = findBestDroppableIngredientSlot(client.player.getInventory(), handler, recipe);
            if (invIndex == -1) {
                break;
            }

            int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
            if (handlerSlot == -1 || !handler.getSlot(handlerSlot).hasItem()) {
                break;
            }

            client.gameMode.handleContainerInput(
                    handler.containerId,
                    handlerSlot,
                    1,
                    ContainerInput.THROW,
                    client.player
            );
            dropped++;
        }

        return dropped;
    }

    private int findBestSupplyIngredientSlot(Inventory inventory,
                                             StonecutterMenu handler,
                                             RecipeHolder<StonecutterRecipe> recipe) {
        if (recipe == null) {
            return findBestMatchingItemSlot(inventory, handler, lockedInputTemplate, false);
        }

        List<Ingredient> ingredients = recipe.value().placementInfo().ingredients();
        int bestIndex = -1;
        int bestTotalCount = -1;
        int bestStackCount = -1;

        for (int invIndex = 0; invIndex < inventory.getNonEquipmentItems().size(); invIndex++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(invIndex);
            if (stack.isEmpty()) continue;
            if (isLockedPlayerInventorySlot(handler, invIndex)) continue;
            if (!matchesAnyIngredient(stack, ingredients)) continue;

            int totalCount = countMatchingUnlockedItems(inventory, handler, stack);
            if (totalCount > bestTotalCount
                    || (totalCount == bestTotalCount && stack.getCount() > bestStackCount)) {
                bestTotalCount = totalCount;
                bestStackCount = stack.getCount();
                bestIndex = invIndex;
            }
        }

        return bestIndex;
    }

    private int findBestDroppableIngredientSlot(Inventory inventory,
                                                StonecutterMenu handler,
                                                RecipeHolder<StonecutterRecipe> recipe) {
        if (recipe == null) {
            return findBestMatchingItemSlot(inventory, handler, lockedInputTemplate, true);
        }

        List<Ingredient> ingredients = recipe.value().placementInfo().ingredients();
        int bestIndex = -1;
        int bestTotalCount = -1;
        int bestStackCount = -1;

        for (int invIndex = 0; invIndex < inventory.getNonEquipmentItems().size(); invIndex++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(invIndex);
            if (stack.isEmpty()) continue;
            if (isLockedPlayerInventorySlot(handler, invIndex)) continue;
            if (stack.getCount() <= 1) continue;
            if (!matchesAnyIngredient(stack, ingredients)) continue;

            int totalCount = countMatchingUnlockedItems(inventory, handler, stack);
            if (totalCount > bestTotalCount
                    || (totalCount == bestTotalCount && stack.getCount() > bestStackCount)) {
                bestTotalCount = totalCount;
                bestStackCount = stack.getCount();
                bestIndex = invIndex;
            }
        }

        return bestIndex;
    }

    private int findBestMatchingItemSlot(Inventory inventory,
                                         StonecutterMenu handler,
                                         ItemStack template,
                                         boolean requireExtraItem) {
        if (template.isEmpty()) {
            return -1;
        }

        int bestIndex = -1;
        int bestTotalCount = -1;
        int bestStackCount = -1;

        for (int invIndex = 0; invIndex < inventory.getNonEquipmentItems().size(); invIndex++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(invIndex);
            if (stack.isEmpty()) continue;
            if (isLockedPlayerInventorySlot(handler, invIndex)) continue;
            if (requireExtraItem && stack.getCount() <= 1) continue;
            if (!ItemStack.isSameItemSameComponents(stack, template)) continue;

            int totalCount = countMatchingUnlockedItems(inventory, handler, stack);
            if (totalCount > bestTotalCount
                    || (totalCount == bestTotalCount && stack.getCount() > bestStackCount)) {
                bestTotalCount = totalCount;
                bestStackCount = stack.getCount();
                bestIndex = invIndex;
            }
        }

        return bestIndex;
    }

    private int countMatchingUnlockedItems(Inventory inventory,
                                           StonecutterMenu handler,
                                           ItemStack template) {
        int total = 0;
        for (int invIndex = 0; invIndex < inventory.getNonEquipmentItems().size(); invIndex++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(invIndex);
            if (!stack.isEmpty()
                    && !isLockedPlayerInventorySlot(handler, invIndex)
                    && ItemStack.isSameItemSameComponents(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean isLockedPlayerInventorySlot(StonecutterMenu handler, int invIndex) {
        int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
        return handlerSlot == -1 || QuickContainerLock.isLockedSlot(handler, handlerSlot);
    }

    private boolean matchesAnyIngredient(ItemStack stack, List<Ingredient> ingredients) {
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            if (ingredient.test(stack)) return true;
        }
        return false;
    }

    private ItemStack getRecipeResultStack(Minecraft client, RecipeHolder<StonecutterRecipe> recipe) {
        if (!lockedResultTemplate.isEmpty()) {
            return lockedResultTemplate.copy();
        }
        return craftRecipeResult(client, recipe);
    }

    private ItemStack craftRecipeResult(Minecraft client, RecipeHolder<StonecutterRecipe> recipe) {
        if (recipe == null) {
            return ItemStack.EMPTY;
        }

        try {
            ItemStack input = client.player.containerMenu.getSlot(INPUT_SLOT).getItem().copy();
            return recipe.value().assemble(new SingleRecipeInput(input)).copy();
        } catch (Throwable throwable) {
            return ItemStack.EMPTY;
        }
    }

    private boolean lockCurrentSelection(Minecraft client, StonecutterMenu handler) {
        int selectedIndex = handler.getSelectedRecipeIndex();
        if (!isRecipeIndexAvailable(handler, selectedIndex) && handler.getSlot(OUTPUT_SLOT).hasItem()) {
            selectedIndex = findAvailableRecipeIndexByResult(client, handler, handler.getSlot(OUTPUT_SLOT).getItem());
        }

        RecipeHolder<StonecutterRecipe> recipe = getRecipeAt(handler, selectedIndex);
        ItemStack resultTemplate = ItemStack.EMPTY;
        if (handler.getSlot(OUTPUT_SLOT).hasItem()) {
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getItem().copy();
        } else if (recipe != null) {
            resultTemplate = craftRecipeResult(client, recipe);
        }
        if (resultTemplate.isEmpty() && isRecipeIndexAvailable(handler, selectedIndex)) {
            resultTemplate = getDisplayResultStack(client, handler, selectedIndex);
        }

        if (resultTemplate.isEmpty()) {
            return hasLockedSelection();
        }

        lockedRecipeIndex = selectedIndex;
        lockedRecipe = recipe;
        lockedInputTemplate = copyTemplate(handler.getSlot(INPUT_SLOT).getItem());
        lockedResultTemplate = copyTemplate(resultTemplate);
        return true;
    }

    private RecipeHolder<StonecutterRecipe> getRecipeAt(StonecutterMenu handler, int recipeIndex) {
        if (!isRecipeIndexAvailable(handler, recipeIndex)) {
            return null;
        }

        return handler.getVisibleRecipes().entries().get(recipeIndex).recipe().recipe().orElse(null);
    }

    private int findAvailableRecipeIndex(StonecutterMenu handler,
                                         RecipeHolder<StonecutterRecipe> recipe) {
        if (recipe == null) {
            return -1;
        }

        List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> entries = handler.getVisibleRecipes().entries();
        for (int i = 0; i < entries.size(); i++) {
            RecipeHolder<StonecutterRecipe> availableRecipe = entries.get(i).recipe().recipe().orElse(null);
            if (availableRecipe != null && availableRecipe.id().equals(recipe.id())) {
                return i;
            }
        }
        return -1;
    }

    private boolean isRecipeIndexAvailable(StonecutterMenu handler, int recipeIndex) {
        return recipeIndex >= 0 && recipeIndex < handler.getNumberOfVisibleRecipes();
    }

    private int findAvailableRecipeIndexByResult(Minecraft client,
                                                 StonecutterMenu handler,
                                                 ItemStack resultTemplate) {
        if (resultTemplate.isEmpty()) {
            return -1;
        }

        List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> entries = handler.getVisibleRecipes().entries();
        for (int i = 0; i < entries.size(); i++) {
            ItemStack displayedResult = getDisplayResultStack(client, handler, i);
            if (!displayedResult.isEmpty()
                    && ItemStack.isSameItemSameComponents(displayedResult, resultTemplate)) {
                return i;
            }
        }
        return -1;
    }

    private ItemStack getDisplayResultStack(Minecraft client,
                                            StonecutterMenu handler,
                                            int recipeIndex) {
        if (client.level == null || !isRecipeIndexAvailable(handler, recipeIndex)) {
            return ItemStack.EMPTY;
        }

        try {
            return handler.getVisibleRecipes()
                    .entries()
                    .get(recipeIndex)
                    .recipe()
                    .optionDisplay()
                    .resolveForFirstStack(SlotDisplayContext.fromLevel(client.level))
                    .copy();
        } catch (Throwable throwable) {
            return ItemStack.EMPTY;
        }
    }

    private boolean hasLockedSelection() {
        return !lockedResultTemplate.isEmpty();
    }

    private ItemStack copyTemplate(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    private void clearLockedSelection() {
        lockedRecipe = null;
        lockedRecipeIndex = -1;
        lockedInputTemplate = ItemStack.EMPTY;
        lockedResultTemplate = ItemStack.EMPTY;
    }

    private int playerInventoryIndexToHandlerSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) {
            return 29 + invIndex;
        }
        if (invIndex >= 9 && invIndex <= 35) {
            return 2 + (invIndex - 9);
        }
        return -1;
    }

    private void handleHotkeys(Minecraft client, StonecutterMenu handler) {
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
                                    StonecutterMenu handler,
                                    boolean fromButton) {
        clearPendingSingleCraft();
        if (!lockCurrentSelection(client, handler)) {
            rapidCraftingActive = false;
            rapidCraftStartedByButton = false;
            sendStatusMessage(client, Component.translatable("quickcraft.message.stonecutter.no_selection"));
            return false;
        }

        rapidCraftingActive = true;
        rapidCraftStartedByButton = fromButton;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        noProgressTicks = 0;
        ingredientDropLocked = false;
        lastObservedOutputSignature = 0;
        fakeProgressTicks = 0;

        refreshProgressSnapshot(client, lockedRecipe);
        sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.started"));
        return true;
    }

    private boolean isCraftingContextValid(Minecraft client) {
        if (client.player == null || client.level == null) {
            return false;
        }

        if (!(client.screen instanceof StonecutterScreen)) {
            return false;
        }

        return client.player.containerMenu instanceof StonecutterMenu;
    }

    private void refreshProgressSnapshot(Minecraft client, RecipeHolder<StonecutterRecipe> recipe) {
        if (client.player == null) {
            lastResultCount = -1;
            lastEmptySlots = -1;
            return;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        Inventory inventory = client.player.getInventory();
        lastResultCount = countMatchingItems(inventory, resultTemplate);
        lastEmptySlots = countEmptyMainSlots(inventory);
    }

    private void detectNoProgressAndMaybeStop(Minecraft client,
                                              RecipeHolder<StonecutterRecipe> recipe) {
        if (client.player == null) {
            return;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        Inventory inventory = client.player.getInventory();
        int currentResultCount = countMatchingItems(inventory, resultTemplate);
        int currentEmptySlots = countEmptyMainSlots(inventory);

        boolean progressed = false;
        if (lastResultCount >= 0 && currentResultCount > lastResultCount) {
            progressed = true;
        }
        if (lastEmptySlots >= 0 && currentEmptySlots > lastEmptySlots) {
            progressed = true;
        }

        lastResultCount = currentResultCount;
        lastEmptySlots = currentEmptySlots;

        if (progressed) {
            noProgressTicks = 0;
            return;
        }

        noProgressTicks++;
        if (noProgressTicks >= MAX_NO_PROGRESS_TICKS) {
            stopRapidCraft(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
        }
    }

    private boolean hasMatchingUnlockedItemInInventory(Inventory inventory,
                                                       StonecutterMenu handler,
                                                       ItemStack template) {
        if (template.isEmpty()) {
            return false;
        }

        for (int invIndex = 0; invIndex < inventory.getNonEquipmentItems().size(); invIndex++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(invIndex);
            if (stack.isEmpty()) continue;
            if (isLockedPlayerInventorySlot(handler, invIndex)) continue;
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                return true;
            }
        }
        return false;
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

    private int countEmptyMainSlots(Inventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                total++;
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
        if (hasLockedSelection() && QuickCraftConfigs.isDropCraftResultsOnStopEnabled()) {
            dropCraftResultsAfterStop(client, (StonecutterMenu) client.player.containerMenu, lockedRecipe);
        }

        rapidCraftingActive = false;
        rapidCraftStartedByButton = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        noProgressTicks = 0;
        ingredientDropLocked = false;
        lastObservedOutputSignature = 0;
        sendStatusMessage(client, message);
    }

    private void resetAll() {
        rapidCraftingActive = false;
        rapidCraftStartedByButton = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        clearLockedSelection();
        noProgressTicks = 0;
        lastResultCount = -1;
        lastEmptySlots = -1;
        ingredientDropLocked = false;
        lastObservedOutputSignature = 0;
        fakeProgressTicks = 0;
        clearPendingSingleCraft();
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
                                           StonecutterMenu handler,
                                           RecipeHolder<StonecutterRecipe> recipe) {
        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (!resultTemplate.isEmpty()) {
            dropMatchingItemsFromInventoryBurst(client, handler, resultTemplate, 1);
        }
    }
}
