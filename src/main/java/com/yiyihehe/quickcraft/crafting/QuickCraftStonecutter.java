package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/**
 * 切石机快速喷射合成。
 * 流程基本复用工作台的思路，只把“放料/选配方”替换成切石机的原版交互。
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

    private boolean lastVDown = false;
    private boolean lastAltCDown = false;
    private boolean rapidCraftingActive = false;
    private boolean rapidCraftStartedByButton = false;
    private int rapidCooldown = 0;
    private int consecutiveFailures = 0;
    private RecipeEntry<StonecuttingRecipe> lockedRecipe = null;
    private int noProgressTicks = 0;
    private int lastResultCount = -1;
    private int lastEmptySlots = -1;
    private boolean ingredientDropLocked = false;
    private int lastObservedOutputSignature = 0;
    private int fakeProgressTicks = 0;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean handleStonecutterCraftButton(boolean rapidCraft) {
        if (INSTANCE == null) {
            return false;
        }
        return INSTANCE.handleCraftButton(MinecraftClient.getInstance(), rapidCraft);
    }

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isStonecutterQuickCraftEnabled()) {
            resetAll();
            return;
        }

        if (!isCraftingContextValid(client)) {
            resetAll();
            return;
        }

        StonecutterScreenHandler handler = (StonecutterScreenHandler) client.player.currentScreenHandler;
        updateIngredientDropLock(handler);
        handleHotkeys(client, handler);

        if (rapidCraftingActive && rapidCraftStartedByButton && !isCraftButtonRapidModeHeld(client)) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.stonecutter.stopped"));
        }

        if (rapidCraftingActive && lockedRecipe != null) {
            rapidCooldown++;
            if (rapidCooldown >= RAPID_INTERVAL) {
                rapidCooldown = 0;
                processRapidCraftTick(client, handler, lockedRecipe);
            }
        }
    }

    private void processRapidCraftTick(MinecraftClient client,
                                       StonecutterScreenHandler handler,
                                       RecipeEntry<StonecuttingRecipe> recipe) {
        boolean anyProgress = false;
        int craftLoopsPerTick = QuickCraftConfigs.getCraftLoopsPerTick();

        for (int loop = 0; loop < craftLoopsPerTick; loop++) {
            boolean progressed = runOneCraftSubLoop(client, handler, recipe);
            if (progressed) {
                anyProgress = true;
            } else {
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
                stopRapidCraft(client, Text.translatable("quickcraft.message.stonecutter.no_progress"));
            }
        }
    }

    private boolean runOneCraftSubLoop(MinecraftClient client,
                                       StonecutterScreenHandler handler,
                                       RecipeEntry<StonecuttingRecipe> recipe) {
        if (client.player == null || client.interactionManager == null || client.world == null) {
            return false;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (resultTemplate.isEmpty() && handler.getSlot(OUTPUT_SLOT).hasStack()) {
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getStack().copy();
        }

        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
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

            if (!hasMatchingItemInInventory(client.player.getInventory(), resultTemplate)) {
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

        if (!handler.getSlot(INPUT_SLOT).hasStack()) {
            if (!quickMoveIngredientToInput(client, handler, recipe)) {
                return false;
            }
        }

        if (!clickSelectedRecipe(client, handler, recipe)) {
            return false;
        }

        tryQuickMoveOutput(client, handler);
        fakeProgressTicks = 0;
        return true;
    }

    private boolean resolveOutputSlotBlockageStrict(MinecraftClient client,
                                                    StonecutterScreenHandler handler,
                                                    ItemStack resultTemplate,
                                                    RecipeEntry<StonecuttingRecipe> recipe) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        if (tryQuickMoveOutput(client, handler)) {
            ingredientDropLocked = false;
            return true;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            ingredientDropLocked = false;
            return true;
        }

        if (dropOutputsBeforeTakingAndTryTake(client, handler, resultTemplate, OUTPUT_TAKE_ATTEMPTS_AFTER_DROP)) {
            if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                ingredientDropLocked = false;
            }
            return true;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
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
                if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                    ingredientDropLocked = false;
                }
                return tookOutput || droppedIngredient > 0;
            }
        }

        return false;
    }

    private void handleSingleCraft(MinecraftClient client, StonecutterScreenHandler handler) {
        RecipeEntry<StonecuttingRecipe> currentRecipe = getCurrentStonecuttingRecipe(handler);
        if (currentRecipe != null) {
            lockedRecipe = currentRecipe;
        }

        if (lockedRecipe == null) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.stonecutter.no_selection"));
            return;
        }

        boolean success = runOneCraftSubLoop(client, handler, lockedRecipe);
        if (!success) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.stonecutter.no_progress"));
        }
    }

    private boolean handleCraftButton(MinecraftClient client, boolean rapidCraft) {
        if (!isCraftingContextValid(client)) {
            return false;
        }

        StonecutterScreenHandler handler = (StonecutterScreenHandler) client.player.currentScreenHandler;
        if (rapidCraft) {
            return startRapidCraft(client, handler, true);
        }

        handleSingleCraft(client, handler);
        return true;
    }

    private boolean clickSelectedRecipe(MinecraftClient client,
                                        StonecutterScreenHandler handler,
                                        RecipeEntry<StonecuttingRecipe> recipe) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        int recipeIndex = findAvailableRecipeIndex(handler, recipe);
        if (recipeIndex < 0) {
            return false;
        }

        try {
            handler.onButtonClick(client.player, recipeIndex);
            client.interactionManager.clickButton(handler.syncId, recipeIndex);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private boolean quickMoveIngredientToInput(MinecraftClient client,
                                               StonecutterScreenHandler handler,
                                               RecipeEntry<StonecuttingRecipe> recipe) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        int invIndex = findBestSupplyIngredientSlot(client.player.getInventory(), recipe);
        if (invIndex == -1) {
            return false;
        }

        int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
        if (handlerSlot == -1 || !handler.getSlot(handlerSlot).hasStack()) {
            return false;
        }

        ItemStack beforeInput = handler.getSlot(INPUT_SLOT).getStack().copy();
        ItemStack beforeSource = handler.getSlot(handlerSlot).getStack().copy();

        client.interactionManager.clickSlot(
                handler.syncId,
                handlerSlot,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        ItemStack afterInput = handler.getSlot(INPUT_SLOT).getStack();
        ItemStack afterSource = handler.getSlot(handlerSlot).getStack();

        return !afterInput.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(beforeInput, afterInput)
                || afterSource.getCount() != beforeSource.getCount();
    }

    private boolean tryQuickMoveOutput(MinecraftClient client, StonecutterScreenHandler handler) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getStack().copy();
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
                || after.getCount() != before.getCount();
    }

    private int getOutputSignature(StonecutterScreenHandler handler) {
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

    private void updateIngredientDropLock(StonecutterScreenHandler handler) {
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
                                                      StonecutterScreenHandler handler,
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
            if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                break;
            }
            if (!tryQuickMoveOutput(client, handler)) {
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
                                                    StonecutterScreenHandler handler,
                                                    ItemStack resultTemplate,
                                                    int burstCount) {
        if (client.player == null || client.interactionManager == null || resultTemplate.isEmpty()) {
            return 0;
        }

        int droppedSlots = 0;
        for (int round = 0; round < burstCount; round++) {
            boolean anyDroppedInRound = false;
            PlayerInventory inventory = client.player.getInventory();

            for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
                ItemStack stack = inventory.main.get(invIndex);
                if (stack.isEmpty()) continue;
                if (!ItemStack.areItemsAndComponentsEqual(stack, resultTemplate)) continue;

                int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
                if (handlerSlot == -1 || !handler.getSlot(handlerSlot).hasStack()) continue;

                client.interactionManager.clickSlot(
                        handler.syncId,
                        handlerSlot,
                        1,
                        SlotActionType.THROW,
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

    private int dropIngredientBurst(MinecraftClient client,
                                    StonecutterScreenHandler handler,
                                    RecipeEntry<StonecuttingRecipe> recipe,
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
            if (handlerSlot == -1 || !handler.getSlot(handlerSlot).hasStack()) {
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

    private int findBestSupplyIngredientSlot(PlayerInventory inventory,
                                             RecipeEntry<StonecuttingRecipe> recipe) {
        List<Ingredient> ingredients = recipe.value().getIngredients();
        int bestIndex = -1;
        int bestTotalCount = -1;
        int bestStackCount = -1;

        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            ItemStack stack = inventory.main.get(invIndex);
            if (stack.isEmpty()) continue;
            if (!matchesAnyIngredient(stack, ingredients)) continue;

            int totalCount = countMatchingItems(inventory, stack);
            if (totalCount > bestTotalCount
                    || (totalCount == bestTotalCount && stack.getCount() > bestStackCount)) {
                bestTotalCount = totalCount;
                bestStackCount = stack.getCount();
                bestIndex = invIndex;
            }
        }

        return bestIndex;
    }

    private int findBestDroppableIngredientSlot(PlayerInventory inventory,
                                                RecipeEntry<StonecuttingRecipe> recipe) {
        List<Ingredient> ingredients = recipe.value().getIngredients();
        int bestIndex = -1;
        int bestTotalCount = -1;
        int bestStackCount = -1;

        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            ItemStack stack = inventory.main.get(invIndex);
            if (stack.isEmpty()) continue;
            if (stack.getCount() <= 1) continue;
            if (!matchesAnyIngredient(stack, ingredients)) continue;

            int totalCount = countMatchingItems(inventory, stack);
            if (totalCount > bestTotalCount
                    || (totalCount == bestTotalCount && stack.getCount() > bestStackCount)) {
                bestTotalCount = totalCount;
                bestStackCount = stack.getCount();
                bestIndex = invIndex;
            }
        }

        return bestIndex;
    }

    private boolean matchesAnyIngredient(ItemStack stack, List<Ingredient> ingredients) {
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            if (ingredient.test(stack)) return true;
        }
        return false;
    }

    private ItemStack getRecipeResultStack(MinecraftClient client, RecipeEntry<StonecuttingRecipe> recipe) {
        try {
            DynamicRegistryManager registryManager = client.world.getRegistryManager();
            return recipe.value().getResult(registryManager).copy();
        } catch (Throwable throwable) {
            return ItemStack.EMPTY;
        }
    }

    private RecipeEntry<StonecuttingRecipe> getCurrentStonecuttingRecipe(StonecutterScreenHandler handler) {
        int selectedIndex = handler.getSelectedRecipe();
        List<RecipeEntry<StonecuttingRecipe>> recipes = handler.getAvailableRecipes();
        if (selectedIndex < 0 || selectedIndex >= recipes.size()) {
            return null;
        }
        return recipes.get(selectedIndex);
    }

    private int findAvailableRecipeIndex(StonecutterScreenHandler handler,
                                         RecipeEntry<StonecuttingRecipe> recipe) {
        List<RecipeEntry<StonecuttingRecipe>> recipes = handler.getAvailableRecipes();
        for (int i = 0; i < recipes.size(); i++) {
            if (recipes.get(i).id().equals(recipe.id())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 切石机槽位布局：
     * - 输入槽：0
     * - 输出槽：1
     * - 主背包：2-28
     * - 快捷栏：29-37
     */
    private int playerInventoryIndexToHandlerSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) {
            return 29 + invIndex;
        }
        if (invIndex >= 9 && invIndex <= 35) {
            return 2 + (invIndex - 9);
        }
        return -1;
    }

    private void handleHotkeys(MinecraftClient client, StonecutterScreenHandler handler) {
        boolean vDown = QuickCraftConfigs.getSingleCraftHotkey().isKeybindHeld();
        boolean rapidDown = QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld();

        if (vDown && !lastVDown) {
            handleSingleCraft(client, handler);
        }

        if (rapidDown && !lastAltCDown) {
            startRapidCraft(client, handler, false);
        }

        if (!rapidDown && rapidCraftingActive && !rapidCraftStartedByButton) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.stonecutter.stopped"));
        }

        lastVDown = vDown;
        lastAltCDown = rapidDown;
    }

    private boolean startRapidCraft(MinecraftClient client,
                                    StonecutterScreenHandler handler,
                                    boolean fromButton) {
        RecipeEntry<StonecuttingRecipe> currentRecipe = getCurrentStonecuttingRecipe(handler);
        if (currentRecipe != null) {
            lockedRecipe = currentRecipe;
        }

        if (lockedRecipe == null) {
            rapidCraftingActive = false;
            rapidCraftStartedByButton = false;
            sendStatusMessage(client, Text.translatable("quickcraft.message.stonecutter.no_selection"));
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
        sendStatusMessage(client, Text.translatable("quickcraft.message.stonecutter.started"));
        return true;
    }

    private boolean isCraftingContextValid(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return false;
        }

        if (!(client.currentScreen instanceof StonecutterScreen)) {
            return false;
        }

        return client.player.currentScreenHandler instanceof StonecutterScreenHandler;
    }

    private void refreshProgressSnapshot(MinecraftClient client, RecipeEntry<StonecuttingRecipe> recipe) {
        if (client.player == null) {
            lastResultCount = -1;
            lastEmptySlots = -1;
            return;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        PlayerInventory inventory = client.player.getInventory();
        lastResultCount = countMatchingItems(inventory, resultTemplate);
        lastEmptySlots = countEmptyMainSlots(inventory);
    }

    private void detectNoProgressAndMaybeStop(MinecraftClient client,
                                              RecipeEntry<StonecuttingRecipe> recipe) {
        if (client.player == null) {
            return;
        }

        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        PlayerInventory inventory = client.player.getInventory();
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
            stopRapidCraft(client, Text.translatable("quickcraft.message.stonecutter.no_progress"));
        }
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

    private int countEmptyMainSlots(PlayerInventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.main) {
            if (stack.isEmpty()) {
                total++;
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
        if (lockedRecipe != null && QuickCraftConfigs.isDropCraftResultsOnStopEnabled()) {
            dropCraftResultsAfterStop(client, (StonecutterScreenHandler) client.player.currentScreenHandler, lockedRecipe);
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
        lockedRecipe = null;
        noProgressTicks = 0;
        lastResultCount = -1;
        lastEmptySlots = -1;
        ingredientDropLocked = false;
        lastObservedOutputSignature = 0;
        fakeProgressTicks = 0;
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
                                           StonecutterScreenHandler handler,
                                           RecipeEntry<StonecuttingRecipe> recipe) {
        ItemStack resultTemplate = getRecipeResultStack(client, recipe);
        if (!resultTemplate.isEmpty()) {
            dropMatchingItemsFromInventoryBurst(client, handler, resultTemplate, 1);
        }
    }
}
