package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作台持续合成的直接潜影盒补料器。
 * 通过 Quick Shulker 的“光标持盒右键合成格”路径直接铺满配方，不打开潜影盒界面。
 */
public final class QuickCraftWorkbenchShulker {
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft/WorkbenchShulker");
    private static final int GRID_START = 1;
    private static final int GRID_END = 9;
    private static final int MAX_UNBUNDLE_CLICKS_PER_SOURCE = GRID_END - GRID_START + 1;
    static final int MAX_SOURCE_SHULKERS = PlayerInventory.MAIN_SIZE;
    private static final Identifier QUICK_SHULKER_OPEN_PACKET =
            Identifier.of("quickshulker", "open_shulker_packet");

    private static Task task;
    private static long nextTaskId;
    private static TaskResult pendingResult = TaskResult.NONE;
    private static Text pendingMessage;
    private static TaskOwner pendingOwner;
    private static int shulkerCraftActionCooldown;
    private static long debugSessionStartedAtNanos;
    private static int debugSessionTaskCount;
    private static int debugSessionSourceBatches;
    private static boolean debugSessionUltraFast;

    private QuickCraftWorkbenchShulker() {
    }

    public static boolean isAvailable() {
        if (!QuickCraftConfigs.isWorkbenchQuickCraftWithQuickShulkerEnabled()
                || !FabricLoader.getInstance().isModLoaded("quickshulker")) {
            return false;
        }
        try {
            // 该包只用于确认服务端装有 Quick Shulker；实际取料走右键空合成格的原版点击链。
            return ClientPlayNetworking.canSend(QUICK_SHULKER_OPEN_PACKET);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static boolean isConfigured() {
        return QuickCraftConfigs.isWorkbenchQuickCraftWithQuickShulkerEnabled();
    }

    static void beginDebugSession(boolean ultraFast) {
        debugSessionStartedAtNanos = System.nanoTime();
        debugSessionTaskCount = 0;
        debugSessionSourceBatches = 0;
        debugSessionUltraFast = ultraFast;
    }

    static void clearDebugSession() {
        debugSessionStartedAtNanos = 0L;
        debugSessionTaskCount = 0;
        debugSessionSourceBatches = 0;
        debugSessionUltraFast = false;
    }

    static long debugElapsedMillis() {
        return debugSessionStartedAtNanos == 0L
                ? 0L
                : (System.nanoTime() - debugSessionStartedAtNanos) / 1_000_000L;
    }

    static int debugSessionTaskCount() {
        return debugSessionTaskCount;
    }

    static int debugSessionSourceBatches() {
        return debugSessionSourceBatches;
    }

    public static boolean isBusy() {
        return isTaskOwnedBy(TaskOwner.LEGACY);
    }

    public static boolean isShulkerCraftBusy() {
        return isTaskOwnedBy(TaskOwner.SHULKER_CRAFT);
    }

    public static boolean shouldBlockWorkbenchInput() {
        return task != null;
    }

    public static boolean handleEscape(MinecraftClient client) {
        if (!shouldBlockWorkbenchInput()) {
            return false;
        }
        task.stopRequested = true;
        task.exitAfterStop = true;
        LOGGER.debug("收到 Esc，等待当前直接取料动作结束：任务 #{}", task.id);
        return true;
    }

    public static void requestStopAfterCurrentAction() {
        if (isTaskOwnedBy(TaskOwner.LEGACY)) {
            task.stopRequested = true;
        }
    }

    public static void requestShulkerCraftStopAfterCurrentAction() {
        if (isTaskOwnedBy(TaskOwner.SHULKER_CRAFT)) {
            task.stopRequested = true;
        }
    }

    public static void reset() {
        resetOwner(TaskOwner.LEGACY);
    }

    public static void resetShulkerCraft() {
        resetOwner(TaskOwner.SHULKER_CRAFT);
    }

    public static TaskResult consumeResult() {
        return consumeResult(TaskOwner.LEGACY);
    }

    public static TaskResult consumeShulkerCraftResult() {
        return consumeResult(TaskOwner.SHULKER_CRAFT);
    }

    public static Text consumeMessage() {
        return consumeMessage(TaskOwner.LEGACY);
    }

    public static Text consumeShulkerCraftMessage() {
        return consumeMessage(TaskOwner.SHULKER_CRAFT);
    }

    public static boolean recoverShulkerCraftCursor(CraftingScreenHandler handler) {
        return handler != null
                && isSingleShulker(handler.getCursorStack())
                && recoverUnexpectedShulker(handler, -1);
    }

    public static RefillStart beginRefill(CraftingScreenHandler handler, List<ItemStack> pattern) {
        return beginRefill(handler, pattern, TaskOwner.LEGACY);
    }

    public static RefillStart beginShulkerCraftRefill(CraftingScreenHandler handler, List<ItemStack> pattern) {
        return beginRefill(handler, pattern, TaskOwner.SHULKER_CRAFT);
    }

    private static RefillStart beginRefill(CraftingScreenHandler handler,
                                           List<ItemStack> pattern,
                                           TaskOwner owner) {
        if (task != null || handler == null || pattern == null || pattern.isEmpty() || !isAvailable()) {
            LOGGER.debug("跳过直接潜影盒补料：owner={}，busyOwner={}，handler={}，pattern={}，available={}",
                    owner, task == null ? "NONE" : task.owner, className(handler),
                    pattern == null ? -1 : pattern.size(), isAvailable());
            return RefillStart.NOT_STARTED;
        }
        if (!handler.getCursorStack().isEmpty()) {
            if (owner == TaskOwner.SHULKER_CRAFT
                    && isSingleShulker(handler.getCursorStack())
                    && recoverUnexpectedShulker(handler, -1)) {
                return RefillStart.RECOVERED_DESYNC;
            }
            LOGGER.warn("无法启动直接潜影盒补料：光标上已有物品，revision={}，光标={}，合成格={}",
                    handler.getRevision(), describeStack(handler.getCursorStack()), describeGrid(handler));
            return RefillStart.NOT_STARTED;
        }
        List<ItemStack> normalizedPattern = normalizePattern(pattern);
        if (!isGridCompatible(handler, normalizedPattern)) {
            int unexpectedShulkerSlot = findUnexpectedGridShulker(handler, normalizedPattern);
            LOGGER.warn("无法启动直接潜影盒补料：合成格与锁定配方不一致，revision={}，异常盒槽={}，光标={}，期望={}，实际={}",
                    handler.getRevision(), unexpectedShulkerSlot, describeStack(handler.getCursorStack()),
                    describePattern(normalizedPattern), describeGrid(handler));
            if (owner == TaskOwner.SHULKER_CRAFT
                    && unexpectedShulkerSlot != -1
                    && recoverUnexpectedShulker(handler, unexpectedShulkerSlot)) {
                return RefillStart.RECOVERED_DESYNC;
            }
            return RefillStart.GRID_MISMATCH;
        }
        task = new Task(normalizedPattern, handler.syncId, owner);
        task.nextSource = findSourceTimed(handler, normalizedPattern, Set.of());
        if (task.nextSource == null) {
            LOGGER.debug("无法启动直接潜影盒补料：完整玩家物品栏中没有可直填的单材料盒");
            task = null;
            return RefillStart.NO_MATERIALS;
        }
        if (owner == TaskOwner.SHULKER_CRAFT) {
            debugSessionTaskCount++;
        }

        LOGGER.debug("开始潜影盒直填任务 #{}：会话t+{} ms，owner={}，实验极速={}，扫描 {} 格玩家物品栏，目标合成格={}，操作间隔={} Tick",
                task.id, debugElapsedMillis(), owner,
                owner == TaskOwner.SHULKER_CRAFT && debugSessionUltraFast,
                MAX_SOURCE_SHULKERS, describePattern(normalizedPattern),
                QuickCraftConfigs.getQuickShulkerActionIntervalTicks());
        return RefillStart.STARTED;
    }

    public static void tick(MinecraftClient client) {
        tick(client, TaskOwner.LEGACY);
    }

    public static void tickShulkerCraft(MinecraftClient client) {
        tick(client, TaskOwner.SHULKER_CRAFT);
    }

    private static void tick(MinecraftClient client, TaskOwner owner) {
        if (!isTaskOwnedBy(owner)) {
            return;
        }
        if (client == null || client.player == null || client.interactionManager == null
                || client.world == null
                || !(client.player.currentScreenHandler instanceof CraftingScreenHandler handler)
                || handler.syncId != task.workbenchSyncId) {
            finish(TaskResult.STOPPED, Text.translatable("quickcraft.message.crafting.shulker_screen_invalid"));
            return;
        }
        if (task.stopRequested) {
            finishAfterReturningHeldBox(client, handler);
            return;
        }

        task.tickCount++;
        if (owner == TaskOwner.SHULKER_CRAFT) {
            if (isShulkerCraftActionCoolingDown()) {
                task.cooldownWaitTicks++;
                return;
            }
        } else if (task.actionCooldown > 0) {
            task.actionCooldown--;
            task.cooldownWaitTicks++;
            return;
        }
        int maxSourceBatches = owner == TaskOwner.SHULKER_CRAFT
                ? sourceBatchesPerTick(QuickCraftConfigs.getQuickShulkerActionIntervalTicks(),
                debugSessionUltraFast)
                : 1;
        long taskId = task.id;
        int processedBatches = 0;
        for (int batch = 0; batch < maxSourceBatches && task != null; batch++) {
            extractDirectly(client, handler);
            processedBatches++;
            if (task == null || isActionCoolingDown(owner)) {
                break;
            }
        }
        if (processedBatches > 1) {
            LOGGER.debug("快速来源批处理 #{}：会话t+{} ms，同Tick处理={} 批，上限={} 批",
                    taskId, debugElapsedMillis(), processedBatches, maxSourceBatches);
        }
    }

    private static void extractDirectly(MinecraftClient client, CraftingScreenHandler handler) {
        int revisionBefore = handler.getRevision();
        task.sourceBatches++;
        if (task.owner == TaskOwner.SHULKER_CRAFT) {
            debugSessionSourceBatches++;
        }
        SourceShulker source = task.nextSource;
        task.nextSource = null;
        if (source == null) {
            source = findSourceTimed(handler, task.pattern, task.exhaustedSourcePlayerIndices);
        }
        if (source == null) {
            finish(task.movedItems > 0 ? TaskResult.REFILLED : TaskResult.STOPPED,
                    task.movedItems > 0 ? null : Text.translatable("quickcraft.message.crafting.no_ingredients"));
            return;
        }

        Slot sourceSlot = findPlayerSlot(handler, source.playerIndex());
        if (sourceSlot == null || !sourceSlot.hasStack()) {
            task.exhaustedSourcePlayerIndices.add(source.playerIndex());
            return;
        }

        long pickupStartedAtNanos = System.nanoTime();
        client.interactionManager.clickSlot(handler.syncId, sourceSlot.id, 0,
                SlotActionType.PICKUP, client.player);
        task.pickupBoxNanos += System.nanoTime() - pickupStartedAtNanos;
        task.currentSourcePlayerIndex = source.playerIndex();
        ItemStack cursorBox = handler.getCursorStack();
        if (!isSingleShulker(cursorBox) || !isHomogeneousShulker(cursorBox)) {
            if (!returnHeldBox(client, handler, sourceSlot)) {
                finish(TaskResult.STOPPED, Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
                return;
            }
            task.exhaustedSourcePlayerIndices.add(source.playerIndex());
            LOGGER.warn("跳过非单材料潜影盒：玩家槽位={}，光标={}", source.playerIndex(), cursorBox.getName().getString());
            return;
        }

        ItemStack boxMaterial = getFirstStoredStack(cursorBox);
        if (findFillableGridSlot(handler, task.pattern, boxMaterial) == -1) {
            if (!returnHeldBox(client, handler, sourceSlot)) {
                finish(TaskResult.STOPPED, Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
                return;
            }
            task.exhaustedSourcePlayerIndices.add(source.playerIndex());
            return;
        }

        int movedItems = 0;
        int clicks = 0;
        boolean extractionUnavailable = false;
        while (clicks < MAX_UNBUNDLE_CLICKS_PER_SOURCE) {
            ItemStack currentCursorBox = handler.getCursorStack();
            if (!isSingleShulker(currentCursorBox) || getFirstStoredStack(currentCursorBox).isEmpty()) {
                LOGGER.trace("当前盒材料已取完：玩家槽={}，本盒直填={} 个，材料={}",
                        source.playerIndex(), movedItems, boxMaterial.getName().getString());
                break;
            }
            int targetSlotId = findFillableGridSlot(handler, task.pattern, boxMaterial);
            if (targetSlotId == -1) {
                break;
            }

            Slot target = handler.getSlot(targetSlotId);
            int before = target.hasStack() ? target.getStack().getCount() : 0;
            long unbundleStartedAtNanos = System.nanoTime();
            client.interactionManager.clickSlot(handler.syncId, targetSlotId, 1,
                    SlotActionType.PICKUP, client.player);
            task.unbundleNanos += System.nanoTime() - unbundleStartedAtNanos;
            ItemStack after = target.getStack();
            int afterCount = !after.isEmpty() && ItemStack.areItemsAndComponentsEqual(after, boxMaterial)
                    ? after.getCount()
                    : 0;
            if (afterCount <= before) {
                if (handler.getCursorStack().isEmpty() && isSingleShulker(after)) {
                    client.interactionManager.clickSlot(handler.syncId, targetSlotId, 0,
                            SlotActionType.PICKUP, client.player);
                }
                extractionUnavailable = true;
                LOGGER.warn("右键合成格未直填材料：盒玩家槽={}，合成槽={}，材料={}，填充前={}，填充后={}",
                        source.playerIndex(), targetSlotId, boxMaterial.getName().getString(), before, afterCount);
                break;
            }
            int moved = afterCount - before;
            clicks++;
            movedItems += moved;
            task.movedItems += moved;
            task.unbundleClicks++;
            LOGGER.trace("潜影盒直填：材料={}，盒玩家槽={}，合成槽={}，移动={}，槽内={}/{}",
                    after.getName().getString(), source.playerIndex(), targetSlotId, moved,
                    afterCount, target.getMaxItemCount(after));
        }

        if (!returnHeldBox(client, handler, sourceSlot)) {
            finish(TaskResult.STOPPED, Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
            return;
        }
        if (extractionUnavailable) {
            finish(TaskResult.STOPPED, Text.translatable("quickcraft.message.crafting.shulker_unavailable"));
            return;
        }
        task.exhaustedSourcePlayerIndices.add(source.playerIndex());
        setActionCooldown();
        LOGGER.debug("潜影盒直填任务 #{}：会话t+{} ms，来源盒玩家槽={}，材料={}，本次直填={} 个/{} 次右键，"
                        + "累计={} 个，revision={}->{}, 下一来源最早 {} Tick 后处理",
                task.id, debugElapsedMillis(), source.playerIndex(), boxMaterial.getName().getString(), movedItems, clicks,
                task.movedItems, revisionBefore, handler.getRevision(), getCurrentActionCooldown(task.owner));
        if (!hasAnyFillableGridSlot(handler, task.pattern)) {
            finish(TaskResult.REFILLED, null);
            return;
        }
        task.nextSource = findSourceTimed(handler, task.pattern, task.exhaustedSourcePlayerIndices);
        if (task.nextSource == null) {
            finish(TaskResult.REFILLED, null);
        }
    }

    private static void finishAfterReturningHeldBox(MinecraftClient client, CraftingScreenHandler handler) {
        if (!handler.getCursorStack().isEmpty()) {
            Slot source = findPlayerSlot(handler, task.currentSourcePlayerIndex);
            if (source == null || !source.getStack().isEmpty()) {
                source = getFirstEmptyPlayerSlot(handler, -1);
            }
            if (source != null) {
                client.interactionManager.clickSlot(handler.syncId, source.id, 0,
                        SlotActionType.PICKUP, client.player);
            }
        }
        finish(TaskResult.STOPPED, handler.getCursorStack().isEmpty()
                ? null
                : Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
    }

    private static boolean returnHeldBox(MinecraftClient client, CraftingScreenHandler handler, Slot sourceSlot) {
        long startedAtNanos = System.nanoTime();
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }
        if (!sourceSlot.hasStack() && sourceSlot.canInsert(handler.getCursorStack())) {
            client.interactionManager.clickSlot(handler.syncId, sourceSlot.id, 0,
                    SlotActionType.PICKUP, client.player);
        }
        boolean returned = handler.getCursorStack().isEmpty();
        if (task != null) {
            task.returnBoxNanos += System.nanoTime() - startedAtNanos;
        }
        return returned;
    }

    private static void finish(TaskResult result, Text message) {
        boolean exitAfterStop = task != null && task.exitAfterStop;
        TaskOwner owner = task == null ? null : task.owner;
        if (task != null) {
            long elapsedMillis = (System.nanoTime() - task.startedAtNanos) / 1_000_000L;
            LOGGER.debug("潜影盒直填任务结束 #{}：会话t+{} ms，结果={}，移动={} 个/{} 次右键，处理批次={}，总耗时={} ms，"
                            + "任务Tick={}，间隔等待={} Tick，扫描={} us，拿盒={} us，取料={} us，还盒={} us，消息={}",
                    task.id, debugElapsedMillis(), result, task.movedItems, task.unbundleClicks,
                    task.sourceBatches, elapsedMillis,
                    task.tickCount, task.cooldownWaitTicks, task.sourceScanNanos / 1_000L,
                    task.pickupBoxNanos / 1_000L, task.unbundleNanos / 1_000L,
                    task.returnBoxNanos / 1_000L, message == null ? "无" : message.getString());
        }
        task = null;
        pendingResult = result;
        pendingMessage = message;
        pendingOwner = owner;
        if (exitAfterStop) {
            closeWorkbenchScreenIfSafe();
        }
    }

    private static void closeWorkbenchScreenIfSafe() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.currentScreenHandler == null
                || !client.player.currentScreenHandler.getCursorStack().isEmpty()) {
            return;
        }
        if (client.player.currentScreenHandler instanceof CraftingScreenHandler handler) {
            for (int slotId = GRID_START; slotId <= GRID_END; slotId++) {
                if (handler.getSlot(slotId).hasStack()) {
                    return;
                }
            }
        }
        client.player.closeHandledScreen();
        client.setScreen(null);
    }

    private static Slot findPlayerSlot(ScreenHandler handler, int playerIndex) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.getIndex() == playerIndex) {
                return slot;
            }
        }
        return null;
    }

