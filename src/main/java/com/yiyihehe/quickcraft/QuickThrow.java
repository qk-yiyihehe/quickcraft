package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.gui.QuickCraftConfigScreen;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import com.yiyihehe.quickcraft.mixin.CreativeSlotAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通用丢弃逻辑：
 * - Q：保留原版行为
 * - 自定义组合键 A：整组丢弃鼠标指向槽位
 * - 自定义组合键 B：丢弃鼠标当前所在容器区域内，与鼠标指向物品相同的全部可见物品
 * - 按住自定义组合键滑过槽位时，每个滑过的槽位只触发一次，手感接近原版拖拽操作
 */
public final class QuickThrow implements ClientModInitializer {
    private static final int SLOT_SIZE = 18;
    private static final int SLIDE_SAMPLE_STEP = SLOT_SIZE / 2;
    private static final Set<SlotKey> handledSlotsInGesture = new HashSet<>();

    private static ThrowMode activeMode = ThrowMode.NONE;
    private static HandledScreen<?> activeScreen;
    private static boolean hasLastMousePosition;
    private static int lastMouseX;
    private static int lastMouseY;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean handleDropMatchingHotkey() {
        return handleBoundThrow(ThrowMode.MATCHING);
    }

    public static boolean handleDropWholeStackHotkey() {
        return handleBoundThrow(ThrowMode.WHOLE_STACK);
    }

    public static boolean shouldConsumeAnvilThrowHotkeyInput() {
        MinecraftClient client = MinecraftClient.getInstance();
        return QuickCraftConfigs.isQuickThrowEnabled()
                && client != null
                && client.currentScreen instanceof AnvilScreen
                && (QuickCraftConfigs.Hotkeys.DROP_MATCHING.getKeybind().isKeybindHeld()
                || QuickCraftConfigs.Hotkeys.DROP_WHOLE_STACK.getKeybind().isKeybindHeld());
    }

    private void onClientTick(MinecraftClient client) {
        ThrowMode mode = getHeldThrowMode();
        if (mode == ThrowMode.NONE) {
            resetHoldGesture();
            return;
        }

        if (!(client.currentScreen instanceof HandledScreen<?> screen) || !canUseQuickThrow(client, screen)) {
            resetHoldGesture();
            return;
        }

        int mouseX = getMouseX(client);
        int mouseY = getMouseY(client);
        ensureHoldGesture(mode, screen);

        for (Slot slot : findHoveredSlotsAlongPath(screen, mouseX, mouseY)) {
            processHoveredSlot(screen, slot, mode, true);
        }

        hasLastMousePosition = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    private static ThrowMode getHeldThrowMode() {
        if (QuickCraftConfigs.Hotkeys.DROP_MATCHING.getKeybind().isKeybindHeld()) {
            return ThrowMode.MATCHING;
        }
        if (QuickCraftConfigs.Hotkeys.DROP_WHOLE_STACK.getKeybind().isKeybindHeld()) {
            return ThrowMode.WHOLE_STACK;
        }
        return ThrowMode.NONE;
    }

    private static boolean handleBoundThrow(ThrowMode mode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof HandledScreen<?> screen) || !canUseQuickThrow(client, screen)) {
            return false;
        }

        int mouseX = getMouseX(client);
        int mouseY = getMouseY(client);
        ensureHoldGesture(mode, screen);

        Slot hoveredSlot = findHoveredSlot(screen, mouseX, mouseY);
        if (hoveredSlot == null) {
            return false;
        }

