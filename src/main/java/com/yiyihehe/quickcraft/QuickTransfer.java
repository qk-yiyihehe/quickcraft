package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 可配置组合键同类转移：
 * - 鼠标在玩家主背包/快捷栏：转移同类物品到当前打开容器
 * - 鼠标在容器/合成格等非背包区域：转移同类物品到玩家主背包/快捷栏
 * - 按住快捷键滑过物品时，每个滑过的物品种类触发一次同类转移
 * - 按住格子转移快捷键滑过格子时，每个滑过的格子触发一次原版 Shift 转移
 */
public final class QuickTransfer implements ClientModInitializer {
    private static final int SLOT_SIZE = 18;
    private static final int SLIDE_SAMPLE_STEP = SLOT_SIZE / 2;
    private static final Set<SlotKey> handledSlotsInGesture = new HashSet<>();

    private static HandledScreen<?> activeScreen;
    private static TransferMode activeMode = TransferMode.NONE;
    private static boolean hasLastMousePosition;
    private static double lastMouseX;
    private static double lastMouseY;

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

        boolean handled = processHoveredSlot(screen, hoveredSlot, true, TransferMode.MATCHING);
        hasLastMousePosition = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        return handled;
    }

    public static boolean handleSlotQuickTransferHotkey() {
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

        boolean handled = processHoveredSlot(screen, hoveredSlot, true, TransferMode.SLOT);
        hasLastMousePosition = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        return handled;
    }

    private void onClientTick(MinecraftClient client) {
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

        for (Slot slot : findHoveredSlotsAlongPath(screen, mouseX, mouseY)) {
            processHoveredSlot(screen, slot, true, mode);
        }

        hasLastMousePosition = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    private static boolean canUseQuickTransfer(MinecraftClient client, HandledScreen<?> screen) {
        return QuickCraftConfigs.isQuickTransferEnabled()
                && client.player != null
                && client.interactionManager != null
                && !isTextInputFocused(screen)
                // 创造物品栏没有真实“目标容器”，在这里批量转移只会制造幽灵物品，直接禁用。
                && !(screen instanceof CreativeInventoryScreen)
                && screen.getScreenHandler().getCursorStack().isEmpty();
    }

    private static TransferMode getHeldTransferMode() {
        if (QuickCraftConfigs.Hotkeys.SLOT_QUICK_TRANSFER.getKeybind().isKeybindHeld()) {
            return TransferMode.SLOT;
        }
        if (QuickCraftConfigs.Hotkeys.QUICK_TRANSFER.getKeybind().isKeybindHeld()) {
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
        hasLastMousePosition = false;
        handledSlotsInGesture.clear();
    }

    private static void resetHoldGesture() {
        activeScreen = null;
        activeMode = TransferMode.NONE;
        hasLastMousePosition = false;
        handledSlotsInGesture.clear();
    }

    private static boolean processHoveredSlot(HandledScreen<?> screen,
                                              Slot hoveredSlot,
                                              boolean rememberGestureSlot,
                                              TransferMode mode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !isVisibleSlot(hoveredSlot)
                || !hoveredSlot.hasStack()
                || !hoveredSlot.canTakeItems(client.player)) {
            return false;
        }

        boolean sourceFromPlayerStorage = isPlayerStorageSlot(hoveredSlot);
        if (sourceFromPlayerStorage && mode == TransferMode.MATCHING
                && !canMoveFromPlayerStorage(screen.getScreenHandler())
                && !isPlayerMainInventorySlot(hoveredSlot)
                && !isPlayerHotbarSlot(hoveredSlot)) {
            return false;
        }

        SlotKey key = new SlotKey(screen.getScreenHandler(), hoveredSlot.id);
        if (rememberGestureSlot && !handledSlotsInGesture.add(key)) {
            return false;
        }

        boolean handled;
        if (mode == TransferMode.SLOT) {
            handled = quickMoveHoveredSlot(screen, hoveredSlot, sourceFromPlayerStorage);
        } else if (sourceFromPlayerStorage && !canMoveFromPlayerStorage(screen.getScreenHandler())) {
            // 玩家物品栏界面没有外部容器时，同类滑动按主背包/快捷栏两边互转处理。
            handled = moveAllMatchingPlayerStorageStacksByQuickMove(screen, hoveredSlot);
        } else if (sourceFromPlayerStorage) {
            handled = moveAllMatchingStacksByQuickMove(screen, hoveredSlot, true);
        } else {
            handled = moveAllMatchingStacksToPlayerMainInventory(screen, hoveredSlot);
        }

        return handled;
    }

    private static boolean quickMoveHoveredSlot(HandledScreen<?> screen,
                                                Slot hoveredSlot,
                                                boolean sourceFromPlayerStorage) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        if (!isVisibleSlot(hoveredSlot)
                || !hoveredSlot.hasStack()
                || !hoveredSlot.canTakeItems(client.player)
                || !belongsToSourceRegion(hoveredSlot, sourceFromPlayerStorage)) {
            return false;
        }

        client.interactionManager.clickSlot(
                screen.getScreenHandler().syncId,
                hoveredSlot.id,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );
        return true;
    }

    private static boolean moveAllMatchingStacksByQuickMove(HandledScreen<?> screen,
                                                            Slot hoveredSlot,
                                                            boolean sourceFromPlayerStorage) {
        ItemStack template = hoveredSlot.getStack().copy();
        if (template.isEmpty()) {
            return false;
        }

        List<Integer> sourceSlotIds = snapshotMatchingSourceSlots(screen, hoveredSlot.id, template, sourceFromPlayerStorage);
        if (sourceSlotIds.isEmpty()) {
            return false;
        }
        for (int slotId : sourceSlotIds) {
            quickMoveIfStillMatching(screen, slotId, template, sourceFromPlayerStorage);
        }
        return true;
    }

    private static boolean moveAllMatchingStacksToPlayerMainInventory(HandledScreen<?> screen, Slot hoveredSlot) {
        ItemStack template = hoveredSlot.getStack().copy();
        if (template.isEmpty()) {
            return false;
        }

        ScreenHandler handler = screen.getScreenHandler();
        List<Integer> sourceSlotIds = snapshotMatchingSourceSlots(screen, hoveredSlot.id, template, false);
        if (sourceSlotIds.isEmpty()) {
            return false;
        }
        List<Integer> targetSlotIds = getPlayerPreferredStorageSlotIds(handler);
        for (int slotId : sourceSlotIds) {
            if (!hasSpaceInPlayerStorage(handler, template, targetSlotIds)) {
                return true;
            }
            moveOneSourceStackToPlayerMainInventory(screen, slotId, template, targetSlotIds);
        }
        return true;
    }

    private static boolean moveAllMatchingPlayerStorageStacksByQuickMove(HandledScreen<?> screen, Slot hoveredSlot) {
        ItemStack template = hoveredSlot.getStack().copy();
        if (template.isEmpty()) {
            return false;
        }

        boolean sourceFromHotbar = isPlayerHotbarSlot(hoveredSlot);
        List<Integer> sourceSlotIds = snapshotMatchingPlayerStorageSideSlots(screen, hoveredSlot.id, template, sourceFromHotbar);
        if (sourceSlotIds.isEmpty()) {
            return false;
        }
        for (int slotId : sourceSlotIds) {
            quickMovePlayerStorageSlotIfStillMatching(screen, slotId, template, sourceFromHotbar);
        }
        return true;
    }

    private static List<Integer> snapshotMatchingPlayerStorageSideSlots(HandledScreen<?> screen,
                                                                        int hoveredSlotId,
                                                                        ItemStack template,
                                                                        boolean sourceFromHotbar) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<Integer> sourceSlotIds = new ArrayList<>();

        addMatchingPlayerStorageSideSlotIfNeeded(screen, hoveredSlotId, template, sourceFromHotbar, client, sourceSlotIds);

        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.id == hoveredSlotId) {
                continue;
            }
            addMatchingPlayerStorageSideSlotIfNeeded(screen, slot.id, template, sourceFromHotbar, client, sourceSlotIds);
        }

        return sourceSlotIds;
    }

    private static void addMatchingPlayerStorageSideSlotIfNeeded(HandledScreen<?> screen,
                                                                 int slotId,
                                                                 ItemStack template,
                                                                 boolean sourceFromHotbar,
                                                                 MinecraftClient client,
                                                                 List<Integer> sourceSlotIds) {
        Slot slot = screen.getScreenHandler().getSlot(slotId);
        if (!isVisibleSlot(slot)) {
            return;
        }
        if (!slot.hasStack() || !slot.canTakeItems(client.player)) {
            return;
        }
        if (!belongsToPlayerStorageSide(slot, sourceFromHotbar)) {
            return;
        }
        if (!ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
            return;
        }

        sourceSlotIds.add(slotId);
    }

    private static List<Integer> snapshotMatchingSourceSlots(HandledScreen<?> screen,
                                                             int hoveredSlotId,
                                                             ItemStack template,
                                                             boolean sourceFromPlayerStorage) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<Integer> sourceSlotIds = new ArrayList<>();

        addMatchingSlotIfNeeded(screen, hoveredSlotId, template, sourceFromPlayerStorage, client, sourceSlotIds);

        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.id == hoveredSlotId) {
                continue;
            }
            addMatchingSlotIfNeeded(screen, slot.id, template, sourceFromPlayerStorage, client, sourceSlotIds);
        }

        return sourceSlotIds;
    }

    private static void addMatchingSlotIfNeeded(HandledScreen<?> screen,
                                                int slotId,
                                                ItemStack template,
                                                boolean sourceFromPlayerStorage,
                                                MinecraftClient client,
                                                List<Integer> sourceSlotIds) {
        Slot slot = screen.getScreenHandler().getSlot(slotId);
        if (!isVisibleSlot(slot)) {
            return;
        }
        if (!slot.hasStack() || !slot.canTakeItems(client.player)) {
            return;
        }
        if (!belongsToSourceRegion(slot, sourceFromPlayerStorage)) {
            return;
        }
        if (!ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
            return;
        }

        sourceSlotIds.add(slotId);
    }

    private static void quickMoveIfStillMatching(HandledScreen<?> screen,
                                                 int slotId,
                                                 ItemStack template,
                                                 boolean sourceFromPlayerStorage) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        Slot slot = screen.getScreenHandler().getSlot(slotId);
        if (!isVisibleSlot(slot)) {
            return;
        }
        if (!slot.hasStack() || !slot.canTakeItems(client.player)) {
            return;
        }
        if (!belongsToSourceRegion(slot, sourceFromPlayerStorage)) {
            return;
        }
        if (!ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
            return;
        }

        client.interactionManager.clickSlot(
                screen.getScreenHandler().syncId,
                slotId,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );
    }

    private static void quickMovePlayerStorageSlotIfStillMatching(HandledScreen<?> screen,
                                                                  int slotId,
                                                                  ItemStack template,
                                                                  boolean sourceFromHotbar) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        Slot slot = screen.getScreenHandler().getSlot(slotId);
        if (!isVisibleSlot(slot)) {
            return;
        }
        if (!slot.hasStack() || !slot.canTakeItems(client.player)) {
            return;
        }
        if (!belongsToPlayerStorageSide(slot, sourceFromHotbar)) {
            return;
        }
        if (!ItemStack.areItemsAndComponentsEqual(slot.getStack(), template)) {
            return;
        }

        client.interactionManager.clickSlot(
                screen.getScreenHandler().syncId,
                slotId,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );
    }

    private static boolean belongsToSourceRegion(Slot slot, boolean sourceFromPlayerStorage) {
        return sourceFromPlayerStorage == isPlayerStorageSlot(slot);
    }

    private static boolean belongsToPlayerStorageSide(Slot slot, boolean sourceFromHotbar) {
        return sourceFromHotbar ? isPlayerHotbarSlot(slot) : isPlayerMainInventorySlot(slot);
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

    private static void moveOneSourceStackToPlayerMainInventory(HandledScreen<?> screen,
                                                                int sourceSlotId,
                                                                ItemStack template,
                                                                List<Integer> targetSlotIds) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        Slot sourceSlot = handler.getSlot(sourceSlotId);
        if (!isVisibleSlot(sourceSlot) || !sourceSlot.hasStack() || !sourceSlot.canTakeItems(client.player)) {
            return;
        }
        if (isPlayerStorageSlot(sourceSlot)) {
            return;
        }
        if (!ItemStack.areItemsAndComponentsEqual(sourceSlot.getStack(), template)) {
            return;
        }

        clickSlot(screen, sourceSlotId, 0, SlotActionType.PICKUP);
        if (handler.getCursorStack().isEmpty()) {
            return;
        }

        fillCursorIntoPlayerMainInventory(screen, targetSlotIds, false);
        fillCursorIntoPlayerMainInventory(screen, targetSlotIds, true);

        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(screen, sourceSlotId, 0, SlotActionType.PICKUP);
        }
    }

    private static void fillCursorIntoPlayerMainInventory(HandledScreen<?> screen,
                                                          List<Integer> targetSlotIds,
                                                          boolean emptySlotsOnly) {
        ScreenHandler handler = screen.getScreenHandler();
        for (int targetSlotId : targetSlotIds) {
            ItemStack cursorStack = handler.getCursorStack();
            if (cursorStack.isEmpty()) {
                return;
            }

            Slot targetSlot = handler.getSlot(targetSlotId);
            if (!isVisibleSlot(targetSlot) || !targetSlot.canInsert(cursorStack)) {
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

    private static boolean hasSpaceInPlayerStorage(ScreenHandler handler,
                                                   ItemStack template,
                                                   List<Integer> targetSlotIds) {
        for (int targetSlotId : targetSlotIds) {
            Slot targetSlot = handler.getSlot(targetSlotId);
            if (!isVisibleSlot(targetSlot) || !targetSlot.canInsert(template)) {
                continue;
            }
            if (!targetSlot.hasStack()) {
                return true;
            }
            if (ItemStack.areItemsAndComponentsEqual(targetSlot.getStack(), template)
                    && targetSlot.getStack().getCount() < targetSlot.getStack().getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> getPlayerPreferredStorageSlotIds(ScreenHandler handler) {
        List<Integer> targetSlotIds = new ArrayList<>(36);
        targetSlotIds.addAll(getPlayerStorageSlotIdsByRange(handler, 9, 35));
        targetSlotIds.addAll(getPlayerStorageSlotIdsByRange(handler, 0, 8));
        return targetSlotIds;
    }

    private static List<Integer> getPlayerStorageSlotIdsByRange(ScreenHandler handler,
                                                                int minInventoryIndex,
                                                                int maxInventoryIndex) {
        List<Slot> targetSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot) || !isPlayerStorageSlotInRange(slot, minInventoryIndex, maxInventoryIndex)) {
                continue;
            }
            targetSlots.add(slot);
        }

        targetSlots.sort(Comparator
                .comparingInt((Slot slot) -> slot.y)
                .thenComparingInt(slot -> slot.x)
                .thenComparingInt(slot -> slot.id));

        return targetSlots.stream()
                .map(slot -> slot.id)
                .toList();
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
        return mouseX >= slotLeft
                && mouseX < slotLeft + SLOT_SIZE
                && mouseY >= slotTop
                && mouseY < slotTop + SLOT_SIZE;
    }

    private static double getMouseX(MinecraftClient client) {
        return client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
    }

    private static double getMouseY(MinecraftClient client) {
        return client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
    }

    private enum TransferMode {
        NONE,
        MATCHING,
        SLOT
    }

    private record SlotKey(ScreenHandler handler, int slotId) {
    }
}