    private static Slot getFirstEmptyPlayerSlot(ScreenHandler handler, int excludedIndex) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.getIndex() != excludedIndex && !slot.hasStack()) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isSingleShulker(ItemStack stack) {
        return stack.getCount() == 1 && isShulkerBox(stack);
    }

    private static boolean isHomogeneousShulker(ItemStack shulker) {
        ItemStack first = getFirstStoredStack(shulker);
        if (first.isEmpty()) {
            return false;
        }
        for (ItemStack stored : getStoredStacks(shulker)) {
            if (!ItemStack.areItemsAndComponentsEqual(first, stored)) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack getFirstStoredStack(ItemStack shulker) {
        ContainerComponent container = shulker.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        for (ItemStack stored : container.iterateNonEmpty()) {
            return stored;
        }
        return ItemStack.EMPTY;
    }

    private static void setActionCooldown() {
        if (task != null) {
            if (task.owner == TaskOwner.SHULKER_CRAFT) {
                markShulkerCraftAction();
            } else {
                task.actionCooldown = Math.max(0,
                        QuickCraftConfigs.getQuickShulkerActionIntervalTicks() - 1);
            }
        }
    }

    public static void advanceShulkerCraftActionCooldown() {
        if (shulkerCraftActionCooldown > 0) {
            shulkerCraftActionCooldown--;
        }
    }

    public static boolean isShulkerCraftActionCoolingDown() {
        return shulkerCraftActionCooldown > 0;
    }

    public static void markShulkerCraftAction() {
        shulkerCraftActionCooldown = shulkerCraftCooldownAfterAction(
                QuickCraftConfigs.getQuickShulkerActionIntervalTicks());
    }

    static int shulkerCraftCooldownAfterAction(int configuredIntervalTicks) {
        return Math.max(0, configuredIntervalTicks);
    }

    private static boolean isActionCoolingDown(TaskOwner owner) {
        return owner == TaskOwner.SHULKER_CRAFT
                ? isShulkerCraftActionCoolingDown()
                : task != null && task.actionCooldown > 0;
    }

    private static int getCurrentActionCooldown(TaskOwner owner) {
        return owner == TaskOwner.SHULKER_CRAFT
                ? shulkerCraftActionCooldown
                : task == null ? 0 : task.actionCooldown;
    }

    private static List<Slot> getPlayerStorageSlots(ScreenHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory
                    && slot.getIndex() >= 0
                    && slot.getIndex() < PlayerInventory.MAIN_SIZE
                    && slot.isEnabled()) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator.comparingInt(Slot::getIndex).thenComparingInt(slot -> slot.id));
        return slots;
    }

    static int sourceScanCount(int carriedShulkers) {
        return Math.min(Math.max(0, carriedShulkers), MAX_SOURCE_SHULKERS);
    }

    static int sourceBatchesPerTick(int actionIntervalTicks, boolean ultraFastEnabled) {
        return ultraFastEnabled && actionIntervalTicks == 0 ? MAX_SOURCE_SHULKERS : 1;
    }

    private static boolean isTaskOwnedBy(TaskOwner owner) {
        return task != null && task.owner == owner;
    }

    private static void resetOwner(TaskOwner owner) {
        if (isTaskOwnedBy(owner)) {
            task = null;
        }
        if (pendingOwner == owner) {
            pendingResult = TaskResult.NONE;
            pendingMessage = null;
            pendingOwner = null;
        }
        if (owner == TaskOwner.SHULKER_CRAFT) {
            shulkerCraftActionCooldown = 0;
        }
    }

    private static TaskResult consumeResult(TaskOwner owner) {
        if (pendingOwner != owner) {
            return TaskResult.NONE;
        }
        TaskResult result = pendingResult;
        pendingResult = TaskResult.NONE;
        if (pendingMessage == null) {
            pendingOwner = null;
        }
        return result;
    }

    private static Text consumeMessage(TaskOwner owner) {
        if (pendingOwner != owner) {
            return null;
        }
        Text message = pendingMessage;
        pendingMessage = null;
        if (pendingResult == TaskResult.NONE) {
            pendingOwner = null;
        }
        return message;
    }

    private static int findUnexpectedGridShulker(CraftingScreenHandler handler,
                                                  List<ItemStack> pattern) {
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack expected = pattern.get(patternIndex);
            int slotId = GRID_START + patternIndex;
            ItemStack actual = handler.getSlot(slotId).getStack();
            if (isSingleShulker(actual)
                    && (expected.isEmpty() || !ItemStack.areItemsAndComponentsEqual(expected, actual))) {
                return slotId;
            }
        }
        return -1;
    }

    private static boolean recoverUnexpectedShulker(CraftingScreenHandler handler, int gridSlotId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        Slot destination = getFirstEmptyPlayerSlot(handler, -1);
        if (destination == null) {
            LOGGER.error("无法恢复错位潜影盒：玩家物品栏没有空槽，revision={}，光标={}，合成格={}",
                    handler.getRevision(), describeStack(handler.getCursorStack()), describeGrid(handler));
            return false;
        }

        if (gridSlotId >= GRID_START && gridSlotId <= GRID_END) {
            client.interactionManager.clickSlot(handler.syncId, gridSlotId, 0,
                    SlotActionType.PICKUP, client.player);
        }
        if (!isSingleShulker(handler.getCursorStack())) {
            if (gridSlotId >= GRID_START && gridSlotId <= GRID_END
                    && !handler.getCursorStack().isEmpty()
                    && !handler.getSlot(gridSlotId).hasStack()) {
                client.interactionManager.clickSlot(handler.syncId, gridSlotId, 0,
                        SlotActionType.PICKUP, client.player);
            }
            LOGGER.error("恢复错位潜影盒失败：拿起后光标不是单个潜影盒，revision={}，光标={}，合成格={}",
                    handler.getRevision(), describeStack(handler.getCursorStack()), describeGrid(handler));
            return false;
        }

        ItemStack recoveredBox = handler.getCursorStack().copy();
        client.interactionManager.clickSlot(handler.syncId, destination.id, 0,
                SlotActionType.PICKUP, client.player);
        boolean recovered = handler.getCursorStack().isEmpty()
                && isSingleShulker(destination.getStack());
        LOGGER.warn("检测到服务端槽位纠正，尝试回收错位潜影盒：成功={}，来源合成槽={}，目标玩家槽={}，盒={}，revision={}，合成格={}",
                recovered, gridSlotId, destination.getIndex(), describeStack(recoveredBox),
                handler.getRevision(), describeGrid(handler));
        if (recovered) {
            markShulkerCraftAction();
        }
        return recovered;
    }

    private static List<ItemStack> normalizePattern(List<ItemStack> pattern) {
        List<ItemStack> normalized = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            ItemStack stack = i < pattern.size() ? pattern.get(i) : ItemStack.EMPTY;
            normalized.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        }
        return normalized;
    }

    private static boolean isGridCompatible(CraftingScreenHandler handler, List<ItemStack> pattern) {
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack expected = pattern.get(patternIndex);
            ItemStack actual = handler.getSlot(GRID_START + patternIndex).getStack();
            if (expected.isEmpty() != actual.isEmpty()) {
                if (expected.isEmpty()) {
                    return false;
                }
                continue;
            }
            if (!expected.isEmpty() && !ItemStack.areItemsAndComponentsEqual(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAnyFillableGridSlot(CraftingScreenHandler handler, List<ItemStack> pattern) {
        for (ItemStack material : pattern) {
            if (!material.isEmpty() && findFillableGridSlot(handler, pattern, material) != -1) {
                return true;
            }
        }
        return false;
    }

    private static int findFillableGridSlot(CraftingScreenHandler handler,
                                            List<ItemStack> pattern,
                                            ItemStack material) {
        for (int patternIndex = 0; patternIndex < pattern.size(); patternIndex++) {
            ItemStack expected = pattern.get(patternIndex);
            if (expected.isEmpty() || !ItemStack.areItemsAndComponentsEqual(expected, material)) {
                continue;
            }
            int slotId = GRID_START + patternIndex;
            Slot slot = handler.getSlot(slotId);
            ItemStack current = slot.getStack();
            // Quick Shulker 3.0.0 只拦截空目标槽；非空槽会回落到原版点击并可能交换盒子。
            if (!current.isEmpty() || !slot.canInsert(material)) {
                continue;
            }
            return slotId;
        }
        return -1;
    }

    private static List<ItemStack> getStoredStacks(ItemStack shulker) {
        ContainerComponent container = shulker.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : container.iterateNonEmpty()) {
            stacks.add(stack);
        }
        return stacks;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static SourceShulker findSource(ScreenHandler handler,
                                            List<ItemStack> pattern,
                                            Set<Integer> excludedPlayerIndices) {
        int scannedShulkers = 0;
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasStack() || slot.getStack().getCount() != 1 || !isShulkerBox(slot.getStack())) {
                continue;
            }
            scannedShulkers++;
            if (scannedShulkers > MAX_SOURCE_SHULKERS) {
                break;
            }
            if (excludedPlayerIndices.contains(slot.getIndex())
                    || !isHomogeneousShulker(slot.getStack())) {
                continue;
            }
            ItemStack material = getFirstStoredStack(slot.getStack());
            if (handler instanceof CraftingScreenHandler craftingHandler
                    && findFillableGridSlot(craftingHandler, pattern, material) != -1) {
                LOGGER.trace("选择潜影盒直填来源：扫描序号={}，玩家槽={}，材料={}",
                        scannedShulkers, slot.getIndex(), material.getName().getString());
                return new SourceShulker(slot.getIndex());
            }
        }
        return null;
    }

    private static SourceShulker findSourceTimed(ScreenHandler handler,
                                                 List<ItemStack> pattern,
                                                 Set<Integer> excludedPlayerIndices) {
        long startedAtNanos = System.nanoTime();
        SourceShulker source = findSource(handler, pattern, excludedPlayerIndices);
        if (task != null) {
            task.sourceScanNanos += System.nanoTime() - startedAtNanos;
        }
        return source;
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String describePattern(List<ItemStack> pattern) {
        List<String> descriptions = new ArrayList<>();
        for (int i = 0; i < pattern.size(); i++) {
            ItemStack material = pattern.get(i);
            if (!material.isEmpty()) {
                descriptions.add((i + 1) + ":" + material.getName().getString());
            }
        }
        return String.join(", ", descriptions);
    }

    private static String describeGrid(CraftingScreenHandler handler) {
        List<String> descriptions = new ArrayList<>();
        for (int slotId = GRID_START; slotId <= GRID_END; slotId++) {
            descriptions.add(slotId + ":" + describeStack(handler.getSlot(slotId).getStack()));
        }
        return String.join(", ", descriptions);
    }

    private static String describeStack(ItemStack stack) {
        return stack.isEmpty() ? "空" : stack.getName().getString() + "x" + stack.getCount();
    }

    public enum TaskResult {
        NONE,
        REFILLED,
        STOPPED
    }

    public enum RefillStart {
        STARTED,
        RECOVERED_DESYNC,
        GRID_MISMATCH,
        NOT_STARTED,
        NO_MATERIALS
    }

    private static final class Task {
        private final long id = ++nextTaskId;
        private final int workbenchSyncId;
        private final List<ItemStack> pattern;
        private final TaskOwner owner;
        private final Set<Integer> exhaustedSourcePlayerIndices = new HashSet<>();
        private final long startedAtNanos = System.nanoTime();
        private int actionCooldown;
        private int tickCount;
        private int cooldownWaitTicks;
        private int currentSourcePlayerIndex = -1;
        private int movedItems;
        private int unbundleClicks;
        private int sourceBatches;
        private long sourceScanNanos;
        private long pickupBoxNanos;
        private long unbundleNanos;
        private long returnBoxNanos;
        private SourceShulker nextSource;
        private boolean stopRequested;
        private boolean exitAfterStop;

        private Task(List<ItemStack> pattern, int workbenchSyncId, TaskOwner owner) {
            this.pattern = pattern;
            this.workbenchSyncId = workbenchSyncId;
            this.owner = owner;
        }
    }

    private enum TaskOwner {
        LEGACY,
        SHULKER_CRAFT
    }

    private record SourceShulker(int playerIndex) {
    }
}