        boolean handled = processHoveredSlot(screen, hoveredSlot, mode, true);
        hasLastMousePosition = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        return handled;
    }

    private static boolean canUseQuickThrow(MinecraftClient client, HandledScreen<?> screen) {
        return QuickCraftConfigs.isQuickThrowEnabled()
                && !QuickCraftConfigScreen.isOpen(client)
                && (!isTextInputFocused(screen) || screen instanceof AnvilScreen)
                && client.player != null
                && client.interactionManager != null;
    }

    private static void ensureHoldGesture(ThrowMode mode, HandledScreen<?> screen) {
        if (activeMode == mode && activeScreen == screen) {
            return;
        }

        activeMode = mode;
        activeScreen = screen;
        hasLastMousePosition = false;
        handledSlotsInGesture.clear();
    }

    private static void resetHoldGesture() {
        activeMode = ThrowMode.NONE;
        activeScreen = null;
        hasLastMousePosition = false;
        handledSlotsInGesture.clear();
    }

    private static boolean processHoveredSlot(HandledScreen<?> screen,
                                              Slot hoveredSlot,
                                              ThrowMode mode,
                                              boolean rememberGestureSlot) {
        ThrowTarget target = getQuickThrowTarget(screen, hoveredSlot);
        if (target == null) {
            return false;
        }

        if (rememberGestureSlot && !handledSlotsInGesture.add(target.key())) {
            return false;
        }

        if (mode == ThrowMode.MATCHING) {
            return dropAllMatchingStacks(screen, target, rememberGestureSlot);
        }

        dropWholeStack(target);
        return true;
    }

    private static boolean dropAllMatchingStacks(HandledScreen<?> screen,
                                                 ThrowTarget hoveredTarget,
                                                 boolean rememberGestureSlot) {
        ItemStack template = hoveredTarget.visibleSlot().getStack();
        if (template.isEmpty()) {
            return false;
        }

        Inventory targetInventory = hoveredTarget.effectiveSlot().inventory;

        List<ThrowTarget> matchingTargets = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            ThrowTarget target = getQuickThrowTarget(screen, slot);
            if (target == null || target.effectiveSlot().inventory != targetInventory) {
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(target.visibleSlot().getStack(), template)) {
                continue;
            }
            matchingTargets.add(target);
        }

        // 先拍快照再点击，避免边遍历边修改槽位集合时影响本轮决策。
        for (ThrowTarget target : matchingTargets) {
            if (rememberGestureSlot) {
                handledSlotsInGesture.add(target.key());
            }
            dropWholeStack(target);
        }

        return !matchingTargets.isEmpty();
    }

    private static void dropWholeStack(ThrowTarget target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        ScreenHandler previousHandler = client.player.currentScreenHandler;
        boolean usingCreativePlayerInventory = target.screen() instanceof CreativeInventoryScreen;
        try {
            if (usingCreativePlayerInventory) {
                // 创造界面原版 dropCreativeStack 有服务端限流，这里按真实玩家背包槽执行普通丢弃。
                client.player.currentScreenHandler = target.handler();
            }
            client.interactionManager.clickSlot(
                    target.handler().syncId,
                    target.clickSlotId(),
                    1,
                    SlotActionType.THROW,
                    client.player
            );
        } finally {
            if (usingCreativePlayerInventory) {
                client.player.currentScreenHandler = previousHandler;
            }
        }
    }

    private static List<Slot> findHoveredSlotsAlongPath(HandledScreen<?> screen, int mouseX, int mouseY) {
        if (!hasLastMousePosition) {
            Slot slot = findHoveredSlot(screen, mouseX, mouseY);
            return slot == null ? List.of() : List.of(slot);
        }

        int deltaX = mouseX - lastMouseX;
        int deltaY = mouseY - lastMouseY;
        int steps = Math.max(1, Math.max(Math.abs(deltaX), Math.abs(deltaY)) / SLIDE_SAMPLE_STEP);
        Set<Slot> slots = new LinkedHashSet<>();

        for (int i = 0; i <= steps; i++) {
            int sampleX = lastMouseX + Math.round(deltaX * i / (float) steps);
            int sampleY = lastMouseY + Math.round(deltaY * i / (float) steps);
            Slot slot = findHoveredSlot(screen, sampleX, sampleY);
            if (slot != null) {
                slots.add(slot);
            }
        }

        return new ArrayList<>(slots);
    }

    private static Slot findHoveredSlot(HandledScreen<?> screen, int mouseX, int mouseY) {
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

    private static ThrowTarget getQuickThrowTarget(HandledScreen<?> screen, Slot slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return null;
        }
        if (!isVisibleSlot(slot) || !slot.hasStack()
                || !slot.canTakeItems(client.player)) {
            return null;
        }

        Slot effectiveSlot = unwrapCreativeSlot(slot);
        ScreenHandler handler = screen.getScreenHandler();
        int clickSlotId;
        if (screen instanceof CreativeInventoryScreen) {
            if (!(effectiveSlot.inventory instanceof PlayerInventory) || !isPlayerStorageIndex(effectiveSlot.getIndex())) {
                return null;
            }
            // 创造界面只允许处理解包后的玩家真实背包槽，避免误碰上方创造物品列表。
            handler = client.player.playerScreenHandler;
            clickSlotId = getMatchingInventorySlotId(handler, effectiveSlot);
        } else {
            clickSlotId = getClickSlotId(handler, effectiveSlot);
        }

        if (clickSlotId < 0 || QuickContainerLock.isLockedSlot(handler, effectiveSlot)) {
            return null;
        }
        return new ThrowTarget(screen, handler, slot, effectiveSlot, clickSlotId);
    }

    private static int getClickSlotId(ScreenHandler handler, Slot slot) {
        int slotIndex = handler.slots.indexOf(slot);
        return slotIndex >= 0 ? slotIndex : -1;
    }

    private static int getMatchingInventorySlotId(ScreenHandler handler, Slot slot) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot candidate = handler.slots.get(i);
            if (candidate.inventory == slot.inventory && candidate.getIndex() == slot.getIndex()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isPlayerStorageIndex(int inventoryIndex) {
        return inventoryIndex >= 0 && inventoryIndex < 36;
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

    private static boolean isTextInputFocused(HandledScreen<?> screen) {
        Element focused = screen.getFocused();
        return focused instanceof TextFieldWidget;
    }

    private static boolean isVisibleSlot(Slot slot) {
        return slot.isEnabled() && slot.x >= 0 && slot.y >= 0;
    }

    private static boolean isMouseOverSlot(Slot slot, int guiLeft, int guiTop, int mouseX, int mouseY) {
        int slotLeft = guiLeft + slot.x;
        int slotTop = guiTop + slot.y;
        return mouseX >= slotLeft
                && mouseX < slotLeft + SLOT_SIZE
                && mouseY >= slotTop
                && mouseY < slotTop + SLOT_SIZE;
    }

    private static int getMouseX(MinecraftClient client) {
        return (int) (client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth());
    }

    private static int getMouseY(MinecraftClient client) {
        return (int) (client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight());
    }

    private enum ThrowMode {
        NONE,
        MATCHING,
        WHOLE_STACK
    }

    private record ThrowTarget(HandledScreen<?> screen,
                               ScreenHandler handler,
                               Slot visibleSlot,
                               Slot effectiveSlot,
                               int clickSlotId) {
        private SlotKey key() {
            return new SlotKey(this.handler, this.clickSlotId);
        }
    }

    private record SlotKey(ScreenHandler handler, int slotId) {
    }
}
