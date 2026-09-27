package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
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
//#if MC>=12103
//$$ import net.minecraft.recipe.display.CuttingRecipeDisplay;
//$$ import net.minecraft.recipe.display.SlotDisplayContexts;
//#endif
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
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
    //#if MC<12103
    private static final int RECIPE_RESULT_WAIT_TICKS = 3;
    //#else
    // 40 tick 给普通多人服务器约两秒时间返回权威切石机槽位。
    //$$ private static final int SERVER_SYNC_TIMEOUT_TICKS = 40;
    //#endif

    private boolean lastVDown = false;
    private boolean lastAltCDown = false;
    private boolean rapidCraftingActive = false;
    private boolean rapidCraftStartedByButton = false;
    private int rapidCooldown = 0;
    private int consecutiveFailures = 0;
    private RecipeEntry<StonecuttingRecipe> lockedRecipe = null;
    private int lockedRecipeIndex = -1;
    private ItemStack lockedInputTemplate = ItemStack.EMPTY;
    private ItemStack lockedResultTemplate = ItemStack.EMPTY;
    private int noProgressTicks = 0;
    private int lastResultCount = -1;
    private int lastEmptySlots = -1;
    private boolean ingredientDropLocked = false;
    private int lastObservedOutputSignature = 0;
    private int fakeProgressTicks = 0;
    //#if MC<12103
    private int recipeResultWaitTicks = 0;
    //#else
    //$$ private boolean singleCraftPending = false;
    //$$ private int singleCraftWaitTicks = 0;
    //#endif

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
        //#if MC>=12103
        //$$ processPendingSingleCraft(client, handler);
        //#endif

        if (rapidCraftingActive && rapidCraftStartedByButton && !isCraftButtonRapidModeHeld(client)) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.stopped"));
        }

        if (rapidCraftingActive && hasLockedSelection()) {
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
        //#if MC<12103
        if (waitForRecipeResult(client, handler)) {
            return;
        }
        //#endif

        boolean anyProgress = false;
        int craftLoopsPerTick = QuickCraftConfigs.getCraftLoopsPerTick();

        for (int loop = 0; loop < craftLoopsPerTick; loop++) {
            boolean progressed = runOneCraftSubLoop(client, handler, recipe);
            if (progressed) {
                anyProgress = true;
            }
            //#if MC<12103
            if (!rapidCraftingActive || recipeResultWaitTicks > 0) {
            //#else
            //$$ if (!rapidCraftingActive) {
            //#endif
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
                stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
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

        if (!handler.getSlot(INPUT_SLOT).hasStack()) {
            if (!quickMoveIngredientToInput(client, handler, recipe)) {
                if (rapidCraftingActive) {
                    stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
                }
                return false;
            }
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack() && !clickSelectedRecipe(client, handler, recipe)) {
            return false;
        }

        if (tryQuickMoveOutput(client, handler)) {
            fakeProgressTicks = 0;
            return true;
        }

        //#if MC<12103
        if (recipeResultWaitTicks > 0) {
            return true;
        }
        //#endif
        return handler.getSlot(OUTPUT_SLOT).hasStack();
    }

    //#if MC<12103
    private boolean waitForRecipeResult(MinecraftClient client, StonecutterScreenHandler handler) {
        if (recipeResultWaitTicks <= 0) {
            return false;
        }

        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
            recipeResultWaitTicks = 0;
            return false;
        }

        recipeResultWaitTicks--;
        if (recipeResultWaitTicks <= 0) {
            stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
        }
        return true;
    }
    //#endif

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
            //#if MC<12103
            return false;
            //#else
            //$$ return true;
            //#endif
        }

        if (dropOutputsBeforeTakingAndTryTake(client, handler, resultTemplate, OUTPUT_TAKE_ATTEMPTS_AFTER_DROP)) {
            if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
                ingredientDropLocked = false;
            }
            return true;
        }

        if (!handler.getSlot(OUTPUT_SLOT).hasStack()) {
            ingredientDropLocked = false;
            //#if MC<12103
            return false;
            //#else
            //$$ return true;
            //#endif
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
        //#if MC>=12103
        //$$ if (rapidCraftingActive || singleCraftPending) {
        //$$     return;
        //$$ }
        //#endif
        if (!lockCurrentSelection(client, handler)) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.stonecutter.no_selection"));
            return;
        }

        boolean success = runOneCraftSubLoop(client, handler, lockedRecipe);
        if (!success) {
            //#if MC<12103
            sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
            //#else
            //$$ handleSingleCraftFailure(client, handler);
            //#endif
        }
    }

    //#if MC>=12103
    //$$ private void processPendingSingleCraft(MinecraftClient client, StonecutterScreenHandler handler) {
    //$$     if (!singleCraftPending || rapidCraftingActive) {
    //$$         return;
    //$$     }
    //$$
    //$$     if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
    //$$         ItemStack output = handler.getSlot(OUTPUT_SLOT).getStack();
    //$$         if (!ItemStack.areItemsAndComponentsEqual(output, lockedResultTemplate)) {
    //$$             clearPendingSingleCraft();
    //$$             return;
    //$$         }
    //$$
    //$$         if (runOneCraftSubLoop(client, handler, lockedRecipe)) {
    //$$             clearPendingSingleCraft();
    //$$             return;
    //$$         }
    //$$     }
    //$$
    //$$     singleCraftWaitTicks++;
    //$$     if (singleCraftWaitTicks >= SERVER_SYNC_TIMEOUT_TICKS) {
    //$$         clearPendingSingleCraft();
    //$$         sendStatusMessage(client, Text.translatable("quickcraft.message.stonecutter.sync_timeout"));
    //$$     }
    //$$ }
    //$$
    //$$ private void handleSingleCraftFailure(MinecraftClient client, StonecutterScreenHandler handler) {
    //$$     if (isIngredientUnavailable(client, handler, lockedRecipe)) {
    //$$         sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
    //$$     } else {
    //$$         singleCraftPending = true;
    //$$         singleCraftWaitTicks = 0;
    //$$     }
    //$$ }
    //$$
    //$$ private boolean isIngredientUnavailable(MinecraftClient client,
    //$$                                         StonecutterScreenHandler handler,
    //$$                                         RecipeEntry<StonecuttingRecipe> recipe) {
    //$$     return !handler.getSlot(OUTPUT_SLOT).hasStack()
    //$$             && !handler.getSlot(INPUT_SLOT).hasStack()
    //$$             && findBestSupplyIngredientSlot(client.player.getInventory(), handler, recipe) == -1;
    //$$ }
    //$$
    //$$ private void clearPendingSingleCraft() {
    //$$     singleCraftPending = false;
    //$$     singleCraftWaitTicks = 0;
    //$$ }
    //#endif

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

        //#if MC<12103
        int recipeIndex = findAvailableRecipeIndex(handler, recipe);
        if (recipeIndex < 0) {
            recipeIndex = findAvailableRecipeIndexByResult(client, handler, lockedResultTemplate);
        }
        if (recipeIndex < 0) {
            recipeIndex = lockedRecipeIndex;
        }
        //#else
        //$$ int recipeIndex = isRecipeIndexAvailable(handler, lockedRecipeIndex) ? lockedRecipeIndex : -1;
        if (recipeIndex < 0) {
            //$$ recipeIndex = findAvailableRecipeIndex(handler, recipe);
        }
        if (recipeIndex < 0) {
            //$$ recipeIndex = findAvailableRecipeIndexByResult(client, handler, lockedResultTemplate);
        }
        //#endif
        if (!isRecipeIndexAvailable(handler, recipeIndex)) {
            return false;
        }

        try {
            //#if MC<12103
            if (handler.getSelectedRecipe() != recipeIndex || !handler.getSlot(OUTPUT_SLOT).hasStack()) {
            //#else
            // 1.21.2+ 客户端只有配方展示数据，重复本地 onButtonClick 不能生成真实产物。
            //$$ boolean selectionChanged = handler.getSelectedRecipe() != recipeIndex;
            //$$ if (selectionChanged) {
            //#endif
                handler.onButtonClick(client.player, recipeIndex);
                client.interactionManager.clickButton(handler.syncId, recipeIndex);
            }
            //#if MC<12103
            if (rapidCraftingActive && !handler.getSlot(OUTPUT_SLOT).hasStack()) {
                recipeResultWaitTicks = RECIPE_RESULT_WAIT_TICKS;
            }
            //#endif
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

        int invIndex = findBestSupplyIngredientSlot(client.player.getInventory(), handler, recipe);
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
        //#if MC>=12103
        //$$ boolean canAcceptOutput = canAcceptOutputInMainInventory(client.player.getInventory(), before);
        //#endif
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
                || countMatchingItems(client.player.getInventory(), before) > beforeResultCount
                //#if MC>=12103
                //$$ || canAcceptOutput
                //#endif
                ;
    }

    private boolean canAcceptOutputInMainInventory(PlayerInventory inventory, ItemStack output) {
        if (output.isEmpty()) {
            return false;
        }

        for (ItemStack stack : mainStacks(inventory)) {
            if (stack.isEmpty()) {
                return true;
            }
            if (ItemStack.areItemsAndComponentsEqual(stack, output)
                    && stack.getCount() < Math.min(stack.getMaxCount(), output.getMaxCount())) {
                return true;
            }
        }
        return false;
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

            for (int invIndex = 0; invIndex < mainStacks(inventory).size(); invIndex++) {
                ItemStack stack = mainStacks(inventory).get(invIndex);
                if (stack.isEmpty()) continue;
                if (!ItemStack.areItemsAndComponentsEqual(stack, resultTemplate)) continue;

                int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
                if (handlerSlot == -1
                        || QuickContainerLock.isLockedSlot(handler, handlerSlot)
                        || !handler.getSlot(handlerSlot).hasStack()) continue;

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
            int invIndex = findBestDroppableIngredientSlot(client.player.getInventory(), handler, recipe);
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
                                             StonecutterScreenHandler handler,
                                             RecipeEntry<StonecuttingRecipe> recipe) {
        if (recipe == null) {
            return findBestMatchingItemSlot(inventory, handler, lockedInputTemplate, false);
        }

        List<Ingredient> ingredients = recipeIngredients(recipe);
        int bestIndex = -1;
        int bestTotalCount = -1;
        int bestStackCount = -1;

        for (int invIndex = 0; invIndex < mainStacks(inventory).size(); invIndex++) {
            ItemStack stack = mainStacks(inventory).get(invIndex);
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

    private int findBestDroppableIngredientSlot(PlayerInventory inventory,
                                                StonecutterScreenHandler handler,
                                                RecipeEntry<StonecuttingRecipe> recipe) {
        if (recipe == null) {
            return findBestMatchingItemSlot(inventory, handler, lockedInputTemplate, true);
        }

        List<Ingredient> ingredients = recipeIngredients(recipe);
        int bestIndex = -1;
        int bestTotalCount = -1;
        int bestStackCount = -1;

        for (int invIndex = 0; invIndex < mainStacks(inventory).size(); invIndex++) {
            ItemStack stack = mainStacks(inventory).get(invIndex);
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

    private int findBestMatchingItemSlot(PlayerInventory inventory,
                                         StonecutterScreenHandler handler,
                                         ItemStack template,
                                         boolean requireExtraItem) {
        if (template.isEmpty()) {
            return -1;
        }

        int bestIndex = -1;
        int bestTotalCount = -1;
        int bestStackCount = -1;

        for (int invIndex = 0; invIndex < mainStacks(inventory).size(); invIndex++) {
            ItemStack stack = mainStacks(inventory).get(invIndex);
            if (stack.isEmpty()) continue;
            if (isLockedPlayerInventorySlot(handler, invIndex)) continue;
            if (requireExtraItem && stack.getCount() <= 1) continue;
            if (!ItemStack.areItemsAndComponentsEqual(stack, template)) continue;

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

    private int countMatchingUnlockedItems(PlayerInventory inventory,
                                           StonecutterScreenHandler handler,
                                           ItemStack template) {
        int total = 0;
        for (int invIndex = 0; invIndex < mainStacks(inventory).size(); invIndex++) {
            ItemStack stack = mainStacks(inventory).get(invIndex);
            if (!stack.isEmpty()
                    && !isLockedPlayerInventorySlot(handler, invIndex)
                    && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean isLockedPlayerInventorySlot(StonecutterScreenHandler handler, int invIndex) {
        int handlerSlot = playerInventoryIndexToHandlerSlot(invIndex);
        return handlerSlot == -1 || QuickContainerLock.isLockedSlot(handler, handlerSlot);
    }

    private boolean matchesAnyIngredient(ItemStack stack, List<Ingredient> ingredients) {
        for (Ingredient ingredient : ingredients) {
            //#if MC<12103
            if (ingredient == null || ingredient.isEmpty()) continue;
            //#elseif MC<12104
            //$$ if (ingredient == null || ingredient.getMatchingItems().isEmpty()) continue;
            //#else
            //$$ if (ingredient == null || ingredient.isEmpty()) continue;
            //#endif
            if (ingredient.test(stack)) return true;
        }
        return false;
    }

    private static List<Ingredient> recipeIngredients(RecipeEntry<StonecuttingRecipe> recipe) {
        //#if MC<12103
        return recipe.value().getIngredients();
        //#else
        //$$ return recipe.value().getIngredientPlacement().getIngredients();
        //#endif
    }

    private ItemStack getRecipeResultStack(MinecraftClient client, RecipeEntry<StonecuttingRecipe> recipe) {
        if (!lockedResultTemplate.isEmpty()) {
            return lockedResultTemplate.copy();
        }
        return craftRecipeResult(client, recipe);
    }

    private ItemStack craftRecipeResult(MinecraftClient client, RecipeEntry<StonecuttingRecipe> recipe) {
        if (recipe == null) {
            return ItemStack.EMPTY;
        }

        try {
            ItemStack input = client.player.currentScreenHandler.getSlot(INPUT_SLOT).getStack().copy();
            return recipe.value().craft(new SingleStackRecipeInput(input), client.world.getRegistryManager()).copy();
        } catch (Throwable throwable) {
            return ItemStack.EMPTY;
        }
    }

    private boolean lockCurrentSelection(MinecraftClient client, StonecutterScreenHandler handler) {
        int selectedIndex = handler.getSelectedRecipe();
        if (!isRecipeIndexAvailable(handler, selectedIndex) && handler.getSlot(OUTPUT_SLOT).hasStack()) {
            selectedIndex = findAvailableRecipeIndexByResult(client, handler, handler.getSlot(OUTPUT_SLOT).getStack());
        }

        RecipeEntry<StonecuttingRecipe> recipe = getRecipeAt(handler, selectedIndex);
        ItemStack resultTemplate = ItemStack.EMPTY;
        if (handler.getSlot(OUTPUT_SLOT).hasStack()) {
            resultTemplate = handler.getSlot(OUTPUT_SLOT).getStack().copy();
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
        lockedInputTemplate = copyTemplate(handler.getSlot(INPUT_SLOT).getStack());
        lockedResultTemplate = copyTemplate(resultTemplate);
        return true;
    }

    private RecipeEntry<StonecuttingRecipe> getRecipeAt(StonecutterScreenHandler handler, int recipeIndex) {
        if (!isRecipeIndexAvailable(handler, recipeIndex)) {
            return null;
        }

        //#if MC<12103
        return handler.getAvailableRecipes().get(recipeIndex);
        //#else
        //$$ return handler.getAvailableRecipes().entries().get(recipeIndex).recipe().recipe().orElse(null);
        //#endif
    }

    private int findAvailableRecipeIndex(StonecutterScreenHandler handler,
                                         RecipeEntry<StonecuttingRecipe> recipe) {
        if (recipe == null) {
            return -1;
        }

        //#if MC<12103
        List<RecipeEntry<StonecuttingRecipe>> recipes = handler.getAvailableRecipes();
        for (int i = 0; i < recipes.size(); i++) {
            if (recipes.get(i).id().equals(recipe.id())) {
                return i;
            }
        }
        //#else
        //$$ List<CuttingRecipeDisplay.GroupEntry<StonecuttingRecipe>> entries = handler.getAvailableRecipes().entries();
        //$$ for (int i = 0; i < entries.size(); i++) {
            //$$ RecipeEntry<StonecuttingRecipe> availableRecipe = entries.get(i).recipe().recipe().orElse(null);
            //$$ if (availableRecipe != null && availableRecipe.id().equals(recipe.id())) {
            //$$     return i;
            //$$ }
        //$$ }
        //#endif
        return -1;
    }

    private boolean isRecipeIndexAvailable(StonecutterScreenHandler handler, int recipeIndex) {
        return recipeIndex >= 0 && recipeIndex < availableRecipeCount(handler);
    }

    private int availableRecipeCount(StonecutterScreenHandler handler) {
        //#if MC<12103
        return handler.getAvailableRecipes().size();
        //#else
        //$$ return handler.getAvailableRecipeCount();
        //#endif
    }

    private int findAvailableRecipeIndexByResult(MinecraftClient client,
                                                 StonecutterScreenHandler handler,
                                                 ItemStack resultTemplate) {
        if (resultTemplate.isEmpty()) {
            return -1;
        }

        //#if MC>=12103 && MC<12105
        //$$ int matchedIndex = -1;
        //#endif
        for (int i = 0; i < availableRecipeCount(handler); i++) {
            ItemStack displayedResult = getDisplayResultStack(client, handler, i);
            if (!displayedResult.isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(displayedResult, resultTemplate)) {
                //#if MC<12103
                return i;
                //#elseif MC<12105
                //$$ if (matchedIndex >= 0) {
                //$$     return -1;
                //$$ }
                //$$ matchedIndex = i;
                //#else
                //$$ return i;
                //#endif
            }
        }
        //#if MC<12103
        return -1;
        //#elseif MC<12105
        //$$ return matchedIndex;
        //#else
        //$$ return -1;
        //#endif
    }

    private ItemStack getDisplayResultStack(MinecraftClient client,
                                            StonecutterScreenHandler handler,
                                            int recipeIndex) {
        if (client.world == null || !isRecipeIndexAvailable(handler, recipeIndex)) {
            return ItemStack.EMPTY;
        }

        try {
            //#if MC<12103
            return craftRecipeResult(client, getRecipeAt(handler, recipeIndex));
            //#else
            //$$ return handler.getAvailableRecipes()
            //$$         .entries()
            //$$         .get(recipeIndex)
            //$$         .recipe()
            //$$         .optionDisplay()
            //$$         .getFirst(SlotDisplayContexts.createParameters(client.world))
            //$$         .copy();
            //#endif
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

    private void handleHotkeys(MinecraftClient client, StonecutterScreenHandler handler) {
        boolean vDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getSingleCraftHotkey());
        boolean rapidDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());

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
                                    StonecutterScreenHandler handler,
                                    boolean fromButton) {
        //#if MC>=12103
        //$$ clearPendingSingleCraft();
        //#endif
        if (!lockCurrentSelection(client, handler)) {
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
        //#if MC<12103
        recipeResultWaitTicks = 0;
        //#endif

        refreshProgressSnapshot(client, lockedRecipe);
        sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.started"));
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
            stopRapidCraft(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
        }
    }

    private boolean hasMatchingUnlockedItemInInventory(PlayerInventory inventory,
                                                       StonecutterScreenHandler handler,
                                                       ItemStack template) {
        if (template.isEmpty()) {
            return false;
        }

        for (int invIndex = 0; invIndex < mainStacks(inventory).size(); invIndex++) {
            ItemStack stack = mainStacks(inventory).get(invIndex);
            if (stack.isEmpty()) continue;
            if (isLockedPlayerInventorySlot(handler, invIndex)) continue;
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
        for (ItemStack stack : mainStacks(inventory)) {
            if (stack.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countEmptyMainSlots(PlayerInventory inventory) {
        int total = 0;
        for (ItemStack stack : mainStacks(inventory)) {
            if (stack.isEmpty()) {
                total++;
            }
        }
        return total;
    }

    private static List<ItemStack> mainStacks(PlayerInventory inventory) {
        //#if MC<12105
        return inventory.main;
        //#else
        //$$ return inventory.getMainStacks();
        //#endif
    }

    private void sendStatusMessage(MinecraftClient client, Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    private void stopRapidCraft(MinecraftClient client, Text message) {
        if (hasLockedSelection() && QuickCraftConfigs.isDropCraftResultsOnStopEnabled()) {
            dropCraftResultsAfterStop(client, (StonecutterScreenHandler) client.player.currentScreenHandler, lockedRecipe);
        }

        rapidCraftingActive = false;
        rapidCraftStartedByButton = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        noProgressTicks = 0;
        ingredientDropLocked = false;
        lastObservedOutputSignature = 0;
        //#if MC<12103
        recipeResultWaitTicks = 0;
        //#endif
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
        //#if MC<12103
        recipeResultWaitTicks = 0;
        //#else
        //$$ clearPendingSingleCraft();
        //#endif
        lastVDown = false;
        lastAltCDown = false;
    }

    private boolean isCraftButtonRapidModeHeld(MinecraftClient client) {
        return QuickCraftKeyBindings.isAltDown()
                && QuickCraftKeyBindings.isLeftMouseButtonDown(client);
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
