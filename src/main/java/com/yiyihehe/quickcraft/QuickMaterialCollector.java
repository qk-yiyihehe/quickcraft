package com.yiyihehe.quickcraft;

import static com.yiyihehe.quickcraft.QuickMaterialCollectorShulkerSelection.*;

import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialLists;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 自动收集当前材料 HUD 缺失物品。
 * 所有搬运都通过原版槽位点击完成，避免直接改客户端背包造成幽灵物品。
 */
public final class QuickMaterialCollector implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickMaterialCollector.class);
    private static boolean materialListFailureLogged;
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int REOPEN_DELAY_TICKS = 2;
    private static final int LONG_PRESS_TICKS = 4;
    private static final int FALLBACK_SUCCESS_COLOR = 0x11FF11;
    private static final int FALLBACK_SHORTAGE_COLOR = 0xFF9100;
    // 缺失数量阈值余量默认值：0-10 +0，10-20 +1，20-50 +3，50-100 +5，100-500 +10，500+ +32。
    private static final int EXTRA_ALLOWANCE_LIMIT_10 = 10;
    private static final int EXTRA_ALLOWANCE_LIMIT_20 = 20;
    private static final int EXTRA_ALLOWANCE_LIMIT_50 = 50;
    private static final int EXTRA_ALLOWANCE_LIMIT_100 = 100;
    private static final int EXTRA_ALLOWANCE_LIMIT_500 = 500;

    private static CollectionTask activeTask;
    private static CollectionTask pendingReturn;
    private static BlockPos completedTarget;
    private static boolean suppressUseUntilRelease;
    private static boolean longPressSessionActive;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        if (pendingReturn != null) {
            if (client.world != pendingReturn.world || client.player == null
                    || client.player.getInventory() != pendingReturn.inventory) {
                pendingReturn = null;
            } else {
                int index = findPendingSourceIndex(pendingReturn);
                if (index < 0) {
                    pendingReturn = null;
                } else {
                    pendingReturn.sourcePlayerIndex = index;
                }
            }
        }
        if (!QuickCraftConfigs.isAutoCollectMaterialsEnabled()) {
            completedTarget = null;
            suppressUseUntilRelease = false;
            longPressSessionActive = false;
            if (activeTask == null) {
                return;
            }
            if (!activeTask.longPressActivated) {
                stopTask(client, true);
                return;
            }
            activeTask.stopRequested = true;
            processTask(client);
            return;
        }

        boolean useDown = client.player != null
                && client.world != null
                && QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.useKey);
        if (!useDown) {
            if (activeTask != null) {
                activeTask.stopRequested = true;
            }
            completedTarget = null;
            suppressUseUntilRelease = false;
            longPressSessionActive = false;
        } else if (activeTask == null) {
            // 长按期间切换到新容器时，每个目标都重新发起一次交互。
            tryStartTask(client);
        }

        if (activeTask != null && !activeTask.longPressActivated) {
            if (!useDown) {
                sendStatusMessage(
                        client,
                        Text.translatable("quickcraft.message.material_collector.hold_to_collect")
                                .formatted(Formatting.RED)
                );
                stopTask(client, true);
                return;
            }
            if (++activeTask.holdTicks < LONG_PRESS_TICKS) {
                return;
            }
            activeTask.longPressActivated = true;
            longPressSessionActive = true;
        }
        processTask(client);
    }

    private void tryStartTask(MinecraftClient client) {
        var player = client.player;
        var interactionManager = client.interactionManager;
        if (player == null
                || client.world == null
                || interactionManager == null
                || !(client.crosshairTarget instanceof BlockHitResult hitResult)
                || !isLookingAtSupportedBlock(client)) {
            return;
        }

        BlockPos target = hitResult.getBlockPos().toImmutable();
        boolean returning = pendingReturn != null && target.equals(pendingReturn.target);
        if (target.equals(completedTarget) || (!returning && !hasVisibleMaterialLists(player))) {
            return;
        }

        ScreenHandler openHandler = getOpenHandledContainer(client);
        if (openHandler != null) {
            if (!isSupportedHandler(openHandler) || !openHandler.getCursorStack().isEmpty()) {
                return;
            }
            activeTask = returning ? resumePendingReturn() : new CollectionTask(client, hitResult, target, CollectionStage.COLLECT_TARGET);
            activeTask.longPressActivated = longPressSessionActive;
            return;
        }

        if (client.currentScreen != null) {
            return;
        }

        activeTask = returning ? resumePendingReturn() : new CollectionTask(client, hitResult, target, CollectionStage.WAIT_TARGET);
        if (returning) {
            activeTask.stage = CollectionStage.WAIT_REOPEN;
        }
        activeTask.longPressActivated = longPressSessionActive;
        interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
    }

    private void processTask(MinecraftClient client) {
        if (activeTask == null) {
            return;
        }
        if (client.player == null || client.world != activeTask.world
                || client.player.getInventory() != activeTask.inventory || client.interactionManager == null) {
            stopTask(client, false);
            return;
        }

        switch (activeTask.stage) {
            case WAIT_TARGET -> waitForTarget(client);
            case COLLECT_TARGET -> collectFromTarget(client);
            case WAIT_SHULKER -> waitForSourceShulker(client);
            case EXTRACT_SHULKER -> extractFromSourceShulker(client);
            case REOPEN_DELAY -> waitBeforeReopen(client);
            case WAIT_REOPEN -> waitForReopenedTarget(client);
            case RETURN_SHULKER -> returnSourceShulker(client);
        }
    }

    private void waitForTarget(MinecraftClient client) {
        ScreenHandler handler = getOpenHandledContainer(client);
        if (handler != null) {
            if (!isSupportedHandler(handler)) {
                stopTask(client, true);
                return;
            }
            activeTask.stage = CollectionStage.COLLECT_TARGET;
            activeTask.ticks = 0;
            return;
        }

        if (++activeTask.ticks > OPEN_TIMEOUT_TICKS) {
            stopTask(client, false);
        }
    }

    private void collectFromTarget(MinecraftClient client) {
        PlayerEntity player = client.player;
        ScreenHandler handler = getOpenHandledContainer(client);
        if (player == null || handler == null || !isSupportedHandler(handler)) {
            stopTask(client, true);
            return;
        }

        if (activeTask.stopRequested && activeTask.sourcePlayerIndex < 0) {
            finishTarget(client);
            return;
        }

        if (activeTask.plan == null) {
            activeTask.plan = buildMaterialPlan(player);
        }

        int remainingBefore = getTotalRemaining(activeTask.plan.demands());
        if (shouldUseQuickShulker()) {
            packPlayerTargetMaterials(client, handler);
            // 先用最后的空格周转来源盒，避免散装材料占满后仍有随身盒容量却无法开盒。
            if (countEmptyPlayerStorageSlots(handler) == 1 && tryOpenSourceShulker(client, handler)) {
                return;
            }
            collectToShulkersOrPlayer(handler, activeTask.plan.demands(), activeTask.plan.targetTemplates());
            packPlayerTargetMaterials(client, handler);
        } else {
            collectToPlayer(handler, activeTask.plan.demands(), activeTask.plan.targetTemplates());
        }

        if (activeTask.stopRequested || activeTask.plan.demands().stream().noneMatch(demand -> demand.remaining() > 0)) {
            finishTarget(client);
            return;
        }

        if (getTotalRemaining(activeTask.plan.demands()) < remainingBefore) {
            return;
        }

        // 最后一格需留给来源盒临时开盒；不能等它也被占满才收空盒。
        if (shouldUseQuickShulker()
                && countEmptyPlayerStorageSlots(handler) <= 1
                && takeEmptyContainerShulker(handler, activeTask.plan.targetTemplates())) {
            activeTask.processedSourceSlots.clear();
            return;
        }

        if (!tryOpenSourceShulker(client, handler)) {
            finishTarget(client);
        }
    }

    private boolean tryOpenSourceShulker(MinecraftClient client, ScreenHandler handler) {
        if (!canOpenQuickShulker() || pendingReturn != null || !handler.getCursorStack().isEmpty()) {
            return false;
        }
        Slot source = findExternalSourceShulker(handler, activeTask);
        Slot destination = source != null ? findEmptyPlayerStorageSlot(handler) : null;
        if (source == null || destination == null) {
            return false;
        }

        activeTask.processedSourceSlots.add(source.id);
        activeTask.sourceContainerSlotId = source.id;
        activeTask.sourcePlayerIndex = destination.getIndex();
        activeTask.sourceStack = source.getStack().copy();
        clickSlot(handler, source.id, 0, SlotActionType.PICKUP);
        clickSlot(handler, destination.id, 0, SlotActionType.PICKUP);

        Slot moved = findPlayerStorageSlotByIndex(handler, activeTask.sourcePlayerIndex);
        if (moved == null || !moved.hasStack() || !isShulkerBox(moved.getStack())) {
            activeTask.stage = CollectionStage.RETURN_SHULKER;
            if (isShulkerBox(handler.getCursorStack()) && !source.hasStack()) {
                clickSlot(handler, source.id, 0, SlotActionType.PICKUP);
            }
            return true;
        }

        if (!sendOpenQuickShulkerPacket(moved.id)) {
            activeTask.stage = CollectionStage.REOPEN_DELAY;
            activeTask.ticks = REOPEN_DELAY_TICKS;
            return true;
        }

        activeTask.previousSyncId = handler.syncId;
        activeTask.stage = CollectionStage.WAIT_SHULKER;
        activeTask.ticks = 0;
        return true;
    }

    private int getTotalRemaining(List<Demand> demands) {
        int total = 0;
        for (Demand demand : demands) {
            total += demand.remaining();
        }
        return total;
    }

    private boolean takeEmptyContainerShulker(ScreenHandler handler, List<ItemStack> targetTemplates) {
        Slot shulkerSlot = findEmptyContainerShulker(handler);
        if (shulkerSlot == null || !handler.getCursorStack().isEmpty()) {
            return false;
        }

        Slot materialSlot = findPlayerTargetMaterialSlot(handler, targetTemplates, shulkerSlot.getStack());
        if (materialSlot == null) {
            return false;
        }

        ItemStack material = materialSlot.getStack().copy();
        clickSlot(handler, shulkerSlot.id, 0, SlotActionType.PICKUP);
        if (handler.getCursorStack().isEmpty() || !isShulkerBox(handler.getCursorStack())) {
            return false;
        }

        // 满包时先用空盒换出一组投影材料，再把光标材料塞回刚进入背包的盒子。
        clickSlot(handler, materialSlot.id, 0, SlotActionType.PICKUP);
        if (!materialSlot.hasStack()
                || !isShulkerBox(materialSlot.getStack())
                || handler.getCursorStack().isEmpty()
                || !stacksExactlyMatch(handler.getCursorStack(), material)) {
            if (materialSlot.hasStack()
                    && isShulkerBox(materialSlot.getStack())
                    && !handler.getCursorStack().isEmpty()
                    && !isShulkerBox(handler.getCursorStack())) {
                clickSlot(handler, materialSlot.id, 0, SlotActionType.PICKUP);
            }
            if (isShulkerBox(handler.getCursorStack()) && !shulkerSlot.hasStack()) {
                clickSlot(handler, shulkerSlot.id, 0, SlotActionType.PICKUP);
            }
            return false;
        }

        int previousCount;
        do {
            previousCount = handler.getCursorStack().getCount();
            clickSlot(handler, materialSlot.id, 1, SlotActionType.PICKUP);
        } while (!handler.getCursorStack().isEmpty()
                && handler.getCursorStack().getCount() < previousCount);

        if (!handler.getCursorStack().isEmpty()) {
            // Quick Shulker 未接管插入时恢复交换，避免材料或盒子留在光标上。
            clickSlot(handler, materialSlot.id, 0, SlotActionType.PICKUP);
            if (isShulkerBox(handler.getCursorStack()) && !shulkerSlot.hasStack()) {
                clickSlot(handler, shulkerSlot.id, 0, SlotActionType.PICKUP);
            }
            return false;
        }
        return materialSlot.hasStack() && isShulkerBox(materialSlot.getStack());
    }

    private Slot findPlayerTargetMaterialSlot(ScreenHandler handler,
                                              List<ItemStack> targetTemplates,
                                              ItemStack shulker) {
        Slot best = null;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasStack()
                    || isShulkerBox(slot.getStack())
                    || !containsTarget(targetTemplates, slot.getStack())
                    || !slot.canInsert(shulker)) {
                continue;
            }
            if (best == null || slot.getStack().getCount() > best.getStack().getCount()) {
                best = slot;
            }
        }
        return best;
    }

    private Slot findEmptyContainerShulker(ScreenHandler handler) {
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.hasStack()
                    && slot.getStack().getCount() == 1
                    && isShulkerBox(slot.getStack())
                    && !hasStoredItems(slot.getStack())) {
                return slot;
            }
        }
        return null;
    }

    private void waitForSourceShulker(MinecraftClient client) {
        ScreenHandler handler = getOpenHandledContainer(client);
        if (handler instanceof ShulkerBoxScreenHandler && handler.syncId != activeTask.previousSyncId) {
            activeTask.stage = CollectionStage.EXTRACT_SHULKER;
            activeTask.ticks = 0;
            return;
        }

        if (++activeTask.ticks > OPEN_TIMEOUT_TICKS) {
            closeCurrentScreen(client);
            activeTask.stage = CollectionStage.REOPEN_DELAY;
            activeTask.ticks = 0;
        }
    }

    private void extractFromSourceShulker(MinecraftClient client) {
        ScreenHandler handler = getOpenHandledContainer(client);
        if (!(handler instanceof ShulkerBoxScreenHandler)) {
            activeTask.stage = CollectionStage.REOPEN_DELAY;
            activeTask.ticks = 0;
            return;
        }

        if (!activeTask.stopRequested) {
            int remainingBefore = getTotalRemaining(activeTask.plan.demands());
            if (shouldUseQuickShulker()) {
                packPlayerTargetMaterials(client, handler);
                collectToShulkersOrPlayer(handler, activeTask.plan.demands(), activeTask.plan.targetTemplates());
                packPlayerTargetMaterials(client, handler);
            } else {
                collectToPlayer(handler, activeTask.plan.demands(), activeTask.plan.targetTemplates());
            }
            // 有进展的来源允许继续取；无进展或开盒失败的来源本轮跳过，避免反复开关。
            if (getTotalRemaining(activeTask.plan.demands()) < remainingBefore) {
                activeTask.processedSourceSlots.remove(activeTask.sourceContainerSlotId);
            }
        }
        closeCurrentScreen(client);
        activeTask.stage = CollectionStage.REOPEN_DELAY;
        activeTask.ticks = 0;
    }

    private void waitBeforeReopen(MinecraftClient client) {
        if (++activeTask.ticks < REOPEN_DELAY_TICKS) {
            return;
        }
        if (client.player == null || client.interactionManager == null) {
            stopTask(client, false);
            return;
        }

        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, activeTask.hitResult);
        activeTask.stage = CollectionStage.WAIT_REOPEN;
        activeTask.ticks = 0;
    }

    private void waitForReopenedTarget(MinecraftClient client) {
        ScreenHandler handler = getOpenHandledContainer(client);
        if (handler != null) {
            if (!isSupportedHandler(handler)) {
                stopTask(client, true);
                return;
            }
            activeTask.stage = CollectionStage.RETURN_SHULKER;
            activeTask.ticks = 0;
            return;
        }

        if (++activeTask.ticks > OPEN_TIMEOUT_TICKS) {
            // 目标箱无法重开时让盒子安全留在玩家背包，不能为了“归还”把它丢出。
            stopTask(client, false);
        }
    }

    private void returnSourceShulker(MinecraftClient client) {
        ScreenHandler handler = getOpenHandledContainer(client);
        if (handler == null || !isSupportedHandler(handler)) {
            stopTask(client, false);
            return;
        }

        Slot source = findPlayerStorageSlotByIndex(handler, activeTask.sourcePlayerIndex);
        if (source == null || !source.hasStack() || !isShulkerBox(source.getStack())) {
            clearReturnedSource(client, handler);
            return;
        }
        if (!handler.getCursorStack().isEmpty()) {
            if (++activeTask.returnTicks > OPEN_TIMEOUT_TICKS) {
                stopTask(client, true);
            }
            return;
        }

        Slot target = source.canTakeItems(client.player)
                ? getReturnContainerSlot(handler, activeTask.sourceContainerSlotId, source.getStack()) : null;
        if (target == null) {
            if (++activeTask.returnTicks > OPEN_TIMEOUT_TICKS) {
                // 容器满时保留盒子在玩家背包，不丢弃也不清除未完成追踪。
                stopTask(client, true);
            }
            return;
        }

        clickSlot(handler, source.id, 0, SlotActionType.PICKUP);
        clickSlot(handler, target.id, 0, SlotActionType.PICKUP);
        if (handler.getCursorStack().isEmpty() && !source.hasStack()) {
            clearReturnedSource(client, handler);
        } else {
            if (!handler.getCursorStack().isEmpty() && !source.hasStack()) {
                clickSlot(handler, source.id, 0, SlotActionType.PICKUP);
            }
            if (++activeTask.returnTicks > OPEN_TIMEOUT_TICKS) {
                stopTask(client, true);
            }
        }
    }

    private void clearReturnedSource(MinecraftClient client, ScreenHandler handler) {
        activeTask.sourceContainerSlotId = -1;
        activeTask.sourcePlayerIndex = -1;
        activeTask.sourceStack = ItemStack.EMPTY;
        packPlayerTargetMaterials(client, handler);
        if (activeTask.stopRequested) {
            finishTarget(client);
            return;
        }

        activeTask.stage = CollectionStage.COLLECT_TARGET;
        activeTask.returnTicks = 0;
    }

    private Slot findExternalSourceShulker(ScreenHandler handler, CollectionTask task) {
        Slot best = null;
        int bestContribution = 0;
        for (Slot slot : getContainerSlots(handler)) {
            if (task.processedSourceSlots.contains(slot.id) || !slot.hasStack() || !isShulkerBox(slot.getStack())) {
                continue;
            }

            int contribution = 0;
            for (ItemStack stored : getStoredStacks(slot.getStack())) {
                Demand demand = findDemand(task.plan.demands(), stored);
                if (demand != null && demand.remaining() > 0) {
                    contribution += Math.min(stored.getCount(), demand.remaining());
                }
            }
            if (contribution > bestContribution) {
                best = slot;
                bestContribution = contribution;
            }
        }
        return best;
    }

    private int countEmptyPlayerStorageSlots(ScreenHandler handler) {
        int count = 0;
        ItemStack shulker = new ItemStack(net.minecraft.item.Items.SHULKER_BOX);
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasStack() && slot.canInsert(shulker)) {
                count++;
            }
        }
        return count;
    }

    private Slot findEmptyPlayerStorageSlot(ScreenHandler handler) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasStack() && slot.canInsert(new ItemStack(net.minecraft.item.Items.SHULKER_BOX))) {
                return slot;
            }
        }
        return null;
    }

    private Slot findPlayerStorageSlotByIndex(ScreenHandler handler, int playerIndex) {
        for (Slot slot : handler.slots) {
            if (isPlayerStorageSlot(slot) && slot.getIndex() == playerIndex) {
                return slot;
            }
        }
        return null;
    }

    private Slot getReturnContainerSlot(ScreenHandler handler, int preferredSlotId, ItemStack shulker) {
        Slot fallback = null;
        for (Slot slot : getContainerSlots(handler)) {
            if (!slot.hasStack() && slot.canInsert(shulker)) {
                if (slot.id == preferredSlotId) {
                    return slot;
                }
                if (fallback == null) {
                    fallback = slot;
                }
            }
        }
        return fallback;
    }

    private ScreenHandler getOpenHandledContainer(MinecraftClient client) {
        if (client == null || client.player == null) {
            return null;
        }
        if (client.currentScreen instanceof HandledScreen<?> screen) {
            return screen.getScreenHandler();
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        return handler == null || handler == client.player.playerScreenHandler || handler.syncId == 0 ? null : handler;
    }

    private boolean sendOpenQuickShulkerPacket(int slotId) {
        return canOpenQuickShulker() && QuickShulkerConnection.sendOpen(slotId);
    }

    private boolean canOpenQuickShulker() {
        return shouldUseQuickShulker() && QuickShulkerConnection.canOpen();
    }

    private void finishTarget(MinecraftClient client) {
        if (activeTask != null && activeTask.longPressActivated && activeTask.plan != null) {
            sendCollectionResult(client, activeTask.plan);
        }
        completedTarget = activeTask != null ? activeTask.target : null;
        stopTask(client, true);
    }

    private void sendCollectionResult(MinecraftClient client, MaterialPlan plan) {
        if (client.player == null) {
            return;
        }

        MutableText details = Text.empty();
        InventoryCounts inventoryCounts = countPlayerInventory(client.player.getInventory());
        int totalCollected = 0;
        int successColor = getMaterialStatusColor(false);
        int shortageColor = getMaterialStatusColor(true);
        for (Demand demand : plan.demands()) {
            int collected = demand.collected();
            if (collected <= 0) {
                continue;
            }

            totalCollected += collected;
            int available = inventoryCounts.available().getInt(demand.template().getItem());
            int shortage = Math.max(0, demand.missing() - available);
            int color = shortage > 0 ? shortageColor : successColor;
            if (totalCollected > collected) {
                details.append(Text.literal(" "));
            }
            MutableText entry = Text.literal(demand.template().getName().getString())
                    .append(Text.literal("×" + formatCollectedAmount(collected, demand.template())))
                    .styled(style -> style.withColor(TextColor.fromRgb(color)));
            details.append(entry);
        }

        if (totalCollected > 0) {
            sendStatusMessage(client, Text.translatable("quickcraft.message.material_collector.result", details));
        }
    }

    private String formatCollectedAmount(int count, ItemStack template) {
        int stackSize = Math.max(1, template.getMaxCount());
        if (stackSize <= 1 || count < stackSize) {
            return Integer.toString(count);
        }

        int groups = count / stackSize;
        int remainder = count % stackSize;
        return remainder == 0
                ? stackSize + "*" + groups
                : stackSize + "*" + groups + "+" + remainder;
    }

    private int getMaterialStatusColor(boolean shortage) {
        int fallback = shortage ? FALLBACK_SHORTAGE_COLOR : FALLBACK_SUCCESS_COLOR;
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            return fallback;
        }
        try {
            return shortage ? QuickLitematicaMaterialLists.getCollectorShortageColor()
                    : QuickLitematicaMaterialLists.getCollectorSuccessColor();
        } catch (RuntimeException | LinkageError failure) {
            logMaterialListFailure(failure);
            return fallback;
        }
    }

    private static void logMaterialListFailure(Throwable failure) {
        if (!materialListFailureLogged) {
            materialListFailureLogged = true;
            LOGGER.warn("Litematica material list bridge failed; using fallback values", failure);
        }
    }

    private void sendStatusMessage(MinecraftClient client, Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    private void stopTask(MinecraftClient client, boolean closeScreen) {
        boolean hasOpenHandler = getOpenHandledContainer(client) != null;
        // 先让原版归置光标物品，再记录待还盒；失败点击可能让盒子仍留在光标上。
        if (closeScreen || hasOpenHandler) {
            closeCurrentScreen(client);
        }
        if (activeTask != null && activeTask.sourcePlayerIndex >= 0
                && client.world == activeTask.world && client.player != null
                && client.player.getInventory() == activeTask.inventory) {
            ItemStack source = activeTask.inventory.getStack(activeTask.sourcePlayerIndex);
            if (isShulkerBox(source) && stacksMatch(source, activeTask.sourceStack)) {
                activeTask.sourceStack = source.copy();
                pendingReturn = activeTask;
                completedTarget = activeTask.target;
                sendStatusMessage(client, Text.translatable("quickcraft.message.material_collector.return_pending"));
            }
        }
        suppressUseUntilRelease |= activeTask != null;
        activeTask = null;
    }

    private CollectionTask resumePendingReturn() {
        CollectionTask task = pendingReturn;
        pendingReturn = null;
        task.stage = CollectionStage.RETURN_SHULKER;
        task.stopRequested = true;
        task.holdTicks = 0;
        task.ticks = 0;
        task.returnTicks = 0;
        return task;
    }

    private int findPendingSourceIndex(CollectionTask task) {
        int preferred = task.sourcePlayerIndex;
        if (preferred >= 0 && stacksExactlyMatch(task.inventory.getStack(preferred), task.sourceStack)) {
            return preferred;
        }
        for (int i = 0; i < 36; i++) {
            if (stacksExactlyMatch(task.inventory.getStack(i), task.sourceStack)) {
                return i;
            }
        }
        return -1;
    }

    private int getTemporarySourcePlayerIndex() {
        return activeTask != null && activeTask.sourcePlayerIndex >= 0 ? activeTask.sourcePlayerIndex
                : pendingReturn != null ? pendingReturn.sourcePlayerIndex : -1;
    }

    public static boolean shouldHideBackgroundHandledScreen() {
        return activeTask != null;
    }

    public static boolean shouldSuppressBackgroundHandledScreenOpen() {
        return activeTask != null || suppressUseUntilRelease;
    }

    public static boolean shouldSuppressUseInput() {
        return activeTask != null || suppressUseUntilRelease;
    }

    public static boolean shouldHandleCurrentTarget(MinecraftClient client) {
        if (!QuickCraftConfigs.isAutoCollectMaterialsEnabled()
                || client == null
                || client.player == null
                || client.world == null) {
            return false;
        }

        QuickMaterialCollector collector = new QuickMaterialCollector();
        return collector.isLookingAtSupportedBlock(client)
                && (collector.hasVisibleMaterialLists(client.player)
                || (pendingReturn != null && client.world == pendingReturn.world
                && client.crosshairTarget instanceof BlockHitResult hit && hit.getBlockPos().equals(pendingReturn.target)));
    }

    private boolean isLookingAtSupportedBlock(MinecraftClient client) {
        HitResult hitResult = client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || client.world == null) {
            return false;
        }

        Block block = client.world.getBlockState(blockHitResult.getBlockPos()).getBlock();
        return block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof EnderChestBlock
                || block instanceof ShulkerBoxBlock;
    }

    private boolean isSupportedHandler(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler || handler instanceof ShulkerBoxScreenHandler;
    }

    private MaterialPlan buildMaterialPlan(PlayerEntity player) {
        List<MaterialRequest> requests = getVisibleMaterialRequests(player);
        List<Demand> demands = new ArrayList<>();
        List<ItemStack> targetTemplates = new ArrayList<>();

        for (MaterialRequest request : requests) {
            if (request.stack().isEmpty()) {
                continue;
            }

            if (!containsTarget(targetTemplates, request.stack())) {
                ItemStack target = request.stack().copy();
                target.setCount(1);
                targetTemplates.add(target);
            }

            if (request.count() <= 0) {
                continue;
            }

            Demand demand = findDemand(demands, request.stack());
            if (demand == null) {
                ItemStack template = request.stack().copy();
                template.setCount(1);
                demands.add(new Demand(template, request.count()));
            } else {
                demand.addMissing(request.count());
            }
        }

        InventoryCounts inventoryCounts = countPlayerInventory(player.getInventory());
        List<PackDemand> packDemands = buildPackDemands(inventoryCounts.boxed(), demands);

        for (int i = demands.size() - 1; i >= 0; i--) {
            Demand demand = demands.get(i);
            int available = inventoryCounts.available().getInt(demand.template().getItem());
            int missing = Math.max(0, demand.missing() - available);
            int remaining = getTargetCollectCount(missing);
            if (remaining <= 0) {
                demands.remove(i);
                continue;
            }
            demand.setRemaining(remaining);
        }

        demands.sort(Comparator
                .comparingInt(Demand::remaining).reversed()
                .thenComparing(demand -> demand.template().getName().getString()));
        return new MaterialPlan(demands, targetTemplates, packDemands);
    }

    private List<PackDemand> buildPackDemands(Object2IntOpenHashMap<Item> boxedCounts, List<Demand> demands) {
        List<PackDemand> packDemands = new ArrayList<>();
        for (Demand demand : demands) {
            // 装盒上限必须跨收集轮次保持稳定，否则背包满足需求后会退回 0-10 档并漏掉此前多拿的余量。
            int desired = demand.missing() + getExtraAllowance(demand.missing());
            int alreadyBoxed = boxedCounts.getInt(demand.template().getItem());
            int remaining = Math.max(0, desired - alreadyBoxed);
            if (remaining <= 0) {
                continue;
            }

            ItemStack template = demand.template().copy();
            template.setCount(1);
            packDemands.add(new PackDemand(template, remaining));
        }

        packDemands.sort(Comparator
                .comparingInt(PackDemand::remaining).reversed()
                .thenComparing(demand -> demand.template().getName().getString()));
        return packDemands;
    }

    private boolean hasVisibleMaterialLists(PlayerEntity player) {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            return false;
        }
        try {
            return QuickLitematicaMaterialLists.hasVisibleMaterialLists(player);
        } catch (RuntimeException | LinkageError failure) {
            logMaterialListFailure(failure);
            return false;
        }
    }

    private List<MaterialRequest> getVisibleMaterialRequests(PlayerEntity player) {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            return List.of();
        }
        try {
            return QuickLitematicaMaterialLists.getVisibleMaterialRequests(player);
        } catch (RuntimeException | LinkageError failure) {
            logMaterialListFailure(failure);
            return List.of();
        }
    }

    private int getTargetCollectCount(int requestedCount) {
        return requestedCount > 0 ? requestedCount + getExtraAllowance(requestedCount) : 0;
    }

    private int getExtraAllowance(int needed) {
        if (needed <= EXTRA_ALLOWANCE_LIMIT_10) {
            return QuickCraftConfigs.getMaterialCollectExtra0To10();
        }
        if (needed <= EXTRA_ALLOWANCE_LIMIT_20) {
            return QuickCraftConfigs.getMaterialCollectExtra10To20();
        }
        if (needed <= EXTRA_ALLOWANCE_LIMIT_50) {
            return QuickCraftConfigs.getMaterialCollectExtra20To50();
        }
        if (needed <= EXTRA_ALLOWANCE_LIMIT_100) {
            return QuickCraftConfigs.getMaterialCollectExtra50To100();
        }
        if (needed <= EXTRA_ALLOWANCE_LIMIT_500) {
            return QuickCraftConfigs.getMaterialCollectExtra100To500();
        }
        return QuickCraftConfigs.getMaterialCollectExtraOver500();
    }

    private void collectToPlayer(ScreenHandler handler, List<Demand> demands, List<ItemStack> targetTemplates) {
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }

        // 只在本次调用内复用；搬运可能改变需求、盒内物品和背包容量，之后必须重新评选。
        WholeShulkerCandidate bestWholeShulker = null;
        boolean searchWholeShulker = true;
        for (Demand demand : demands) {
            if (demand.remaining() <= 0) {
                continue;
            }

            for (Slot source : getContainerSlots(handler)) {
                if (demand.remaining() <= 0) {
                    break;
                }
                if (!source.hasStack()) {
                    continue;
                }

                ItemStack stack = source.getStack();
                if (isShulkerBox(stack)) {
                    if (searchWholeShulker) {
                        bestWholeShulker = findBestWholeShulkerCandidate(handler, demands, targetTemplates);
                        searchWholeShulker = false;
                    }
                    if (bestWholeShulker != null && bestWholeShulker.slot().id == source.id) {
                        moveWholeCleanShulkerIfUseful(handler, source, demands, targetTemplates);
                        searchWholeShulker = true;
                    }
                    continue;
                }
                if (!stacksMatch(stack, demand.template())) {
                    continue;
                }

                searchWholeShulker = true;
                int amount = Math.min(stack.getCount(), demand.remaining());
                int moved = moveFromContainerToPlayer(handler, source.id, demand.template(), amount);
                demand.decrease(moved);
            }
        }
    }

    private void collectToShulkersOrPlayer(ScreenHandler handler, List<Demand> demands, List<ItemStack> targetTemplates) {
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }

        // 只在本次调用内复用；搬运可能改变需求、盒内物品和背包容量，之后必须重新评选。
        WholeShulkerCandidate bestWholeShulker = null;
        boolean searchWholeShulker = true;
        for (Demand demand : demands) {
            if (demand.remaining() <= 0) {
                continue;
            }

            for (Slot source : getContainerSlots(handler)) {
                if (demand.remaining() <= 0) {
                    break;
                }
                if (!source.hasStack()) {
                    continue;
                }

                ItemStack stack = source.getStack();
                if (isShulkerBox(stack)) {
                    if (searchWholeShulker) {
                        bestWholeShulker = findBestWholeShulkerCandidate(handler, demands, targetTemplates);
                        searchWholeShulker = false;
                    }
                    if (bestWholeShulker != null && bestWholeShulker.slot().id == source.id) {
                        moveWholeCleanShulkerIfUseful(handler, source, demands, targetTemplates);
                        searchWholeShulker = true;
                    }
                    continue;
                }
                if (!stacksMatch(stack, demand.template())) {
                    continue;
                }

                searchWholeShulker = true;
                int amount = Math.min(stack.getCount(), demand.remaining());
                int moved = moveSlotAmountIntoShulkers(handler, source.id, demand.template(), amount, targetTemplates);
                demand.decrease(moved);

                int remainingAmount = amount - moved;
                if (remainingAmount > 0) {
                    moved = moveFromContainerToPlayer(handler, source.id, demand.template(), remainingAmount);
                    demand.decrease(moved);
                }
            }
        }
    }

    private int moveSlotAmountIntoShulkers(ScreenHandler handler,
                                           int sourceSlotId,
                                           ItemStack template,
                                           int amount,
                                           List<ItemStack> targetTemplates) {
        Slot source = handler.getSlot(sourceSlotId);
        if (amount <= 0 || !handler.getCursorStack().isEmpty()
                || !source.hasStack() || isShulkerBox(source.getStack()) || !stacksMatch(source.getStack(), template)) {
            return 0;
        }

        int amountToPack = Math.min(amount, source.getStack().getCount());
        if (findDestinationShulkerSlot(handler, source.getStack(), targetTemplates, getTemporarySourcePlayerIndex()) == null) {
            return 0;
        }
        if (amountToPack < source.getStack().getCount() && hasQuickShulkerBundlingConflict(template)) {
            return moveQuickShulkerConflictAmount(
                    handler,
                    sourceSlotId,
                    template,
                    amountToPack,
                    targetTemplates,
                    true
            );
        }

        clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        while (!handler.getCursorStack().isEmpty() && handler.getCursorStack().getCount() > amountToPack) {
            int before = handler.getCursorStack().getCount();
            // 超出需求的部分立即放回原容器槽，不借用箱子槽位临时存无关物品。
            clickSlot(handler, sourceSlotId, 1, SlotActionType.PICKUP);
            int after = handler.getCursorStack().isEmpty() ? 0 : handler.getCursorStack().getCount();
            if (after >= before) {
                clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
                return 0;
            }
        }

        int moved = packCursorIntoShulkers(handler, targetTemplates);

        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        }

        return moved;
    }

    private int moveFromContainerToPlayer(ScreenHandler handler, int sourceSlotId, ItemStack template, int amount) {
        if (amount <= 0 || !handler.getCursorStack().isEmpty()) {
            return 0;
        }

        Slot source = handler.getSlot(sourceSlotId);
        if (!source.hasStack() || !stacksMatch(source.getStack(), template)) {
            return 0;
        }

        ItemStack sourceTemplate = source.getStack().copy();
        sourceTemplate.setCount(1);
        int sourceCount = source.getStack().getCount();
        int moveAmount = getPlayerCapacity(handler, sourceTemplate, Math.min(amount, sourceCount));
        if (moveAmount <= 0) {
            return 0;
        }

        if (moveAmount == sourceCount) {
            clickSlot(handler, sourceSlotId, 0, SlotActionType.QUICK_MOVE);
            int remaining = source.hasStack() && stacksExactlyMatch(source.getStack(), sourceTemplate)
                    ? source.getStack().getCount() : 0;
            return Math.max(0, sourceCount - remaining);
        }
        if (hasQuickShulkerBundlingConflict(sourceTemplate)) {
            return moveQuickShulkerConflictAmount(
                    handler,
                    sourceSlotId,
                    sourceTemplate,
                    moveAmount,
                    List.of(),
                    false
            );
        }

        clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        int deposited = depositCursorToPlayer(handler, sourceTemplate, moveAmount);

        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        }

        return deposited;
    }

    private int moveQuickShulkerConflictAmount(ScreenHandler handler,
                                                int sourceSlotId,
                                                ItemStack template,
                                                int amount,
                                                List<ItemStack> targetTemplates,
                                                boolean intoShulkers) {
        if (!handler.getCursorStack().isEmpty()) {
            return 0;
        }

        Slot source = handler.getSlot(sourceSlotId);
        if (!source.hasStack() || !stacksMatch(source.getStack(), template)) {
            return 0;
        }

        int targetAmount = Math.min(amount, source.getStack().getCount());
        if (targetAmount <= 0 || targetAmount >= source.getStack().getCount()) {
            return 0;
        }

        Slot buffer = findEmptyTemporarySplitSlot(handler, sourceSlotId, template);
        if (buffer == null) {
            // 没有安全缓冲槽时跳过半组末影箱，不能退回多余数量时触发 Quick Shulker 装入自身。
            return 0;
        }

        int moved = 0;
        while (moved < targetAmount && source.hasStack() && handler.getCursorStack().isEmpty()) {
            int remaining = targetAmount - moved;
            if (source.getStack().getCount() <= remaining) {
                clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
            } else {
                // 空光标右键数量大于 1 的末影箱堆只会原版对半拆分，不会触发 Quick Shulker。
                clickSlot(handler, sourceSlotId, 1, SlotActionType.PICKUP);
                if (handler.getCursorStack().isEmpty() || !stacksMatch(handler.getCursorStack(), template)) {
                    break;
                }
                if (handler.getCursorStack().getCount() > remaining) {
                    clickSlot(handler, buffer.id, 0, SlotActionType.PICKUP);
                    if (!handler.getCursorStack().isEmpty()) {
                        break;
                    }
                    continue;
                }
            }

            if (handler.getCursorStack().isEmpty() || !stacksMatch(handler.getCursorStack(), template)) {
                break;
            }

            int before = handler.getCursorStack().getCount();
            int deposited = intoShulkers
                    ? packCursorIntoShulkers(handler, targetTemplates)
                    : depositCursorToPlayerWithLeftClicks(handler, template, buffer.id);
            moved += Math.min(before, Math.max(0, deposited));

            if (!handler.getCursorStack().isEmpty()) {
                clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
                break;
            }
        }

        restoreTemporarySplitBuffer(handler, sourceSlotId, buffer.id);
        return moved;
    }

    private int depositCursorToPlayerWithLeftClicks(ScreenHandler handler,
                                                     ItemStack template,
                                                     int excludedSlotId) {
        int deposited = 0;
        while (!handler.getCursorStack().isEmpty()) {
            Slot target = findPlayerDepositSlot(handler, template, excludedSlotId);
            if (target == null) {
                break;
            }

            int before = handler.getCursorStack().getCount();
            clickSlot(handler, target.id, 0, SlotActionType.PICKUP);
            int after = handler.getCursorStack().isEmpty() ? 0 : handler.getCursorStack().getCount();
            if (after >= before) {
                break;
            }
            deposited += before - after;
        }
        return deposited;
    }

    private void restoreTemporarySplitBuffer(ScreenHandler handler, int sourceSlotId, int bufferSlotId) {
        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        }
        Slot buffer = handler.getSlot(bufferSlotId);
        if (!handler.getCursorStack().isEmpty() || !buffer.hasStack()) {
            return;
        }

        clickSlot(handler, bufferSlotId, 0, SlotActionType.PICKUP);
        clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(handler, bufferSlotId, 0, SlotActionType.PICKUP);
        }
    }

    private int depositCursorToPlayer(ScreenHandler handler, ItemStack template, int amount) {
        int deposited = 0;

        while (deposited < amount && !handler.getCursorStack().isEmpty()) {
            Slot target = findPlayerDepositSlot(handler, template);
            if (target == null) {
                break;
            }

            int before = handler.getCursorStack().getCount();
            clickSlot(handler, target.id, 1, SlotActionType.PICKUP);
            int after = handler.getCursorStack().isEmpty() ? 0 : handler.getCursorStack().getCount();
            if (after >= before) {
                break;
            }
            deposited += before - after;
        }

        return deposited;
    }

    private void moveWholeCleanShulkerIfUseful(ScreenHandler handler,
                                               Slot source,
                                               List<Demand> demands,
                                               List<ItemStack> targetTemplates) {
        ItemStack shulker = source.getStack();
        List<StoredCount> contents = getStoredTargetCounts(shulker, demands);
        if (contents.isEmpty() || !containsOnlyTargetMaterials(shulker, targetTemplates)) {
            return;
        }

        for (StoredCount content : contents) {
            if (content.count() > content.demand().remaining()) {
                return;
            }
        }
        if (!hasPlayerCapacity(handler, shulker, shulker.getCount())) {
            return;
        }

        clickSlot(handler, source.id, 0, SlotActionType.QUICK_MOVE);
        if (source.hasStack()) {
            return;
        }
        for (StoredCount content : contents) {
            content.demand().decrease(content.count());
        }
    }

    private void packPlayerTargetMaterials(MinecraftClient client, ScreenHandler handler) {
        if (client.player == null || !shouldUseQuickShulker()) {
            return;
        }
        MaterialPlan packingPlan = buildMaterialPlan(client.player);
        if (packPlayerTargetMaterialsIntoShulkers(handler, packingPlan.packDemands(), packingPlan.targetTemplates()) > 0) {
            activeTask.processedSourceSlots.clear();
        }
    }

    private int packPlayerTargetMaterialsIntoShulkers(ScreenHandler handler,
                                                       List<PackDemand> packDemands,
                                                       List<ItemStack> targetTemplates) {
        if (!handler.getCursorStack().isEmpty()) {
            return 0;
        }

        int packed = 0;
        for (PackDemand demand : packDemands) {
            if (demand.remaining() <= 0) {
                continue;
            }

            for (Slot source : getPlayerStorageSlots(handler)) {
                if (demand.remaining() <= 0) {
                    break;
                }
                if (!source.hasStack() || isShulkerBox(source.getStack()) || !stacksMatch(source.getStack(), demand.template())) {
                    continue;
                }

                int amount = Math.min(source.getStack().getCount(), demand.remaining());
                int moved = moveSlotAmountIntoShulkers(handler, source.id, demand.template(), amount, targetTemplates);
                demand.decrease(moved);
                packed += moved;
            }
        }
        return packed;
    }

    private int packCursorIntoShulkers(ScreenHandler handler, List<ItemStack> targetTemplates) {
        int moved = 0;

        while (!handler.getCursorStack().isEmpty()) {
            Slot shulkerSlot = findDestinationShulkerSlot(handler, handler.getCursorStack(), targetTemplates, getTemporarySourcePlayerIndex());
            if (shulkerSlot == null) {
                break;
            }

            ItemStack beforeStack = handler.getCursorStack().copy();
            int before = handler.getCursorStack().getCount();
            // 右键潜影盒槽位，让 Quick Shulker 的服务端逻辑负责真实写入。
            clickSlot(handler, shulkerSlot.id, 1, SlotActionType.PICKUP);
            if (!handler.getCursorStack().isEmpty() && !stacksExactlyMatch(handler.getCursorStack(), beforeStack)) {
                clickSlot(handler, shulkerSlot.id, 0, SlotActionType.PICKUP);
                break;
            }
            int after = handler.getCursorStack().isEmpty() ? 0 : handler.getCursorStack().getCount();
            if (after < before) {
                moved += before - after;
                continue;
            }
            break;
        }

        return moved;
    }

    private boolean shouldUseQuickShulker() {
        return QuickCraftConfigs.isAutoCollectMaterialsWithQuickShulkerEnabled()
                && QuickShulkerConnection.canBundle();
    }

    private InventoryCounts countPlayerInventory(PlayerInventory inventory) {
        Object2IntOpenHashMap<Item> available = new Object2IntOpenHashMap<>();
        Object2IntOpenHashMap<Item> boxed = new Object2IntOpenHashMap<>();
        // 与材料表一致只按物品类型统计；快照不跨点击或收集轮次保存。
        for (int i = 0; i < 36; i++) {
            // 临时来源盒仍属于原容器，不能计入玩家库存或用于满足装盒上限。
            if (i == getTemporarySourcePlayerIndex()) {
                continue;
            }
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (isShulkerBox(stack)) {
                List<ItemStack> contents = getStoredStacks(stack);
                if (!contents.isEmpty()) {
                    for (ItemStack stored : contents) {
                        available.addTo(stored.getItem(), stored.getCount());
                        boxed.addTo(stored.getItem(), stored.getCount());
                    }
                    continue;
                }
            }
            available.addTo(stack.getItem(), stack.getCount());
        }
        return new InventoryCounts(available, boxed);
    }

    private Slot findPlayerDepositSlot(ScreenHandler handler, ItemStack template) {
        return findPlayerDepositSlot(handler, template, -1);
    }

    private Slot findPlayerDepositSlot(ScreenHandler handler, ItemStack template, int excludedSlotId) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.id == excludedSlotId || !slot.hasStack() || !slot.canInsert(template)) {
                continue;
            }
            if (stacksExactlyMatch(slot.getStack(), template) && slot.getStack().getCount() < slot.getStack().getMaxCount()) {
                return slot;
            }
        }

        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.id != excludedSlotId && !slot.hasStack() && slot.canInsert(template)) {
                return slot;
            }
        }

        return null;
    }

    private Slot findEmptyTemporarySplitSlot(ScreenHandler handler, int sourceSlotId, ItemStack template) {
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.id != sourceSlotId && !slot.hasStack() && slot.canInsert(template)) {
                return slot;
            }
        }
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.id != sourceSlotId && !slot.hasStack() && slot.canInsert(template)) {
                return slot;
            }
        }
        return null;
    }

    private boolean hasQuickShulkerBundlingConflict(ItemStack stack) {
        return FabricLoader.getInstance().isModLoaded("quickshulker") && stack.isOf(Items.ENDER_CHEST);
    }

    private void clickSlot(ScreenHandler handler, int slotId, int button, SlotActionType actionType) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                slotId,
                button,
                actionType,
                client.player
        );
    }

    private void closeCurrentScreen(MinecraftClient client) {
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
        if (client.currentScreen instanceof HandledScreen<?>) {
            client.setScreen(null);
        }
    }

    private enum CollectionStage {
        WAIT_TARGET,
        COLLECT_TARGET,
        WAIT_SHULKER,
        EXTRACT_SHULKER,
        REOPEN_DELAY,
        WAIT_REOPEN,
        RETURN_SHULKER
    }

    private static final class CollectionTask {
        private final ClientWorld world;
        private final PlayerInventory inventory;
        private final BlockHitResult hitResult;
        private final BlockPos target;
        private final Set<Integer> processedSourceSlots = new HashSet<>();
        private CollectionStage stage;
        private MaterialPlan plan;
        private boolean stopRequested;
        private boolean longPressActivated;
        private int holdTicks;
        private int ticks;
        private int returnTicks;
        private int previousSyncId = -1;
        private int sourceContainerSlotId = -1;
        private int sourcePlayerIndex = -1;
        private ItemStack sourceStack = ItemStack.EMPTY;

        private CollectionTask(MinecraftClient client, BlockHitResult hitResult, BlockPos target, CollectionStage stage) {
            this.world = client.world;
            this.inventory = client.player.getInventory();
            this.hitResult = hitResult;
            this.target = target;
            this.stage = stage;
        }
    }

    public record MaterialRequest(ItemStack stack, int count) {
    }

    private record InventoryCounts(Object2IntOpenHashMap<Item> available, Object2IntOpenHashMap<Item> boxed) {
    }

    private record MaterialPlan(List<Demand> demands, List<ItemStack> targetTemplates, List<PackDemand> packDemands) {
    }

    static final class Demand {
        private final ItemStack template;
        private int missing;
        private int remaining;
        private int collected;

        Demand(ItemStack template, int missing) {
            this.template = template;
            this.missing = missing;
        }

        ItemStack template() {
            return template;
        }

        int missing() {
            return missing;
        }

        int remaining() {
            return remaining;
        }

        int collected() {
            return collected;
        }

        void addMissing(int count) {
            this.missing += count;
        }

        void setRemaining(int remaining) {
            this.remaining = remaining;
        }

        void decrease(int count) {
            int moved = Math.min(this.remaining, Math.max(0, count));
            this.remaining -= moved;
            this.collected += moved;
        }
    }

    static final class StoredCount {
        private final Demand demand;
        private int count;

        StoredCount(Demand demand, int count) {
            this.demand = demand;
            this.count = count;
        }

        Demand demand() {
            return demand;
        }

        int count() {
            return count;
        }

        void add(int count) {
            this.count += count;
        }
    }

    private static final class PackDemand {
        private final ItemStack template;
        private int remaining;

        private PackDemand(ItemStack template, int remaining) {
            this.template = template;
            this.remaining = remaining;
        }

        private ItemStack template() {
            return template;
        }

        private int remaining() {
            return remaining;
        }

        private void decrease(int count) {
            this.remaining = Math.max(0, this.remaining - count);
        }
    }
}
