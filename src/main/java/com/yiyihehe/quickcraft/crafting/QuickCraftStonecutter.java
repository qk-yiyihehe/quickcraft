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
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/**
 * 切石机快速合成的客户端入口。
 *
 * <p>本类负责切石机界面的单次/连续合成：锁定玩家当前选择的切石配方和输出模板，
 * 后续通过原版选择按钮与槽位点击补输入、取输出。按钮注入、配置项和语言文本由其它模块提供。</p>
 *
 * <p>切石机的可用配方列表会随输入槽刷新；连续合成期间不能只信任旧索引，
 * 因此这里会按配方 id 和输出物品回找当前可用索引。</p>
 */
public class QuickCraftStonecutter implements ClientModInitializer {
    private static QuickCraftStonecutter INSTANCE;

    // 每 tick 驱动一次；单 tick 内循环次数由 craftLoopsPerTick 配置控制。
    private static final int RAPID_INTERVAL = 1;
    // 丢出已有成品后最多再尝试拿取两次，用来吸收客户端槽位状态的短暂不同步。
    private static final int OUTPUT_TAKE_ATTEMPTS_AFTER_DROP = 2;
    // 连续无进展后停机，避免库存满、配方失效或服务端拒绝点击时无限发包。
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    // 库存数量和空槽连续没有变化时停止，补足只看输出槽变化可能漏掉“假成功”。
    private static final int MAX_NO_PROGRESS_TICKS = 3;
    // StonecutterScreenHandler：0 是输入槽，1 是输出槽。
    private static final int INPUT_SLOT = 0;
    private static final int OUTPUT_SLOT = 1;
    // 丢原料后如果输出仍无法拿走，只允许少量“看似有动作”的轮次，避免持续丢料。
    private static final int MAX_FAKE_PROGRESS = 3;

    private boolean lastVDown = false;
    private boolean lastAltCDown = false;
    private boolean rapidCraftingActive = false;
    private boolean rapidCraftStartedByButton = false;
    private int rapidCooldown = 0;
    private int consecutiveFailures = 0;

    // 连续合成期间锁定的选择；索引用于快速复用，配方和输出模板用于列表刷新后的回找。
    private RecipeEntry<StonecuttingRecipe> lockedRecipe = null;
    private int lockedRecipeIndex = -1;
    private ItemStack lockedInputTemplate = ItemStack.EMPTY;
    private ItemStack lockedResultTemplate = ItemStack.EMPTY;
    private int noProgressTicks = 0;
    private int lastResultCount = -1;
    private int lastEmptySlots = -1;

    // 同一个输出状态只允许丢一次原料，防止输出槽堵住时把原料持续扔到地上。
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

    /**
     * 执行一次切石机快速合成子循环。
     *
     * <p>优先取走已有输出；输出拿不走时先尝试腾出成品空间，再补输入、重新选择配方。
     * 切石机只有单输入槽，所以不需要像 2x2/3x3 合成那样维护格子图案。</p>
     */
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

        if (!handler.getSlot(OUTPUT_SLOT).hasStack() && !clickSelectedRecipe(client, handler, recipe)) {
            return false;
        }

        if (tryQuickMoveOutput(client, handler)) {
            fakeProgressTicks = 0;
            return true;
        }

