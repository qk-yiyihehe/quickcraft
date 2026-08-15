package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.CreativeInventoryScreenInvoker;
import com.yiyihehe.quickcraft.mixin.CreativeSlotAccessor;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    private static AbstractContainerScreen<?> activeScreen;
    private static TransferMode activeMode = TransferMode.NONE;
    private static SlotKey lastHoveredSlotKey;
    private static boolean hasLastMousePosition;
    private static double lastMouseX;
    private static double lastMouseY;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean handleQuickTransferHotkey() {
        Minecraft client = Minecraft.getInstance();
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen) || !canUseQuickTransfer(client, screen)) {
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
        Minecraft client = Minecraft.getInstance();
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen) || !canUseQuickTransfer(client, screen)) {
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
        Minecraft client = Minecraft.getInstance();
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen) || !canUseQuickTransfer(client, screen)) {
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

    public static boolean handleScrollTransfer(AbstractContainerScreen<?> screen,
                                               double mouseX,
                                               double mouseY,
                                               double verticalAmount) {
        Minecraft client = Minecraft.getInstance();
        if (verticalAmount == 0
                || screen instanceof CreativeModeInventoryScreen
                || !canUseScrollTransfer(client, screen)) {
            return false;
        }

        Slot hoveredSlot = findHoveredSlot(screen, mouseX, mouseY);
        if (!canUseScrollTransferSlot(screen, hoveredSlot, client)) {
            return false;
        }

        boolean moveToOtherInventory = verticalAmount > 0;
        if (client.hasShiftDown()) {
            return moveFullStackByScroll(screen, hoveredSlot, moveToOtherInventory);
        }
        if (client.hasAltDown()) {
            return moveMatchingStacksByScroll(screen, hoveredSlot, moveToOtherInventory);
        }
        return moveSingleItemByScroll(screen, hoveredSlot, moveToOtherInventory);
    }

    private void onClientTick(Minecraft client) {
        TransferMode mode = getHeldTransferMode();
        if (mode == TransferMode.NONE) {
            resetHoldGesture();
            return;
        }

        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen) || !canUseQuickTransfer(client, screen)) {
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

    private static boolean canUseQuickTransfer(Minecraft client, AbstractContainerScreen<?> screen) {
        return QuickCraftConfigs.isQuickTransferEnabled()
                && client.player != null
                && client.gameMode != null
                && !isTextInputFocused(screen)
                && screen.getMenu().getCarried().isEmpty();
    }

    private static boolean canUseScrollTransfer(Minecraft client, AbstractContainerScreen<?> screen) {
        return QuickCraftConfigs.isScrollTransferEnabled()
                && client.player != null
                && client.gameMode != null
                && !isTextInputFocused(screen)
                && screen.getMenu().getCarried().isEmpty();
    }

    private static boolean canUseScrollTransferSlot(AbstractContainerScreen<?> screen,
                                                    Slot slot,
                                                    Minecraft client) {
        return slot != null
                && isVisibleSlot(slot)
                && slot.hasItem()
                && slot.mayPickup(client.player)
                && !QuickContainerLock.isLockedSlot(screen.getMenu(), slot);
    }

    private static boolean moveFullStackByScroll(AbstractContainerScreen<?> screen,
                                                 Slot hoveredSlot,
                                                 boolean moveToOtherInventory) {
        if (moveToOtherInventory) {
            return quickMoveHoveredSlot(screen, hoveredSlot, isPlayerStorageSlot(hoveredSlot));
        }

        Slot sourceSlot = findFirstMatchingOtherInventorySlot(screen, hoveredSlot);
        return sourceSlot != null && quickMoveHoveredSlot(screen, sourceSlot, isPlayerStorageSlot(sourceSlot));
    }

    private static boolean moveMatchingStacksByScroll(AbstractContainerScreen<?> screen,
                                                       Slot hoveredSlot,
                                                       boolean moveToOtherInventory) {
        if (moveToOtherInventory) {
            return processHoveredSlot(screen, hoveredSlot, TransferMode.MATCHING);
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (!canMoveFromPlayerStorage(handler)) {
            return false;
        }

        return isPlayerStorageSlot(hoveredSlot)
                ? moveAllMatchingStacksToPlayerMainInventory(screen, hoveredSlot)
                : moveAllMatchingStacksByQuickMove(screen, hoveredSlot, true);
    }

    private static boolean moveSingleItemByScroll(AbstractContainerScreen<?> screen,
                                                   Slot hoveredSlot,
                                                   boolean moveToOtherInventory) {
        if (canMoveFromPlayerStorage(screen.getMenu())
                && isSingleShulkerBox(hoveredSlot.getItem())) {
            // QuickShulker 将单个潜影盒右键放入空槽识别为解包；快速移动可避免触发该物品回调。
            return moveFullStackByScroll(screen, hoveredSlot, moveToOtherInventory);
        }

        if (moveToOtherInventory) {
            List<Integer> targetSlotIds = getOtherInventoryTargetSlotIds(screen.getMenu(), hoveredSlot);
            return moveOneSourceItemToTargetSlots(screen, hoveredSlot.index, hoveredSlot.getItem().copy(), targetSlotIds);
        }

        Slot sourceSlot = findFirstMatchingOtherInventorySlot(screen, hoveredSlot);
        if (sourceSlot == null) {
            return false;
        }

        List<Integer> targetSlotIds = getPreferredTargetSlotIds(screen.getMenu(), hoveredSlot);
        return moveOneSourceItemToTargetSlots(screen, sourceSlot.index, hoveredSlot.getItem().copy(), targetSlotIds);
    }

    private static boolean isSingleShulkerBox(ItemStack stack) {
        return stack.getCount() == 1
                && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static Slot findFirstMatchingOtherInventorySlot(AbstractContainerScreen<?> screen, Slot hoveredSlot) {
        AbstractContainerMenu handler = screen.getMenu();
        if (!canMoveFromPlayerStorage(handler)) {
            return null;
        }

        Minecraft client = Minecraft.getInstance();
        boolean sourceFromPlayerStorage = !isPlayerStorageSlot(hoveredSlot);
        ItemStack template = hoveredSlot.getItem();
        for (Slot slot : handler.slots) {
            if (isMatchingSourceSlot(slot, template, sourceFromPlayerStorage, client, handler)) {
                return slot;
            }
        }
        return null;
    }

    private static TransferMode getHeldTransferMode() {
        if (QuickCraftConfigs.Hotkeys.QUICK_TRANSFER_RETAIN_ONE.getKeybind().isKeybindHeld()) {
            return TransferMode.MATCHING_RETAIN_ONE;
        }
        if (QuickCraftConfigs.Hotkeys.SLOT_QUICK_TRANSFER.getKeybind().isKeybindHeld()) {
            return TransferMode.SLOT;
        }
        if (QuickCraftConfigs.Hotkeys.QUICK_TRANSFER.getKeybind().isKeybindHeld()) {
            return TransferMode.MATCHING;
        }
        return TransferMode.NONE;
    }

    private static void ensureHoldGesture(AbstractContainerScreen<?> screen, TransferMode mode) {
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

    private static boolean processHoveredSlot(AbstractContainerScreen<?> screen,
                                              Slot hoveredSlot,
                                              TransferMode mode) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !isVisibleSlot(hoveredSlot)) {
            return false;
        }

        if (screen instanceof CreativeModeInventoryScreen creativeScreen) {
            return processCreativeHoveredSlot(creativeScreen, hoveredSlot, mode);
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (QuickContainerLock.isLockedSlot(handler, hoveredSlot)) {
            return false;
        }

        if (!hoveredSlot.hasItem() || !hoveredSlot.mayPickup(client.player)) {
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

    private static boolean processCreativeHoveredSlot(CreativeModeInventoryScreen screen,
                                                      Slot hoveredSlot,
                                                      TransferMode mode) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null
                || !isCreativePlayerStorageSlot(hoveredSlot)
                || QuickContainerLock.isLockedSlot(client.player.inventoryMenu, unwrapCreativeSlot(hoveredSlot))
                || !hoveredSlot.hasItem()
                || !hoveredSlot.mayPickup(client.player)) {
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

    private static boolean quickMoveCreativeSlot(CreativeModeInventoryScreen screen, Slot slot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return false;
        }

        if (screen.isInventoryOpen()) {
            Slot effectiveSlot = unwrapCreativeSlot(slot);
            if (QuickContainerLock.isLockedSlot(client.player.inventoryMenu, effectiveSlot)) {
                return false;
            }
            client.gameMode.handleContainerInput(
                    client.player.inventoryMenu.containerId,
                    effectiveSlot.index,
                    0,
                    ContainerInput.QUICK_MOVE,
                    client.player
            );
            return true;
        }

        ((CreativeInventoryScreenInvoker) screen)
                .quickcraft$invokeOnMouseClick(slot, slot.index, 0, ContainerInput.QUICK_MOVE);
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

    private static boolean moveAllMatchingCreativePlayerStorageStacksByQuickMove(CreativeModeInventoryScreen screen,
                                                                                 Slot hoveredSlot) {
        ItemStack template = hoveredSlot.getItem().copy();
        if (template.isEmpty()) {
            return false;
        }

        boolean sourceFromHotbar = isCreativePlayerHotbarSlot(hoveredSlot);
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }
        AbstractContainerMenu handler = client.player.inventoryMenu;
        List<Slot> sourceSlots = snapshotMatchingSlots(
                screen.getMenu().slots,
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

    private static SlotKey createSlotKey(AbstractContainerScreen<?> screen, Slot slot) {
        if (screen instanceof CreativeModeInventoryScreen) {
            Slot effectiveSlot = unwrapCreativeSlot(slot);
            return new SlotKey(screen.getMenu(), effectiveSlot.index);
        }
        return new SlotKey(screen.getMenu(), slot.index);
    }

    private static boolean quickMoveHoveredSlot(AbstractContainerScreen<?> screen,
                                                Slot hoveredSlot,
                                                boolean sourceFromPlayerStorage) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return false;
        }

        if (!isVisibleSlot(hoveredSlot)
                || !hoveredSlot.hasItem()
                || !hoveredSlot.mayPickup(client.player)
                || QuickContainerLock.isLockedSlot(screen.getMenu(), hoveredSlot)
                || !belongsToSourceRegion(hoveredSlot, sourceFromPlayerStorage)) {
            return false;
        }

        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                hoveredSlot.index,
                0,
                ContainerInput.QUICK_MOVE,
                client.player
        );
        return true;
    }

    private static void quickMoveSlot(AbstractContainerScreen<?> screen, Slot slot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                slot.index,
                0,
                ContainerInput.QUICK_MOVE,
                client.player
        );
    }

    private static boolean moveAllMatchingStacksByQuickMove(AbstractContainerScreen<?> screen,
                                                            Slot hoveredSlot,
                                                            boolean sourceFromPlayerStorage) {
        ItemStack template = hoveredSlot.getItem().copy();
        if (template.isEmpty()) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        AbstractContainerMenu handler = screen.getMenu();
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

    private static boolean moveAllMatchingStacksToPlayerMainInventory(AbstractContainerScreen<?> screen, Slot hoveredSlot) {
        ItemStack template = hoveredSlot.getItem().copy();
        if (template.isEmpty()) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        AbstractContainerMenu handler = screen.getMenu();
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
            moveOneSourceStackToTargetSlots(
                    screen,
                    sourceSlot.index,
                    template,
                    targetSlotIds,
                    false
            );
        }
        return true;
    }

    private static boolean moveHoveredSlotRetainingOne(AbstractContainerScreen<?> screen,
                                                       Slot hoveredSlot,
                                                       boolean sourceFromPlayerStorage) {
        AbstractContainerMenu handler = screen.getMenu();
        if (!canMoveFromPlayerStorage(handler)) {
            return false;
        }

        ItemStack template = hoveredSlot.getItem().copy();
        if (template.isEmpty()) {
            return false;
        }

        List<Integer> targetSlotIds = sourceFromPlayerStorage
                ? getContainerPreferredSlotIds(handler)
                : getPlayerPreferredStorageSlotIds(handler);
        if (targetSlotIds.isEmpty()) {
            return false;
        }
        if (!hasSpaceInTargetSlots(handler, template, targetSlotIds)) {
            return false;
        }

        return moveOneSourceStackToTargetSlots(
                screen,
                hoveredSlot.index,
                template,
                targetSlotIds,
                true
        );
    }

    private static boolean moveAllMatchingPlayerStorageStacksByQuickMove(AbstractContainerScreen<?> screen, Slot hoveredSlot) {
        ItemStack template = hoveredSlot.getItem().copy();
        if (template.isEmpty()) {
            return false;
        }

        boolean sourceFromHotbar = isPlayerHotbarSlot(hoveredSlot);
        Minecraft client = Minecraft.getInstance();
        AbstractContainerMenu handler = screen.getMenu();
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

    private static boolean isMatchingSourceSlot(Slot slot,
                                                ItemStack template,
                                                boolean sourceFromPlayerStorage,
                                                Minecraft client,
                                                AbstractContainerMenu handler) {
        return isMatchingTransferCandidate(slot, template, client, handler)
                && belongsToSourceRegion(slot, sourceFromPlayerStorage);
    }

    private static boolean isMatchingPlayerStorageSideSlot(Slot slot,
                                                           ItemStack template,
                                                           boolean sourceFromHotbar,
                                                           Minecraft client,
                                                           AbstractContainerMenu handler) {
        return isMatchingTransferCandidate(slot, template, client, handler)
                && belongsToPlayerStorageSide(slot, sourceFromHotbar);
    }

    private static boolean isMatchingCreativePlayerStorageSideSlot(Slot slot,
                                                                   ItemStack template,
                                                                   boolean sourceFromHotbar,
                                                                   Minecraft client,
                                                                   AbstractContainerMenu handler) {
        return isMatchingTransferCandidate(slot, template, client, handler)
                && belongsToCreativePlayerStorageSide(slot, sourceFromHotbar);
    }

    private static boolean isMatchingTransferCandidate(Slot slot,
                                                       ItemStack template,
                                                       Minecraft client,
                                                       AbstractContainerMenu handler) {
        return client.player != null
                && isVisibleSlot(slot)
                && slot.hasItem()
                && !QuickContainerLock.isLockedSlot(handler, slot)
                && slot.mayPickup(client.player)
                && ItemStack.isSameItemSameComponents(slot.getItem(), template);
    }

    private static List<Slot> snapshotMatchingSlots(List<Slot> slots,
                                                    Slot hoveredSlot,
                                                    Predicate<Slot> matcher) {
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

    private static void addMatchingSlotIfNeeded(Slot slot,
                                                Predicate<Slot> matcher,
                                                List<Slot> matchingSlots) {
        if (matcher.test(slot)) {
            matchingSlots.add(slot);
        }
    }

    private static boolean moveMatchingSlots(List<Slot> sourceSlots,
                                             Predicate<Slot> stillMatches,
                                             Consumer<Slot> moveAction) {
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

    private static List<Slot> findHoveredSlotsAlongPath(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
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

    private static boolean moveOneSourceStackToTargetSlots(AbstractContainerScreen<?> screen,
                                                           int sourceSlotId,
                                                           ItemStack template,
                                                           List<Integer> targetSlotIds,
                                                           boolean leaveOneInSource) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return false;
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (!handler.getCarried().isEmpty()) {
            return false;
        }
        Slot sourceSlot = handler.getSlot(sourceSlotId);
        if (!isVisibleSlot(sourceSlot)
                || QuickContainerLock.isLockedSlot(handler, sourceSlot)
                || !sourceSlot.hasItem()
                || !sourceSlot.mayPickup(client.player)) {
            return false;
        }
        if (!ItemStack.isSameItemSameComponents(sourceSlot.getItem(), template)) {
            return false;
        }
        if (leaveOneInSource && !canLeaveOneInSource(handler, sourceSlot)) {
            return false;
        }
        if (leaveOneInSource && sourceSlot.getItem().getCount() <= 1) {
            return false;
        }

        clickSlot(screen, sourceSlotId, 0, ContainerInput.PICKUP);
        if (handler.getCarried().isEmpty()) {
            return false;
        }
        if (leaveOneInSource) {
            clickSlot(screen, sourceSlotId, 1, ContainerInput.PICKUP);
        }

        int cursorCountBeforeFill = handler.getCarried().getCount();
        fillCursorIntoTargetSlots(screen, targetSlotIds, false);
        fillCursorIntoTargetSlots(screen, targetSlotIds, true);
        boolean moved = handler.getCarried().getCount() < cursorCountBeforeFill;

        if (!handler.getCarried().isEmpty()) {
            clickSlot(screen, sourceSlotId, 0, ContainerInput.PICKUP);
        }
        return moved;
    }

    private static boolean moveOneSourceItemToTargetSlots(AbstractContainerScreen<?> screen,
                                                          int sourceSlotId,
                                                          ItemStack template,
                                                          List<Integer> targetSlotIds) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null || targetSlotIds.isEmpty()) {
            return false;
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (!handler.getCarried().isEmpty()) {
            return false;
        }

        Slot sourceSlot = handler.getSlot(sourceSlotId);
        if (!isMatchingTransferCandidate(sourceSlot, template, client, handler)) {
            return false;
        }

        clickSlot(screen, sourceSlotId, 1, ContainerInput.PICKUP);
        if (handler.getCarried().isEmpty()) {
            return false;
        }

        int cursorCount = handler.getCarried().getCount();
        boolean moved = false;
        for (int targetSlotId : targetSlotIds) {
            Slot targetSlot = handler.getSlot(targetSlotId);
            ItemStack cursorStack = handler.getCarried();
            if (!isVisibleSlot(targetSlot)
                    || QuickContainerLock.isLockedSlot(handler, targetSlot)
                    || !targetSlot.mayPlace(cursorStack)
                    || (targetSlot.hasItem()
                    && (!ItemStack.isSameItemSameComponents(targetSlot.getItem(), cursorStack)
                    || targetSlot.getItem().getCount() >= targetSlot.getItem().getMaxStackSize()))) {
                continue;
            }

            clickSlot(screen, targetSlotId, 1, ContainerInput.PICKUP);
            if (handler.getCarried().isEmpty() || handler.getCarried().getCount() < cursorCount) {
                moved = true;
                break;
            }
        }

        if (!handler.getCarried().isEmpty()) {
            clickSlot(screen, sourceSlotId, 0, ContainerInput.PICKUP);
        }
        return moved;
    }

    private static void fillCursorIntoTargetSlots(AbstractContainerScreen<?> screen,
                                                  List<Integer> targetSlotIds,
                                                  boolean emptySlotsOnly) {
        AbstractContainerMenu handler = screen.getMenu();
        for (int targetSlotId : targetSlotIds) {
            ItemStack cursorStack = handler.getCarried();
            if (cursorStack.isEmpty()) {
                return;
            }

            Slot targetSlot = handler.getSlot(targetSlotId);
            if (!isVisibleSlot(targetSlot)
                    || QuickContainerLock.isLockedSlot(handler, targetSlot)
                    || !targetSlot.mayPlace(cursorStack)) {
                continue;
            }

            if (emptySlotsOnly) {
                if (targetSlot.hasItem()) {
                    continue;
                }
            } else {
                if (!targetSlot.hasItem()) {
                    continue;
                }
                if (!ItemStack.isSameItemSameComponents(targetSlot.getItem(), cursorStack)) {
                    continue;
                }
                if (targetSlot.getItem().getCount() >= targetSlot.getItem().getMaxStackSize()) {
                    continue;
                }
            }

            clickSlot(screen, targetSlotId, 0, ContainerInput.PICKUP);
        }
    }

    private static boolean hasSpaceInTargetSlots(AbstractContainerMenu handler,
                                                 ItemStack template,
                                                 List<Integer> targetSlotIds) {
        for (int targetSlotId : targetSlotIds) {
            Slot targetSlot = handler.getSlot(targetSlotId);
            if (!isVisibleSlot(targetSlot)
                    || QuickContainerLock.isLockedSlot(handler, targetSlot)
                    || !targetSlot.mayPlace(template)) {
                continue;
            }
            if (!targetSlot.hasItem()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(targetSlot.getItem(), template)
                    && targetSlot.getItem().getCount() < targetSlot.getItem().getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    // 只对能放回同类物品的普通槽位保留 1 个，避免影响产物槽等特殊槽位。
    private static boolean canLeaveOneInSource(AbstractContainerMenu handler, Slot sourceSlot) {
        ItemStack stack = sourceSlot.getItem();
        return handler.getCarried().isEmpty()
                && sourceSlot.hasItem()
                && !QuickContainerLock.isLockedSlot(handler, sourceSlot)
                && !stack.isEmpty()
                && stack.getMaxStackSize() > 1
                && sourceSlot.mayPlace(stack);
    }

    private static List<Integer> getContainerPreferredSlotIds(AbstractContainerMenu handler) {
        List<Slot> targetSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot)
                    || isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            targetSlots.add(slot);
        }

        targetSlots.sort(Comparator
                .comparingInt((Slot slot) -> slot.y)
                .thenComparingInt(slot -> slot.x)
                .thenComparingInt(slot -> slot.index));

        return targetSlots.stream()
                .map(slot -> slot.index)
                .toList();
    }

    private static List<Integer> getPlayerPreferredStorageSlotIds(AbstractContainerMenu handler) {
        List<Integer> targetSlotIds = new ArrayList<>(36);
        targetSlotIds.addAll(getPlayerStorageSlotIdsByRange(handler, 9, 35));
        targetSlotIds.addAll(getPlayerStorageSlotIdsByRange(handler, 0, 8));
        return targetSlotIds;
    }

    private static List<Integer> getOtherInventoryTargetSlotIds(AbstractContainerMenu handler, Slot sourceSlot) {
        if (!canMoveFromPlayerStorage(handler)) {
            return List.of();
        }

        return isPlayerStorageSlot(sourceSlot)
                ? getContainerPreferredSlotIds(handler)
                : getPlayerPreferredStorageSlotIds(handler);
    }

    private static List<Integer> getPreferredTargetSlotIds(AbstractContainerMenu handler, Slot preferredSlot) {
        List<Integer> targetSlotIds = new ArrayList<>(isPlayerStorageSlot(preferredSlot)
                ? getPlayerPreferredStorageSlotIds(handler)
                : getContainerPreferredSlotIds(handler));
        targetSlotIds.remove(Integer.valueOf(preferredSlot.index));
        targetSlotIds.add(0, preferredSlot.index);
        return targetSlotIds;
    }

    private static List<Integer> getPlayerStorageSlotIdsByRange(AbstractContainerMenu handler,
                                                                int minInventoryIndex,
                                                                int maxInventoryIndex) {
        List<Slot> targetSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot)
                    || !isPlayerStorageSlotInRange(slot, minInventoryIndex, maxInventoryIndex)
                    || QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            targetSlots.add(slot);
        }

        targetSlots.sort(Comparator
                .comparingInt((Slot slot) -> slot.y)
                .thenComparingInt(slot -> slot.x)
                .thenComparingInt(slot -> slot.index));

        return targetSlots.stream()
                .map(slot -> slot.index)
                .toList();
    }

    private static void clickSlot(AbstractContainerScreen<?> screen, int slotId, int button, ContainerInput actionType) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        client.gameMode.handleContainerInput(
                screen.getMenu().containerId,
                slotId,
                button,
                actionType,
                client.player
        );
    }

    private static boolean isPlayerStorageSlot(Slot slot) {
        if (!(slot.container instanceof Inventory)) {
            return false;
        }

        int inventoryIndex = slot.getContainerSlot();
        return inventoryIndex >= 0 && inventoryIndex <= 35;
    }

    private static boolean isPlayerStorageSlotInRange(Slot slot, int minInventoryIndex, int maxInventoryIndex) {
        if (!(slot.container instanceof Inventory)) {
            return false;
        }

        int inventoryIndex = slot.getContainerSlot();
        return inventoryIndex >= minInventoryIndex && inventoryIndex <= maxInventoryIndex;
    }

    private static boolean isPlayerMainInventorySlot(Slot slot) {
        return isPlayerStorageSlotInRange(slot, 9, 35);
    }

    private static boolean isPlayerHotbarSlot(Slot slot) {
        return isPlayerStorageSlotInRange(slot, 0, 8);
    }

    private static boolean canMoveFromPlayerStorage(AbstractContainerMenu handler) {
        // 玩家自身 2x2 和工作台 3x3 都不算“目标容器”，不能按背包 -> 容器处理。
        return !(handler instanceof InventoryMenu) && !(handler instanceof CraftingMenu);
    }

    private static Slot findHoveredSlot(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        int guiLeft = accessor.quickcraft$getGuiLeft();
        int guiTop = accessor.quickcraft$getGuiTop();

        for (Slot slot : screen.getMenu().slots) {
            if (!isVisibleSlot(slot)) {
                continue;
            }
            if (isMouseOverSlot(slot, guiLeft, guiTop, mouseX, mouseY)) {
                return slot;
            }
        }

        return null;
    }

    private static boolean isTextInputFocused(AbstractContainerScreen<?> screen) {
        GuiEventListener focused = screen.getFocused();
        return focused instanceof EditBox;
    }

    private static boolean isVisibleSlot(Slot slot) {
        return slot.isActive() && slot.x >= 0 && slot.y >= 0;
    }

    private static boolean isMouseOverSlot(Slot slot, int guiLeft, int guiTop, double mouseX, double mouseY) {
        int slotLeft = guiLeft + slot.x;
        int slotTop = guiTop + slot.y;
        return mouseX >= slotLeft
                && mouseX < slotLeft + SLOT_SIZE
                && mouseY >= slotTop
                && mouseY < slotTop + SLOT_SIZE;
    }

    private static double getMouseX(Minecraft client) {
        return client.mouseHandler.getScaledXPos(client.getWindow());
    }

    private static double getMouseY(Minecraft client) {
        return client.mouseHandler.getScaledYPos(client.getWindow());
    }

    private enum TransferMode {
        NONE,
        MATCHING,
        MATCHING_RETAIN_ONE,
        SLOT
    }

    private record SlotKey(AbstractContainerMenu handler, int slotId) {
    }
}
