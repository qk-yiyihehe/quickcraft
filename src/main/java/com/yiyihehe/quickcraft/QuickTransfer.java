package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftLayout;
import com.yiyihehe.quickcraft.mixin.CreativeInventoryScreenInvoker;
import com.yiyihehe.quickcraft.mixin.CreativeSlotAccessor;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.BeaconScreenHandler;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.slot.TradeOutputSlot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 可配置组合键同类转移：
 * - 鼠标在玩家主背包/快捷栏：转移同类物品到当前打开容器
 * - 鼠标在容器/合成格等非背包区域：转移同类物品到玩家主背包/快捷栏
 * - 按住快捷键滑过物品时，每个滑过的物品种类触发一次同类转移
 * - 按住格子转移快捷键滑过格子时，每个滑过的格子触发一次原版 Shift 转移
 * - 按住保留一个快捷键单击或滑过格子时，对当前格子执行一次“源侧保留 1 个”的转移
 */
public final class QuickTransfer implements ClientModInitializer {
    private static final int SLOT_SIZE = 18;
    private static final int SLIDE_SAMPLE_STEP = SLOT_SIZE / 2;
    private static final long RESULT_SYNC_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);

    private static HandledScreen<?> activeScreen;
    private static TransferMode activeMode = TransferMode.NONE;
    private static SlotKey lastHoveredSlotKey;
    private static boolean hasLastMousePosition;
    private static double lastMouseX;
    private static double lastMouseY;
    private static HandledScreen<?> scrollScreen;
    private static SlotKey scrollSlotKey;
    private static ItemStack scrollTemplate = ItemStack.EMPTY;
    private static ResultTransfer pendingResultTransfer;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean handleQuickTransferHotkey() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof HandledScreen<?> screen) || !canUseQuickTransfer(client, screen)) {
            return false;
        }

        double mouseX = getMouseX(client);
        double mouseY = getMouseY(client);
        ensureHoldGesture(screen, TransferMode.MATCHING);

        Slot hoveredSlot = findHoveredSlot(screen, mouseX, mouseY);
        if (hoveredSlot == null) {
            return false;
        }

        boolean handled = processHoveredSlot(screen, hoveredSlot, TransferMode.MATCHING);
        lastHoveredSlotKey = createSlotKey(screen, hoveredSlot);
        hasLastMousePosition = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        return handled;
    }

    public static boolean handleSlotQuickTransferHotkey() {
        cancelResultTransfer();
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof HandledScreen<?> screen) || !canUseQuickTransfer(client, screen)) {
            return false;
        }

        double mouseX = getMouseX(client);
        double mouseY = getMouseY(client);
        ensureHoldGesture(screen, TransferMode.SLOT);

        Slot hoveredSlot = findHoveredSlot(screen, mouseX, mouseY);
        if (hoveredSlot == null) {
            return false;
        }

        boolean handled = processHoveredSlot(screen, hoveredSlot, TransferMode.SLOT);
        lastHoveredSlotKey = createSlotKey(screen, hoveredSlot);
        hasLastMousePosition = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        return handled;
    }

    public static boolean handleQuickTransferRetainOneHotkey() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof HandledScreen<?> screen) || !canUseQuickTransfer(client, screen)) {
            return false;
        }

        double mouseX = getMouseX(client);
        double mouseY = getMouseY(client);
        ensureHoldGesture(screen, TransferMode.MATCHING_RETAIN_ONE);

        Slot hoveredSlot = findHoveredSlot(screen, mouseX, mouseY);
        if (hoveredSlot == null) {
            return false;
        }

        boolean handled = processHoveredSlot(screen, hoveredSlot, TransferMode.MATCHING_RETAIN_ONE);
        lastHoveredSlotKey = createSlotKey(screen, hoveredSlot);
        hasLastMousePosition = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        return handled;
    }

    /** 第三方输入入口只查询事件归属，不发点击或修改滚轮记忆。 */
    public static boolean shouldHandleScrollTransfer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof HandledScreen<?> screen) || screen instanceof CreativeInventoryScreen
                || !canUseScrollTransfer(client, screen)) {
            return false;
        }
        Slot slot = findHoveredSlot(screen, getMouseX(client), getMouseY(client));
        return slot != null && isVisibleSlot(slot) && !resolveScrollTemplate(screen, slot).isEmpty();
    }

    private static ItemStack resolveScrollTemplate(HandledScreen<?> screen, Slot slot) {
        if (slot.hasStack()) {
            return slot.getStack();
        }
        return scrollScreen == screen && createSlotKey(screen, slot).equals(scrollSlotKey) ? scrollTemplate
                : ItemStack.EMPTY;
    }

    private static void refreshScrollMemory(MinecraftClient client) {
        if (scrollScreen == null) {
            return;
        }
        if (client.currentScreen != scrollScreen || !canUseScrollTransfer(client, scrollScreen)) {
            resetScrollMemory();
            return;
        }
        Slot slot = findHoveredSlot(scrollScreen, getMouseX(client), getMouseY(client));
        if (slot == null || !createSlotKey(scrollScreen, slot).equals(scrollSlotKey) || !canUseScrollTransferSlot(
                scrollScreen,
                slot,
                client
        ) || (slot.hasStack() && !ItemStack.areItemsAndComponentsEqual(slot.getStack(), scrollTemplate))) {
            resetScrollMemory();
        }
    }

    private static void resetScrollMemory() {
        scrollScreen = null;
        scrollSlotKey = null;
        scrollTemplate = ItemStack.EMPTY;
    }

    public static boolean handleScrollTransfer(
            HandledScreen<?> screen,
            double mouseX,
            double mouseY,
            double verticalAmount
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (verticalAmount == 0 || screen instanceof CreativeInventoryScreen || !canUseScrollTransfer(client, screen)) {
            resetScrollMemory();
            return false;
        }

        Slot hoveredSlot = findHoveredSlot(screen, mouseX, mouseY);
        if (hoveredSlot == null || !isVisibleSlot(hoveredSlot)) {
            resetScrollMemory();
            return false;
        }
        ItemStack template = resolveScrollTemplate(screen, hoveredSlot).copy();
        if (template.isEmpty()) {
            resetScrollMemory();
            return false;
        }
        // 认领事件与是否能移动分开，满包、缺料和锁格时也不交给其它滚轮处理器重试。
        if (!canUseScrollTransferSlot(screen, hoveredSlot, client)) {
            resetScrollMemory();
            return true;
        }
        template.setCount(1);
        scrollScreen = screen;
        scrollSlotKey = createSlotKey(screen, hoveredSlot);
        scrollTemplate = template;

        boolean moveToOtherInventory = verticalAmount > 0;
        if (QuickCraftKeyBindings.isShiftDown()) {
            moveFullStackByScroll(screen, hoveredSlot, template, moveToOtherInventory);
        } else if (QuickCraftKeyBindings.isAltDown()) {
            moveMatchingStacksByScroll(screen, hoveredSlot, template, moveToOtherInventory);
        } else {
            moveSingleItemByScroll(screen, hoveredSlot, template, moveToOtherInventory);
        }
        return true;
    }

    private void onClientTick(MinecraftClient client) {
        continueResultTransfer(client);
        refreshScrollMemory(client);
        TransferMode mode = getHeldTransferMode();
        if (mode == TransferMode.NONE) {
            resetHoldGesture();
            return;
        }

        if (!(client.currentScreen instanceof HandledScreen<?> screen) || !canUseQuickTransfer(client, screen)) {
            resetHoldGesture();
            return;
        }

        double mouseX = getMouseX(client);
        double mouseY = getMouseY(client);
        ensureHoldGesture(screen, mode);

        SlotKey previousHoveredSlotKey = lastHoveredSlotKey;
        List<Slot> hoveredSlots = findHoveredSlotsAlongPath(screen, mouseX, mouseY);
        for (Slot slot : hoveredSlots) {
            SlotKey key = createSlotKey(screen, slot);
            if (key.equals(previousHoveredSlotKey)) {
                continue;
            }
            processHoveredSlot(screen, slot, mode);
        }

        Slot currentHoveredSlot = findHoveredSlot(screen, mouseX, mouseY);
        lastHoveredSlotKey = currentHoveredSlot == null ? null : createSlotKey(screen, currentHoveredSlot);
        hasLastMousePosition = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    private static boolean canUseQuickTransfer(MinecraftClient client, HandledScreen<?> screen) {
        return QuickCraftConfigs.isQuickTransferEnabled() && client.player != null && client.interactionManager != null
                && !isTextInputFocused(screen) && screen.getScreenHandler().getCursorStack().isEmpty();
    }

    public static Slot resolveHighlightSlot(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        return QuickCraftConfigs.isMatchingTransferHighlightEnabled() && canUseQuickTransfer(client, screen)
                ? findHoveredSlot(screen, getMouseX(client), getMouseY(client))
                : null;
    }

    public static boolean shouldHighlightMatchingSlot(HandledScreen<?> screen, Slot slot, Slot hoveredSlot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!QuickCraftConfigs.isMatchingTransferHighlightEnabled() || !canUseQuickTransfer(client, screen)
                || !isVisibleSlot(slot) || !slot.hasStack()) {
            return false;
        }

        return hoveredSlot != null && hoveredSlot.hasStack() && ItemStack.areItemsAndComponentsEqual(
                slot.getStack(),
                hoveredSlot.getStack()
        );
    }

    private static boolean canUseScrollTransfer(MinecraftClient client, HandledScreen<?> screen) {
        return QuickCraftConfigs.isScrollTransferEnabled() && client.player != null && client.interactionManager != null
                && !isTextInputFocused(screen) && screen.getScreenHandler().getCursorStack().isEmpty();
    }

    private static boolean canUseScrollTransferSlot(HandledScreen<?> screen, Slot slot, MinecraftClient client) {
        return slot != null && isVisibleSlot(slot) && slot.canTakeItems(client.player) && !QuickContainerLock
                .isLockedSlot(screen.getScreenHandler(), slot);
    }

    private static boolean moveFullStackByScroll(
            HandledScreen<?> screen,
            Slot hoveredSlot,
            ItemStack template,
            boolean moveToOtherInventory
    ) {
        Slot sourceSlot = findScrollSourceSlot(screen, hoveredSlot, template, moveToOtherInventory);
        return sourceSlot != null && quickMoveHoveredSlot(screen, sourceSlot, isPlayerStorageSlot(sourceSlot));
    }

    private static boolean moveMatchingStacksByScroll(
            HandledScreen<?> screen,
            Slot hoveredSlot,
            ItemStack template,
            boolean moveToOtherInventory
    ) {
        Slot sourceSlot = findScrollSourceSlot(screen, hoveredSlot, template, moveToOtherInventory);
        return sourceSlot != null && processHoveredSlot(screen, sourceSlot, TransferMode.MATCHING);
    }

    private static boolean moveSingleItemByScroll(
            HandledScreen<?> screen,
            Slot hoveredSlot,
            ItemStack template,
            boolean moveToOtherInventory
    ) {
        Slot sourceSlot = findScrollSourceSlot(screen, hoveredSlot, template, moveToOtherInventory);
        if (sourceSlot == null) {
            return false;
        }
        if (canMoveFromPlayerStorage(screen.getScreenHandler()) && isSingleShulkerBox(sourceSlot.getStack())) {
            // QuickShulker 将单个潜影盒右键放入空槽识别为解包；快速移动可避免触发该物品回调。
            return quickMoveHoveredSlot(screen, sourceSlot, isPlayerStorageSlot(sourceSlot));
        }
        List<Integer> targetSlotIds = moveToOtherInventory ? getOtherInventoryTargetSlotIds(
                screen.getScreenHandler(),
                hoveredSlot
        ) : getPreferredTargetSlotIds(screen.getScreenHandler(), hoveredSlot);
        return moveOneSourceItemToTargetSlots(screen, sourceSlot.id, template, targetSlotIds);
    }

    private static boolean isSingleShulkerBox(ItemStack stack) {
        return stack.getCount() == 1 && stack.getItem() instanceof BlockItem blockItem && blockItem
                .getBlock() instanceof ShulkerBoxBlock;
    }

    private static Slot findScrollSourceSlot(
            HandledScreen<?> screen,
            Slot hoveredSlot,
            ItemStack template,
            boolean moveToOtherInventory
    ) {
        ScreenHandler handler = screen.getScreenHandler();
        MinecraftClient client = MinecraftClient.getInstance();
        if (moveToOtherInventory && isMatchingTransferCandidate(hoveredSlot, template, client, handler)) {
            return hoveredSlot;
        }
        if (!canMoveFromPlayerStorage(handler) && !moveToOtherInventory) {
            return null;
        }
        // 结果槽移空后不借用其它输入/结果槽继续操作。
        if (moveToOtherInventory && !hoveredSlot.canInsert(template)) {
            return null;
        }
        boolean sourceFromPlayerStorage = moveToOtherInventory == isPlayerStorageSlot(hoveredSlot);
        for (Slot slot : handler.slots) {
            if (isMatchingSourceSlot(slot, template, sourceFromPlayerStorage, client, handler)
                    && (canMoveFromPlayerStorage(handler) || !isPlayerStorageSlot(hoveredSlot)
                            || belongsToPlayerStorageSide(slot, isPlayerHotbarSlot(hoveredSlot)))) {
                return slot;
            }
        }
        return null;
    }

    private static TransferMode getHeldTransferMode() {
        if (QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.Hotkeys.QUICK_TRANSFER_RETAIN_ONE.getKeybind())) {
            return TransferMode.MATCHING_RETAIN_ONE;
        }
        if (QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.Hotkeys.SLOT_QUICK_TRANSFER.getKeybind())) {
            return TransferMode.SLOT;
        }
        if (QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.Hotkeys.QUICK_TRANSFER.getKeybind())) {
            return TransferMode.MATCHING;
        }
        return TransferMode.NONE;
    }

    private static void ensureHoldGesture(HandledScreen<?> screen, TransferMode mode) {
        if (activeScreen == screen && activeMode == mode) {
            return;
        }

        activeScreen = screen;
        activeMode = mode;
        lastHoveredSlotKey = null;
        hasLastMousePosition = false;
    }

    private static void resetHoldGesture() {
        activeScreen = null;
        activeMode = TransferMode.NONE;
        lastHoveredSlotKey = null;
        hasLastMousePosition = false;
    }

    private static boolean processHoveredSlot(HandledScreen<?> screen, Slot hoveredSlot, TransferMode mode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !isVisibleSlot(hoveredSlot)) {
            return false;
        }

        if (screen instanceof CreativeInventoryScreen creativeScreen) {
            return processCreativeHoveredSlot(creativeScreen, hoveredSlot, mode);
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (QuickContainerLock.isLockedSlot(handler, hoveredSlot)) {
            return false;
        }

        if (!hoveredSlot.hasStack() || !hoveredSlot.canTakeItems(client.player)) {
            return false;
        }

        boolean sourceFromPlayerStorage = isPlayerStorageSlot(hoveredSlot);

        boolean handled;
        if (mode == TransferMode.SLOT) {
            handled = quickMoveHoveredSlot(screen, hoveredSlot, sourceFromPlayerStorage);
        } else if (mode == TransferMode.MATCHING_RETAIN_ONE) {
            handled = moveHoveredSlotRetainingOne(screen, hoveredSlot, sourceFromPlayerStorage);
        } else if (sourceFromPlayerStorage && !canMoveFromPlayerStorage(handler)) {
            // 玩家物品栏界面没有外部容器时，同类滑动按主背包/快捷栏两边互转处理。
            handled = moveAllMatchingPlayerStorageStacksByQuickMove(screen, hoveredSlot);
        } else if (sourceFromPlayerStorage) {
            handled = moveAllMatchingStacksByQuickMove(screen, hoveredSlot, true);
        } else {
            handled = moveAllMatchingStacksToPlayerMainInventory(screen, hoveredSlot);
        }

        return handled;
    }

    private static boolean processCreativeHoveredSlot(
            CreativeInventoryScreen screen,
            Slot hoveredSlot,
            TransferMode mode
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !isCreativePlayerStorageSlot(hoveredSlot) || QuickContainerLock.isLockedSlot(
                client.player.playerScreenHandler,
                unwrapCreativeSlot(hoveredSlot)
        ) || !hoveredSlot.hasStack() || !hoveredSlot.canTakeItems(client.player)) {
            return false;
        }

        if (mode == TransferMode.MATCHING_RETAIN_ONE) {
            return false;
        }

        // 创造模式下，Alt 同类转移也要保持“主背包/快捷栏两边互转”的语义，
        // 不能一律退化成原版 Shift 的单格快速移动。
        if (mode != TransferMode.SLOT) {
            return moveAllMatchingCreativePlayerStorageStacksByQuickMove(screen, hoveredSlot);
        }

        return quickMoveCreativeSlot(screen, hoveredSlot);
    }

    private static boolean quickMoveCreativeSlot(CreativeInventoryScreen screen, Slot slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        if (screen.isInventoryTabSelected()) {
            Slot effectiveSlot = unwrapCreativeSlot(slot);
            if (QuickContainerLock.isLockedSlot(client.player.playerScreenHandler, effectiveSlot)) {
                return false;
            }
            client.interactionManager.clickSlot(
                    client.player.playerScreenHandler.syncId,
                    effectiveSlot.id,
                    0,
                    SlotActionType.QUICK_MOVE,
                    client.player
            );
            return true;
        }

        ((CreativeInventoryScreenInvoker) screen).quickcraft$invokeOnMouseClick(
                slot,
                slot.id,
                0,
                SlotActionType.QUICK_MOVE
        );
        return true;
    }

    private static boolean isCreativePlayerStorageSlot(Slot slot) {
        return isCreativePlayerStorageSlotInRange(slot, 0, 35);
    }

    private static boolean isCreativePlayerMainInventorySlot(Slot slot) {
        return isCreativePlayerStorageSlotInRange(slot, 9, 35);
    }

    private static boolean isCreativePlayerHotbarSlot(Slot slot) {
        return isCreativePlayerStorageSlotInRange(slot, 0, 8);
    }

    private static boolean isCreativePlayerStorageSlotInRange(Slot slot, int minInventoryIndex, int maxInventoryIndex) {
        return isPlayerStorageSlotInRange(unwrapCreativeSlot(slot), minInventoryIndex, maxInventoryIndex);
    }

    private static boolean moveAllMatchingCreativePlayerStorageStacksByQuickMove(
            CreativeInventoryScreen screen,
            Slot hoveredSlot
    ) {
        ItemStack template = hoveredSlot.getStack().copy();
        if (template.isEmpty()) {
            return false;
        }

        boolean sourceFromHotbar = isCreativePlayerHotbarSlot(hoveredSlot);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false;
        }
        ScreenHandler handler = client.player.playerScreenHandler;
        List<Slot> sourceSlots = snapshotMatchingSlots(
                screen.getScreenHandler().slots,
                hoveredSlot,
                slot -> isMatchingCreativePlayerStorageSideSlot(slot, template, sourceFromHotbar, client, handler)
        );
        return moveMatchingSlots(
                sourceSlots,
                slot -> isMatchingCreativePlayerStorageSideSlot(slot, template, sourceFromHotbar, client, handler),
                slot -> quickMoveCreativeSlot(screen, slot)
        );
    }

    private static boolean belongsToCreativePlayerStorageSide(Slot slot, boolean sourceFromHotbar) {
        return sourceFromHotbar ? isCreativePlayerHotbarSlot(slot) : isCreativePlayerMainInventorySlot(slot);
    }

    private static Slot unwrapCreativeSlot(Slot slot) {
        if (slot instanceof CreativeSlotAccessor accessor) {
            Slot wrappedSlot = accessor.quickcraft$getWrappedSlot();
            if (wrappedSlot != null) {
                return wrappedSlot;
            }
        }
        return slot;
    }

    private static SlotKey createSlotKey(HandledScreen<?> screen, Slot slot) {
        if (screen instanceof CreativeInventoryScreen) {
            Slot effectiveSlot = unwrapCreativeSlot(slot);
            return new SlotKey(screen.getScreenHandler(), effectiveSlot.id);
        }
        return new SlotKey(screen.getScreenHandler(), slot.id);
    }

    private static boolean quickMoveHoveredSlot(
            HandledScreen<?> screen,
            Slot hoveredSlot,
            boolean sourceFromPlayerStorage
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        if (!isVisibleSlot(hoveredSlot)
                || !hoveredSlot.hasStack()
                || !hoveredSlot.canTakeItems(client.player)
                || QuickContainerLock.isLockedSlot(screen.getScreenHandler(), hoveredSlot)
                || !belongsToSourceRegion(hoveredSlot, sourceFromPlayerStorage)) {
            return false;
        }

        if (isRepeatableResult(screen.getScreenHandler(), hoveredSlot)) {
            quickMoveResult(client, screen, hoveredSlot);
        } else {
            quickMoveSlot(screen, hoveredSlot);
        }
        return true;
    }

    private static boolean isRepeatableResult(ScreenHandler handler, Slot slot) {
        return slot instanceof CraftingResultSlot || slot instanceof TradeOutputSlot
                || (handler instanceof StonecutterScreenHandler && slot.id == 1)
                || (handler instanceof CartographyTableScreenHandler && slot.id == 2)
                || ((handler instanceof LoomScreenHandler || handler instanceof SmithingScreenHandler) && slot.id == 3);
    }

    private static void quickMoveResult(MinecraftClient client, HandledScreen<?> screen, Slot source) {
        cancelResultTransfer();
        ScreenHandler handler = screen.getScreenHandler();
        if (client.getServer() != null || handler.slots.stream().noneMatch(
                slot -> isPlayerStorageSlot(slot) && QuickContainerLock.isLockedSlot(handler, slot)
        )) {
            client.interactionManager.clickSlot(handler.syncId, source.id, 0, SlotActionType.QUICK_MOVE, client.player);
            return;
        }

        // 远程服务端自行选择 QUICK_MOVE 落点，显式取放才能避开客户端的锁格。
        moveResultRound(client, screen, source, source.getStack().copy());
    }

    private static void moveResultRound(
            MinecraftClient client,
            HandledScreen<?> screen,
            Slot source,
            ItemStack result
    ) {
        if (QuickSlotTransfer.moveResultWithPickup(client, screen.getScreenHandler(), source.id)) {
            pendingResultTransfer = new ResultTransfer(
                    screen,
                    source,
                    result,
                    System.nanoTime() + RESULT_SYNC_TIMEOUT_NANOS
            );
        }
    }

    private static void continueResultTransfer(MinecraftClient client) {
        ResultTransfer transfer = pendingResultTransfer;
        if (transfer == null) {
            return;
        }
        if (client.currentScreen != transfer.screen() || (!canUseQuickTransfer(client, transfer.screen())
                && !canUseScrollTransfer(client, transfer.screen())) || client.player.currentScreenHandler != transfer
                        .screen().getScreenHandler() || System.nanoTime() - transfer.syncDeadline() >= 0) {
            cancelResultTransfer();
            return;
        }
        ItemStack next = transfer.source().getStack();
        if (next.isEmpty()) {
            return;
        }

        cancelResultTransfer();
        if (ItemStack.areItemsEqual(transfer.result(), next)) {
            moveResultRound(client, transfer.screen(), transfer.source(), transfer.result());
        }
    }

    public static void cancelResultTransfer() {
        pendingResultTransfer = null;
    }

    private record ResultTransfer(HandledScreen<?> screen, Slot source, ItemStack result, long syncDeadline) {
    }

    private static void quickMoveSlot(HandledScreen<?> screen, Slot slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        var handler = screen.getScreenHandler();
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }
        boolean fromPlayer = isPlayerStorageSlot(slot);
        boolean fromHotbar = isPlayerHotbarSlot(slot);
        boolean localServer = client.getServer() != null;
        if (localServer && (!fromPlayer || handler instanceof CraftingScreenHandler)) {
            client.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, client.player);
            return;
        }

        if (fromPlayer && handler instanceof CraftingScreenHandler) {
            // 远程服的原版回退可能写进锁格，只有这个方向有锁格时才需要显式取放。
            var layout = QuickCraftMouseCraftLayout.WORKBENCH;
            int fallbackStart = fromHotbar ? layout.playerMainInventoryStart() : layout.playerHotbarStart();
            int fallbackEnd = fromHotbar ? layout.playerHotbarStart()
                    : layout.playerMainInventoryStart() + QuickCraftMouseCraftLayout.PLAYER_INVENTORY_SIZE;
            ItemStack before = slot.getStack().copy();
            if (QuickContainerLock.hasLockedSlotInRange(handler, fallbackStart, fallbackEnd) && QuickSlotTransfer
                    .tryMoveToWorkbenchGrid(client, handler, slot)) {
                ItemStack remaining = slot.getStack();
                if (remaining.isEmpty() || !handler.getCursorStack().isEmpty() || !ItemStack.areItemsAndComponentsEqual(
                        before,
                        remaining
                ) || remaining.getCount() >= before.getCount()) {
                    return;
                }
            }
        }
        if (fromPlayer && handler instanceof PlayerScreenHandler) {
            EquipmentSlot equipment = client.player.getPreferredEquipmentSlot(slot.getStack());
            int equipmentSlotId = switch (equipment) {
                case HEAD -> 5;
                case CHEST -> 6;
                case LEGS -> 7;
                case FEET -> 8;
                case OFFHAND -> PlayerScreenHandler.OFFHAND_ID;
                default -> -1;
            };
            if (equipmentSlotId >= 0 && !handler.getSlot(equipmentSlotId).hasStack() && QuickContainerLock
                    .hasLockedSlotInRange(handler, fromHotbar ? 9 : 36, fromHotbar ? 36 : 45)) {
                Slot target = handler.getSlot(equipmentSlotId);
                QuickSlotTransfer.moveAmount(client, handler, slot, target, slot.getStack().getCount());
                return;
            }
        }
        boolean playerStorageOnly = fromPlayer && (!canMoveFromPlayerStorage(handler) || (!localServer
                && usesPlayerStorageFallback(client, handler, slot.getStack())));
        QuickSlotTransfer.quickMove(client, handler, slot.id, target -> {
            if (!fromPlayer) {
                return isPlayerStorageSlot(target);
            }
            if (!playerStorageOnly) {
                return !isPlayerStorageSlot(target);
            }
            return fromHotbar ? isPlayerMainInventorySlot(target) : isPlayerHotbarSlot(target);
        }, !fromPlayer && !returnsInputInForwardOrder(handler, slot));
    }

    private static boolean usesPlayerStorageFallback(MinecraftClient client, ScreenHandler handler, ItemStack stack) {
        if (handler instanceof MerchantScreenHandler) {
            return true;
        }
        if (handler instanceof StonecutterScreenHandler) {
            return !QuickFillContainerRecipeAccess.isStonecutterInput(client, stack);
        }
        if (handler instanceof AbstractFurnaceScreenHandler) {
            return !QuickFillContainerRecipeAccess.isAccepted(client, handler, stack) && !QuickFillContainerRecipeAccess
                    .isFurnaceFuel(client, stack);
        }
        if (handler instanceof BeaconScreenHandler) {
            Slot payment = handler.getSlot(0);
            return payment.hasStack() || stack.getCount() != 1 || !payment.canInsert(stack);
        }
        if (handler instanceof GrindstoneScreenHandler) {
            return handler.getSlot(0).hasStack() && handler.getSlot(1).hasStack();
        }
        if (handler instanceof SmithingScreenHandler smithing) {
            return !smithing.isValidIngredient(stack);
        }
        int inputCount = switch (handler) {
            case CartographyTableScreenHandler ignored -> 2;
            case LoomScreenHandler ignored -> 3;
            case BrewingStandScreenHandler ignored -> 5;
            default -> 0;
        };
        for (int i = 0; i < inputCount; i++) {
            if (handler.getSlot(i).canInsert(stack)) {
                return false;
            }
        }
        return inputCount > 0;
    }

    private static boolean returnsInputInForwardOrder(ScreenHandler handler, Slot slot) {
        return !isRepeatableResult(handler, slot) && (handler instanceof CraftingScreenHandler
                || handler instanceof PlayerScreenHandler || handler instanceof StonecutterScreenHandler
                || handler instanceof MerchantScreenHandler || handler instanceof LoomScreenHandler
                || handler instanceof CartographyTableScreenHandler || handler instanceof SmithingScreenHandler
                || (slot.id < 2 && (handler instanceof AbstractFurnaceScreenHandler
                        || handler instanceof AnvilScreenHandler || handler instanceof GrindstoneScreenHandler)));
    }

    private static boolean moveAllMatchingStacksByQuickMove(
            HandledScreen<?> screen,
            Slot hoveredSlot,
            boolean sourceFromPlayerStorage
    ) {
        ItemStack template = hoveredSlot.getStack().copy();
        if (template.isEmpty()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ScreenHandler handler = screen.getScreenHandler();
        List<Slot> sourceSlots = snapshotMatchingSlots(
                handler.slots,
                hoveredSlot,
                slot -> isMatchingSourceSlot(slot, template, sourceFromPlayerStorage, client, handler)
        );
        return moveMatchingSlots(
                sourceSlots,
                slot -> isMatchingSourceSlot(slot, template, sourceFromPlayerStorage, client, handler),
                slot -> quickMoveSlot(screen, slot)
        );
    }

    private static boolean moveAllMatchingStacksToPlayerMainInventory(HandledScreen<?> screen, Slot hoveredSlot) {
        ItemStack template = hoveredSlot.getStack().copy();
        if (template.isEmpty()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ScreenHandler handler = screen.getScreenHandler();
        List<Slot> sourceSlots = snapshotMatchingSlots(
                handler.slots,
                hoveredSlot,
                slot -> isMatchingSourceSlot(slot, template, false, client, handler)
        );
        if (sourceSlots.isEmpty()) {
            return false;
        }
        List<Integer> targetSlotIds = getPlayerPreferredStorageSlotIds(handler);
        for (Slot sourceSlot : sourceSlots) {
            if (!hasSpaceInTargetSlots(handler, template, targetSlotIds)) {
                return true;
            }
            moveOneSourceStackToTargetSlots(screen, sourceSlot.id, template, targetSlotIds, false);
        }
        return true;
    }

    private static boolean moveHoveredSlotRetainingOne(
            HandledScreen<?> screen,
            Slot hoveredSlot,
            boolean sourceFromPlayerStorage
    ) {
        ScreenHandler handler = screen.getScreenHandler();
        if (!canMoveFromPlayerStorage(handler)) {
            return false;
        }

        ItemStack template = hoveredSlot.getStack().copy();
        if (template.isEmpty()) {
            return false;
        }

        List<Integer> targetSlotIds = sourceFromPlayerStorage ? getContainerPreferredSlotIds(handler)
                : getPlayerPreferredStorageSlotIds(handler);
        if (targetSlotIds.isEmpty()) {
            return false;
        }
        if (!hasSpaceInTargetSlots(handler, template, targetSlotIds)) {
            return false;
        }

        return moveOneSourceStackToTargetSlots(screen, hoveredSlot.id, template, targetSlotIds, true);
    }

    private static boolean moveAllMatchingPlayerStorageStacksByQuickMove(HandledScreen<?> screen, Slot hoveredSlot) {
        ItemStack template = hoveredSlot.getStack().copy();
        if (template.isEmpty()) {
            return false;
        }

        boolean sourceFromHotbar = isPlayerHotbarSlot(hoveredSlot);
        MinecraftClient client = MinecraftClient.getInstance();
        ScreenHandler handler = screen.getScreenHandler();
        List<Slot> sourceSlots = snapshotMatchingSlots(
                handler.slots,
                hoveredSlot,
                slot -> isMatchingPlayerStorageSideSlot(slot, template, sourceFromHotbar, client, handler)
        );
        return moveMatchingSlots(
                sourceSlots,
                slot -> isMatchingPlayerStorageSideSlot(slot, template, sourceFromHotbar, client, handler),
                slot -> quickMoveSlot(screen, slot)
        );
    }

    private static boolean belongsToSourceRegion(Slot slot, boolean sourceFromPlayerStorage) {
        return sourceFromPlayerStorage == isPlayerStorageSlot(slot);
    }

    private static boolean belongsToPlayerStorageSide(Slot slot, boolean sourceFromHotbar) {
        return sourceFromHotbar ? isPlayerHotbarSlot(slot) : isPlayerMainInventorySlot(slot);
    }

    private static boolean isMatchingSourceSlot(
            Slot slot,
            ItemStack template,
            boolean sourceFromPlayerStorage,
            MinecraftClient client,
            ScreenHandler handler
    ) {
        return isMatchingTransferCandidate(slot, template, client, handler) && belongsToSourceRegion(
                slot,
                sourceFromPlayerStorage
        );
    }

    private static boolean isMatchingPlayerStorageSideSlot(
            Slot slot,
            ItemStack template,
            boolean sourceFromHotbar,
            MinecraftClient client,
            ScreenHandler handler
    ) {
        return isMatchingTransferCandidate(slot, template, client, handler) && belongsToPlayerStorageSide(
                slot,
                sourceFromHotbar
        );
    }

    private static boolean isMatchingCreativePlayerStorageSideSlot(
            Slot slot,
            ItemStack template,
            boolean sourceFromHotbar,
            MinecraftClient client,
            ScreenHandler handler
    ) {
        return isMatchingTransferCandidate(slot, template, client, handler) && belongsToCreativePlayerStorageSide(
                slot,
                sourceFromHotbar
        );
    }

    private static boolean isMatchingTransferCandidate(
            Slot slot,
            ItemStack template,
            MinecraftClient client,
            ScreenHandler handler
    ) {
        return client.player != null && isVisibleSlot(slot) && slot.hasStack() && !QuickContainerLock.isLockedSlot(
                handler,
                slot
        ) && slot.canTakeItems(client.player) && ItemStack.areItemsAndComponentsEqual(slot.getStack(), template);
    }

    private static List<Slot> snapshotMatchingSlots(List<Slot> slots, Slot hoveredSlot, Predicate<Slot> matcher) {
        List<Slot> matchingSlots = new ArrayList<>();
        addMatchingSlotIfNeeded(hoveredSlot, matcher, matchingSlots);

        for (Slot slot : slots) {
            if (slot == hoveredSlot) {
                continue;
            }
            addMatchingSlotIfNeeded(slot, matcher, matchingSlots);
        }

        return matchingSlots;
    }

    private static void addMatchingSlotIfNeeded(Slot slot, Predicate<Slot> matcher, List<Slot> matchingSlots) {
        if (matcher.test(slot)) {
            matchingSlots.add(slot);
        }
    }

    private static boolean moveMatchingSlots(
            List<Slot> sourceSlots,
            Predicate<Slot> stillMatches,
            Consumer<Slot> moveAction
    ) {
        if (sourceSlots.isEmpty()) {
            return false;
        }

        for (Slot slot : sourceSlots) {
            if (stillMatches.test(slot)) {
                moveAction.accept(slot);
            }
        }

        return true;
    }

    private static List<Slot> findHoveredSlotsAlongPath(HandledScreen<?> screen, double mouseX, double mouseY) {
        if (!hasLastMousePosition) {
            Slot slot = findHoveredSlot(screen, mouseX, mouseY);
            return slot == null ? List.of() : List.of(slot);
        }

        double deltaX = mouseX - lastMouseX;
        double deltaY = mouseY - lastMouseY;
        int steps = Math.max(1, (int) (Math.max(Math.abs(deltaX), Math.abs(deltaY)) / SLIDE_SAMPLE_STEP));
        Set<Slot> slots = new LinkedHashSet<>();

        for (int i = 0; i <= steps; i++) {
            double sampleX = lastMouseX + deltaX * i / steps;
            double sampleY = lastMouseY + deltaY * i / steps;
            Slot slot = findHoveredSlot(screen, sampleX, sampleY);
            if (slot != null) {
                slots.add(slot);
            }
        }

        return new ArrayList<>(slots);
    }

    private static boolean moveOneSourceStackToTargetSlots(
            HandledScreen<?> screen,
            int sourceSlotId,
            ItemStack template,
            List<Integer> targetSlotIds,
            boolean leaveOneInSource
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (!handler.getCursorStack().isEmpty()) {
            return false;
        }
        Slot sourceSlot = handler.getSlot(sourceSlotId);
        if (!isVisibleSlot(sourceSlot) || QuickContainerLock.isLockedSlot(handler, sourceSlot) || !sourceSlot.hasStack()
                || !sourceSlot.canTakeItems(client.player)) {
            return false;
        }
        if (!ItemStack.areItemsAndComponentsEqual(sourceSlot.getStack(), template)) {
            return false;
        }
        if (leaveOneInSource && !canLeaveOneInSource(handler, sourceSlot)) {
            return false;
        }
        if (leaveOneInSource && sourceSlot.getStack().getCount() <= 1) {
            return false;
        }

        clickSlot(screen, sourceSlotId, 0, SlotActionType.PICKUP);
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }
        if (leaveOneInSource) {
            clickSlot(screen, sourceSlotId, 1, SlotActionType.PICKUP);
        }

        int cursorCountBeforeFill = handler.getCursorStack().getCount();
        fillCursorIntoTargetSlots(screen, targetSlotIds, false);
        fillCursorIntoTargetSlots(screen, targetSlotIds, true);
        boolean moved = handler.getCursorStack().getCount() < cursorCountBeforeFill;

        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(screen, sourceSlotId, 0, SlotActionType.PICKUP);
        }
        return moved;
    }

    private static boolean moveOneSourceItemToTargetSlots(
            HandledScreen<?> screen,
            int sourceSlotId,
            ItemStack template,
            List<Integer> targetSlotIds
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null || targetSlotIds.isEmpty()) {
            return false;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (!handler.getCursorStack().isEmpty()) {
            return false;
        }

        Slot sourceSlot = handler.getSlot(sourceSlotId);
        if (!isMatchingTransferCandidate(sourceSlot, template, client, handler)) {
            return false;
        }

        if (QuickShulkerClickSafety.isBundlingConflict(template)) {
            int emptyTargetSlot = -1;
            for (int targetSlotId : targetSlotIds) {
                Slot targetSlot = handler.getSlot(targetSlotId);
                if (!isVisibleSlot(targetSlot) || QuickContainerLock.isLockedSlot(handler, targetSlot) || !targetSlot
                        .canInsert(template) || (targetSlot.hasStack() && (!ItemStack.areItemsAndComponentsEqual(
                                targetSlot.getStack(),
                                template
                        ) || targetSlot.getStack().getCount() >= targetSlot.getStack().getMaxCount()))) {
                    continue;
                }
                if (!targetSlot.hasStack()) {
                    emptyTargetSlot = targetSlotId;
                    break;
                }
                if (QuickShulkerClickSafety.moveExactAmountToSlot(handler, sourceSlotId, targetSlotId, 1, client) > 0) {
                    return true;
                }
            }
            if (emptyTargetSlot == -1) {
                return false;
            }
            // 空目标沿用原版单件放置，且不再尝试可能被第三方接管的其它目标。
            targetSlotIds = List.of(emptyTargetSlot);
        }

        clickSlot(screen, sourceSlotId, 1, SlotActionType.PICKUP);
        if (handler.getCursorStack().isEmpty()) {
            return false;
        }

        int cursorCount = handler.getCursorStack().getCount();
        boolean moved = false;
        for (int targetSlotId : targetSlotIds) {
            Slot targetSlot = handler.getSlot(targetSlotId);
            ItemStack cursorStack = handler.getCursorStack();
            if (!isVisibleSlot(targetSlot) || QuickContainerLock.isLockedSlot(handler, targetSlot) || !targetSlot
                    .canInsert(cursorStack) || (targetSlot.hasStack() && (!ItemStack.areItemsAndComponentsEqual(
                            targetSlot.getStack(),
                            cursorStack
                    ) || targetSlot.getStack().getCount() >= targetSlot.getStack().getMaxCount()))) {
                continue;
            }

            clickSlot(screen, targetSlotId, 1, SlotActionType.PICKUP);
            if (handler.getCursorStack().isEmpty() || handler.getCursorStack().getCount() < cursorCount) {
                moved = true;
                break;
            }
        }

        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(screen, sourceSlotId, 0, SlotActionType.PICKUP);
        }
        return moved;
    }

    private static void fillCursorIntoTargetSlots(
            HandledScreen<?> screen,
            List<Integer> targetSlotIds,
            boolean emptySlotsOnly
    ) {
        ScreenHandler handler = screen.getScreenHandler();
        for (int targetSlotId : targetSlotIds) {
            ItemStack cursorStack = handler.getCursorStack();
            if (cursorStack.isEmpty()) {
                return;
            }

            Slot targetSlot = handler.getSlot(targetSlotId);
            if (!isVisibleSlot(targetSlot) || QuickContainerLock.isLockedSlot(handler, targetSlot) || !targetSlot
                    .canInsert(cursorStack)) {
                continue;
            }

            if (emptySlotsOnly) {
                if (targetSlot.hasStack()) {
                    continue;
                }
            } else {
                if (!targetSlot.hasStack()) {
                    continue;
                }
                if (!ItemStack.areItemsAndComponentsEqual(targetSlot.getStack(), cursorStack)) {
                    continue;
                }
                if (targetSlot.getStack().getCount() >= targetSlot.getStack().getMaxCount()) {
                    continue;
                }
            }

            clickSlot(screen, targetSlotId, 0, SlotActionType.PICKUP);
        }
    }

    private static boolean hasSpaceInTargetSlots(
            ScreenHandler handler,
            ItemStack template,
            List<Integer> targetSlotIds
    ) {
        for (int targetSlotId : targetSlotIds) {
            Slot targetSlot = handler.getSlot(targetSlotId);
            if (!isVisibleSlot(targetSlot) || QuickContainerLock.isLockedSlot(handler, targetSlot) || !targetSlot
                    .canInsert(template)) {
                continue;
            }
            if (!targetSlot.hasStack()) {
                return true;
            }
            if (ItemStack.areItemsAndComponentsEqual(targetSlot.getStack(), template) && targetSlot.getStack()
                    .getCount() < targetSlot.getStack().getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    // 只对能放回同类物品的普通槽位保留 1 个，避免影响产物槽等特殊槽位。
    private static boolean canLeaveOneInSource(ScreenHandler handler, Slot sourceSlot) {
        ItemStack stack = sourceSlot.getStack();
        return handler.getCursorStack().isEmpty() && sourceSlot.hasStack() && !QuickContainerLock.isLockedSlot(
                handler,
                sourceSlot
        ) && !stack.isEmpty() && stack.getMaxCount() > 1 && sourceSlot.canInsert(stack);
    }

    private static List<Integer> getContainerPreferredSlotIds(ScreenHandler handler) {
        List<Slot> targetSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || isPlayerStorageSlot(slot) || QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            targetSlots.add(slot);
        }

        targetSlots.sort(
                Comparator.comparingInt((Slot slot) -> slot.y).thenComparingInt(slot -> slot.x).thenComparingInt(
                        slot -> slot.id
                )
        );

        return targetSlots.stream().map(slot -> slot.id).toList();
    }

    private static List<Integer> getPlayerPreferredStorageSlotIds(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler) {
            // 与普通箱子原版 Shift 取出的槽位顺序一致。
            return handler.slots.reversed().stream().filter(
                    slot -> isPlayerStorageSlot(slot) && isVisibleSlot(slot) && !QuickContainerLock.isLockedSlot(
                            handler,
                            slot
                    )
            ).map(slot -> slot.id).toList();
        }
        List<Integer> targetSlotIds = new ArrayList<>(36);
        targetSlotIds.addAll(getPlayerStorageSlotIdsByRange(handler, 9, 35));
        targetSlotIds.addAll(getPlayerStorageSlotIdsByRange(handler, 0, 8));
        return targetSlotIds;
    }

    private static List<Integer> getOtherInventoryTargetSlotIds(ScreenHandler handler, Slot sourceSlot) {
        if (!canMoveFromPlayerStorage(handler)) {
            return List.of();
        }

        return isPlayerStorageSlot(sourceSlot) ? getContainerPreferredSlotIds(handler)
                : getPlayerPreferredStorageSlotIds(handler);
    }

    private static List<Integer> getPreferredTargetSlotIds(ScreenHandler handler, Slot preferredSlot) {
        List<Integer> targetSlotIds = new ArrayList<>(
                isPlayerStorageSlot(preferredSlot) ? getPlayerPreferredStorageSlotIds(handler)
                        : getContainerPreferredSlotIds(handler)
        );
        targetSlotIds.remove(Integer.valueOf(preferredSlot.id));
        targetSlotIds.add(0, preferredSlot.id);
        return targetSlotIds;
    }

    private static List<Integer> getPlayerStorageSlotIdsByRange(
            ScreenHandler handler,
            int minInventoryIndex,
            int maxInventoryIndex
    ) {
        List<Slot> targetSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || !isPlayerStorageSlotInRange(slot, minInventoryIndex, maxInventoryIndex)
                    || QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            targetSlots.add(slot);
        }

        targetSlots.sort(
                Comparator.comparingInt((Slot slot) -> slot.y).thenComparingInt(slot -> slot.x).thenComparingInt(
                        slot -> slot.id
                )
        );

        return targetSlots.stream().map(slot -> slot.id).toList();
    }

    private static void clickSlot(HandledScreen<?> screen, int slotId, int button, SlotActionType actionType) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        client.interactionManager.clickSlot(
                screen.getScreenHandler().syncId,
                slotId,
                button,
                actionType,
                client.player
        );
    }

    private static boolean isPlayerStorageSlot(Slot slot) {
        if (!(slot.inventory instanceof PlayerInventory)) {
            return false;
        }

        int inventoryIndex = slot.getIndex();
        return inventoryIndex >= 0 && inventoryIndex <= 35;
    }

    private static boolean isPlayerStorageSlotInRange(Slot slot, int minInventoryIndex, int maxInventoryIndex) {
        if (!(slot.inventory instanceof PlayerInventory)) {
            return false;
        }

        int inventoryIndex = slot.getIndex();
        return inventoryIndex >= minInventoryIndex && inventoryIndex <= maxInventoryIndex;
    }

    private static boolean isPlayerMainInventorySlot(Slot slot) {
        return isPlayerStorageSlotInRange(slot, 9, 35);
    }

    private static boolean isPlayerHotbarSlot(Slot slot) {
        return isPlayerStorageSlotInRange(slot, 0, 8);
    }

    private static boolean canMoveFromPlayerStorage(ScreenHandler handler) {
        // 玩家自身 2x2 和工作台 3x3 都不算“目标容器”，不能按背包 -> 容器处理。
        return !(handler instanceof PlayerScreenHandler) && !(handler instanceof CraftingScreenHandler);
    }

    private static Slot findHoveredSlot(HandledScreen<?> screen, double mouseX, double mouseY) {
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        int guiLeft = accessor.quickcraft$getGuiLeft();
        int guiTop = accessor.quickcraft$getGuiTop();

        for (Slot slot : screen.getScreenHandler().slots) {
            if (!isVisibleSlot(slot)) {
                continue;
            }
            if (isMouseOverSlot(slot, guiLeft, guiTop, mouseX, mouseY)) {
                return slot;
            }
        }

        return null;
    }

    private static boolean isTextInputFocused(HandledScreen<?> screen) {
        Element focused = screen.getFocused();
        return focused instanceof TextFieldWidget;
    }

    private static boolean isVisibleSlot(Slot slot) {
        return slot.isEnabled() && slot.x >= 0 && slot.y >= 0;
    }

    private static boolean isMouseOverSlot(Slot slot, int guiLeft, int guiTop, double mouseX, double mouseY) {
        int slotLeft = guiLeft + slot.x;
        int slotTop = guiTop + slot.y;
        return mouseX >= slotLeft && mouseX < slotLeft + SLOT_SIZE && mouseY >= slotTop && mouseY < slotTop + SLOT_SIZE;
    }

    private static double getMouseX(MinecraftClient client) {
        return client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
    }

    private static double getMouseY(MinecraftClient client) {
        return client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
    }

    private enum TransferMode {
        NONE, MATCHING, MATCHING_RETAIN_ONE, SLOT
    }

    private record SlotKey(ScreenHandler handler, int slotId) {
    }
}