        return handler.getSlot(OUTPUT_SLOT).hasStack();
    }

    /**
     * 处理输出槽已生成但无法放入背包的情况。
     *
     * <p>先丢同类成品腾空间；仍失败时最多丢一份可补回的原料，并用 {@code ingredientDropLocked}
     * 锁住当前输出状态，避免同一个堵塞状态下连续丢料。</p>
     */
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
        if (!lockCurrentSelection(client, handler)) {
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
            recipeIndex = findAvailableRecipeIndexByResult(client, handler, lockedResultTemplate);
        }
        if (recipeIndex < 0) {
            recipeIndex = lockedRecipeIndex;
        }
        if (!isRecipeIndexAvailable(handler, recipeIndex)) {
            return false;
        }

        try {
            // 先更新本地 handler 选择，再发送 clickButton；1.21/1.21.1 的切石机依赖这两个状态一起同步输出。
            if (handler.getSelectedRecipe() != recipeIndex || !handler.getSlot(OUTPUT_SLOT).hasStack()) {
                handler.onButtonClick(client.player, recipeIndex);
                client.interactionManager.clickButton(handler.syncId, recipeIndex);
            }
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

    /**
     * 跟踪输出槽是否已经换成另一份结果。
     *
     * <p>{@code ingredientDropLocked} 只保护当前输出状态；输出消失或物品/数量/组件变化后允许下一轮回退。</p>
     */
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
        if (recipe == null) {
            return findBestMatchingItemSlot(inventory, lockedInputTemplate, false);
        }

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
        if (recipe == null) {
            return findBestMatchingItemSlot(inventory, lockedInputTemplate, true);
        }

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

    private int findBestMatchingItemSlot(PlayerInventory inventory, ItemStack template, boolean requireExtraItem) {
        if (template.isEmpty()) {
            return -1;
        }

        int bestIndex = -1;
        int bestTotalCount = -1;
        int bestStackCount = -1;

        for (int invIndex = 0; invIndex < inventory.main.size(); invIndex++) {
            ItemStack stack = inventory.main.get(invIndex);
            if (stack.isEmpty()) continue;
            if (requireExtraItem && stack.getCount() <= 1) continue;
            if (!ItemStack.areItemsAndComponentsEqual(stack, template)) continue;

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

    /**
     * 锁定当前切石机选择。
     *
     * <p>优先记录当前索引和配方；如果输出槽已有结果，也保存输出模板。后续配方列表刷新时，
     * 输出模板可以作为兜底，防止索引指向另一个切石结果。</p>
     */
    private boolean lockCurrentSelection(MinecraftClient client, StonecutterScreenHandler handler) {
        clearLockedSelection();

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
            return false;
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

        return handler.getAvailableRecipes().get(recipeIndex);
    }

    private int findAvailableRecipeIndex(StonecutterScreenHandler handler,
                                         RecipeEntry<StonecuttingRecipe> recipe) {
        if (recipe == null) {
            return -1;
        }

        List<RecipeEntry<StonecuttingRecipe>> recipes = handler.getAvailableRecipes();
        for (int i = 0; i < recipes.size(); i++) {
            if (recipes.get(i).id().equals(recipe.id())) {
                return i;
            }
        }
        return -1;
    }

    private boolean isRecipeIndexAvailable(StonecutterScreenHandler handler, int recipeIndex) {
        return recipeIndex >= 0 && recipeIndex < handler.getAvailableRecipes().size();
    }

    /**
     * 配方 id 找不到时按显示结果回找索引。
     *
     * <p>切石机输入变化会重建可用列表，旧索引只作为最后兜底；结果模板能更直观地对应玩家锁定的按钮。</p>
     */
    private int findAvailableRecipeIndexByResult(MinecraftClient client,
                                                 StonecutterScreenHandler handler,
                                                 ItemStack resultTemplate) {
        if (resultTemplate.isEmpty()) {
            return -1;
        }

        List<RecipeEntry<StonecuttingRecipe>> recipes = handler.getAvailableRecipes();
        for (int i = 0; i < recipes.size(); i++) {
            ItemStack displayedResult = getDisplayResultStack(client, handler, i);
            if (!displayedResult.isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(displayedResult, resultTemplate)) {
                return i;
            }
        }
        return -1;
    }

    private ItemStack getDisplayResultStack(MinecraftClient client,
                                            StonecutterScreenHandler handler,
                                            int recipeIndex) {
        if (client.world == null || !isRecipeIndexAvailable(handler, recipeIndex)) {
            return ItemStack.EMPTY;
        }

        try {
            return craftRecipeResult(client, getRecipeAt(handler, recipeIndex));
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

    /**
     * 把玩家背包索引映射到当前屏幕 handler 的槽位编号。
     *
     * <p>这是 1.21/1.21.1 {@link StonecutterScreenHandler} 的布局：热栏在 29-37，主背包在 2-28。</p>
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

    /**
     * 用库存结果数量和空槽数量辅助判断是否真的有进展。
     *
     * <p>某些堵塞回退会让槽位点击返回“有动作”，但成品没有增加、空槽也没有释放；
     * 连续出现这种状态就停止，避免快速合成在服务端拒绝或背包满时假运行。</p>
     */
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
