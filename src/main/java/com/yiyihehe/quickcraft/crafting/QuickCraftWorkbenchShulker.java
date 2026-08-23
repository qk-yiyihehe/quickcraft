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
    private static final int GRID_START = 1;
    private static final int GRID_END = 9;
    private static final int MAX_UNBUNDLE_CLICKS_PER_SOURCE = GRID_END - GRID_START + 1;
    static final int MAX_SOURCE_SHULKERS = PlayerInventory.MAIN_SIZE;
    private static final int MULTI_SOURCE_BATCHES_PER_ACK = 3;
    private static final Identifier QUICK_SHULKER_OPEN_PACKET =
            Identifier.of("quickshulker", "open_shulker_packet");

    private static Task task;
    private static TaskResult pendingResult = TaskResult.NONE;
    private static Text pendingMessage;
    private static TaskOwner pendingOwner;
    private static int sessionSourceBatches;
    private static QuickCraftConfigs.WorkbenchShulkerPipelineMode sessionMode =
            QuickCraftConfigs.WorkbenchShulkerPipelineMode.RESPONSE_STABLE;

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

    static void beginSession(QuickCraftConfigs.WorkbenchShulkerPipelineMode mode) {
        sessionSourceBatches = 0;
        sessionMode = mode;
    }

    static void clearSession() {
        sessionSourceBatches = 0;
        sessionMode = QuickCraftConfigs.WorkbenchShulkerPipelineMode.RESPONSE_STABLE;
    }

    static int sessionSourceBatches() {
        return sessionSourceBatches;
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
            return RefillStart.NOT_STARTED;
        }
        if (!handler.getCursorStack().isEmpty()) {
            if (owner == TaskOwner.SHULKER_CRAFT
                    && isSingleShulker(handler.getCursorStack())
                    && recoverUnexpectedShulker(handler, -1)) {
                return RefillStart.RECOVERED_DESYNC;
            }
            return RefillStart.NOT_STARTED;
        }
        List<ItemStack> normalizedPattern = normalizePattern(pattern);
        if (!isGridCompatible(handler, normalizedPattern)) {
            int unexpectedShulkerSlot = findUnexpectedGridShulker(handler, normalizedPattern);
            if (owner == TaskOwner.SHULKER_CRAFT
                    && unexpectedShulkerSlot != -1
                    && recoverUnexpectedShulker(handler, unexpectedShulkerSlot)) {
                return RefillStart.RECOVERED_DESYNC;
            }
            return RefillStart.GRID_MISMATCH;
        }
        task = new Task(normalizedPattern, handler.syncId, owner);
        task.nextSource = findSource(handler, normalizedPattern, Set.of());
        if (task.nextSource == null) {
            task = null;
            return RefillStart.NO_MATERIALS;
        }
        return RefillStart.STARTED;
    }

    public static void tick(MinecraftClient client) {
        tick(client, TaskOwner.LEGACY);
    }

    public static void tickShulkerCraft(MinecraftClient client) {
        tick(client, TaskOwner.SHULKER_CRAFT);
    }

    private static void tick(MinecraftClient client, TaskOwner owner) {
        tick(client, owner, -1);
    }

    private static void tick(MinecraftClient client, TaskOwner owner, int sourceBatchLimit) {
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

        if (owner != TaskOwner.SHULKER_CRAFT && task.actionCooldown > 0) {
            task.actionCooldown--;
            return;
        }
        int maxSourceBatches = owner == TaskOwner.SHULKER_CRAFT
                ? sourceBatchesPerAck(sessionMode)
                : 1;
        if (sourceBatchLimit > 0) {
            maxSourceBatches = Math.min(maxSourceBatches, sourceBatchLimit);
        }
        for (int batch = 0; batch < maxSourceBatches && task != null; batch++) {
            extractDirectly(client, handler);
            if (task == null || isActionCoolingDown(owner)) {
                break;
            }
        }
    }

    private static void extractDirectly(MinecraftClient client, CraftingScreenHandler handler) {
        if (task.owner == TaskOwner.SHULKER_CRAFT) {
            sessionSourceBatches++;
        }
        SourceShulker source = task.nextSource;
        task.nextSource = null;
        if (source == null) {
            source = findSource(handler, task.pattern, task.exhaustedSourcePlayerIndices);
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

        client.interactionManager.clickSlot(handler.syncId, sourceSlot.id, 0,
                SlotActionType.PICKUP, client.player);
        task.currentSourcePlayerIndex = source.playerIndex();
        ItemStack cursorBox = handler.getCursorStack();
        if (!isSingleShulker(cursorBox) || !isHomogeneousShulker(cursorBox)) {
            if (!returnHeldBox(client, handler, sourceSlot)) {
                finish(TaskResult.STOPPED, Text.translatable("quickcraft.message.crafting.shulker_cursor_blocked"));
                return;
            }
            task.exhaustedSourcePlayerIndices.add(source.playerIndex());
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

        int clicks = 0;
        boolean extractionUnavailable = false;
        while (clicks < MAX_UNBUNDLE_CLICKS_PER_SOURCE) {
            ItemStack currentCursorBox = handler.getCursorStack();
            if (!isSingleShulker(currentCursorBox) || getFirstStoredStack(currentCursorBox).isEmpty()) {
                break;
            }
            int targetSlotId = findFillableGridSlot(handler, task.pattern, boxMaterial);
            if (targetSlotId == -1) {
                break;
            }

            Slot target = handler.getSlot(targetSlotId);
            int before = target.hasStack() ? target.getStack().getCount() : 0;
            client.interactionManager.clickSlot(handler.syncId, targetSlotId, 1,
                    SlotActionType.PICKUP, client.player);
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
                break;
            }
            int moved = afterCount - before;
            clicks++;
            task.movedItems += moved;
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
        if (!hasAnyFillableGridSlot(handler, task.pattern)) {
            finish(TaskResult.REFILLED, null);
            return;
        }
        task.nextSource = findSource(handler, task.pattern, task.exhaustedSourcePlayerIndices);
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
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }
        if (!sourceSlot.hasStack() && sourceSlot.canInsert(handler.getCursorStack())) {
            client.interactionManager.clickSlot(handler.syncId, sourceSlot.id, 0,
                    SlotActionType.PICKUP, client.player);
        }
        return handler.getCursorStack().isEmpty();
    }

    private static void finish(TaskResult result, Text message) {
        boolean exitAfterStop = task != null && task.exitAfterStop;
        TaskOwner owner = task == null ? null : task.owner;
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
        if (task != null && task.owner != TaskOwner.SHULKER_CRAFT) {
            task.actionCooldown = Math.max(0,
                    QuickCraftConfigs.getQuickShulkerActionIntervalTicks() - 1);
        }
    }

    private static boolean isActionCoolingDown(TaskOwner owner) {
        return owner != TaskOwner.SHULKER_CRAFT
                && task != null
                && task.actionCooldown > 0;
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

    static int sourceBatchesPerAck(QuickCraftConfigs.WorkbenchShulkerPipelineMode mode) {
        return mode == null || mode == QuickCraftConfigs.WorkbenchShulkerPipelineMode.RESPONSE_STABLE
                ? 1
                : MULTI_SOURCE_BATCHES_PER_ACK;
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
            return false;
        }

        client.interactionManager.clickSlot(handler.syncId, destination.id, 0,
                SlotActionType.PICKUP, client.player);
        boolean recovered = handler.getCursorStack().isEmpty()
                && isSingleShulker(destination.getStack());
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
                return new SourceShulker(slot.getIndex());
            }
        }
        return null;
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
        private final int workbenchSyncId;
        private final List<ItemStack> pattern;
        private final TaskOwner owner;
        private final Set<Integer> exhaustedSourcePlayerIndices = new HashSet<>();
        private int actionCooldown;
        private int currentSourcePlayerIndex = -1;
        private int movedItems;
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
