package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.gui.QuickCraftConfigScreen;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import com.yiyihehe.quickcraft.mixin.CreativeSlotAccessor;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeyboardInputHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.HashedStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
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
 * - 短按只执行一次；进入系统键盘重复后才持续处理当前槽位
 */
public final class QuickThrow implements ClientModInitializer, IKeyboardInputHandler {
    private static final int SLOT_SIZE = 18;
    private static final int SLIDE_SAMPLE_STEP = SLOT_SIZE / 2;
    private static final Set<Integer> pressedKeyboardKeys = new HashSet<>();

    private static ThrowMode activeMode = ThrowMode.NONE;
    private static AbstractContainerScreen<?> activeScreen;
    private static Slot lastHoveredSlot;
    private static boolean hasLastMousePosition;
    private static int lastMouseX;
    private static int lastMouseY;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        InputEventHandler.getInputManager().registerKeyboardInputHandler(this);
    }

    @Override
    public boolean onKeyInput(KeyEvent input, boolean eventKeyState) {
        int keyCode = input.key();
        if (keyCode < 0) {
            return false;
        }

        boolean repeatedEvent = eventKeyState && !pressedKeyboardKeys.add(keyCode);
        if (!eventKeyState) {
            pressedKeyboardKeys.remove(keyCode);
            if (getHeldThrowMode() == ThrowMode.NONE) {
                resetHoldGesture();
            }
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof AbstractContainerScreen<?> screen) || !canUseQuickThrow(client, screen)) {
            return false;
        }

        return handleActiveThrowKeyEvent(
                QuickCraftConfigs.Hotkeys.DROP_MATCHING.getKeybind(),
                keyCode,
                repeatedEvent,
                ThrowMode.MATCHING,
                screen
        ) || handleActiveThrowKeyEvent(
                QuickCraftConfigs.Hotkeys.DROP_WHOLE_STACK.getKeybind(),
                keyCode,
                repeatedEvent,
                ThrowMode.WHOLE_STACK,
                screen
        );
    }

    private static boolean handleActiveThrowKeyEvent(IKeybind keybind,
                                                     int keyCode,
                                                     boolean repeatedEvent,
                                                     ThrowMode mode,
                                                     AbstractContainerScreen<?> screen) {
        if (!keybind.isKeybindHeld() || !isRepeatTriggerKey(keybind, keyCode)) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        Slot hoveredSlot = findHoveredSlot(screen, getMouseX(client), getMouseY(client));
        if (shouldUseVanillaCreativeThrow(screen, hoveredSlot)) {
            return false;
        }

        if (repeatedEvent) {
            ensureHoldGesture(mode, screen);
            if (hoveredSlot == null) {
                lastHoveredSlot = null;
            } else {
                processHoveredSlot(screen, hoveredSlot, mode, true);
            }
        }

        // 首次按下由热键回调执行；QuickCraft 接管的槽位统一吞掉 PRESS/REPEAT，避免回退到原版 Q。
        return true;
    }

    private static boolean isRepeatTriggerKey(IKeybind keybind, int keyCode) {
        List<Integer> keys = keybind.getKeys();
        return !keys.isEmpty() && keys.get(keys.size() - 1) == keyCode;
    }

    public static boolean handleDropMatchingHotkey() {
        return handleBoundThrow(ThrowMode.MATCHING);
    }

    public static boolean handleDropWholeStackHotkey() {
        return handleBoundThrow(ThrowMode.WHOLE_STACK);
    }

    public static boolean shouldConsumeAnvilThrowHotkeyInput() {
        Minecraft client = Minecraft.getInstance();
        return QuickCraftConfigs.isQuickThrowEnabled()
                && client != null
                && client.screen instanceof AnvilScreen
                && (QuickCraftConfigs.Hotkeys.DROP_MATCHING.getKeybind().isKeybindHeld()
                || QuickCraftConfigs.Hotkeys.DROP_WHOLE_STACK.getKeybind().isKeybindHeld());
    }

    private void onClientTick(Minecraft client) {
        if (!client.getWindow().isFocused()) {
            pressedKeyboardKeys.clear();
            resetHoldGesture();
            return;
        }

        ThrowMode mode = getHeldThrowMode();
        if (mode == ThrowMode.NONE) {
            resetHoldGesture();
            return;
        }

        if (!(client.screen instanceof AbstractContainerScreen<?> screen) || !canUseQuickThrow(client, screen)) {
            resetHoldGesture();
            return;
        }

        int mouseX = getMouseX(client);
        int mouseY = getMouseY(client);
        ensureHoldGesture(mode, screen);

        for (Slot slot : findHoveredSlotsAlongPath(screen, mouseX, mouseY)) {
            processHoveredSlot(screen, slot, mode, false);
        }

        Slot hoveredSlot = findHoveredSlot(screen, mouseX, mouseY);
        if (hoveredSlot == null) {
            lastHoveredSlot = null;
        } else {
            processHoveredSlot(screen, hoveredSlot, mode, false);
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
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof AbstractContainerScreen<?> screen) || !canUseQuickThrow(client, screen)) {
            return false;
        }

        int mouseX = getMouseX(client);
        int mouseY = getMouseY(client);
        ensureHoldGesture(mode, screen);

        Slot hoveredSlot = findHoveredSlot(screen, mouseX, mouseY);
        if (hoveredSlot == null) {
            lastHoveredSlot = null;
            return false;
        }

        boolean handled = processHoveredSlot(screen, hoveredSlot, mode, false);
        hasLastMousePosition = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        return handled;
    }

    private static boolean canUseQuickThrow(Minecraft client, AbstractContainerScreen<?> screen) {
        return QuickCraftConfigs.isQuickThrowEnabled()
                && !QuickCraftConfigScreen.isOpen(client)
                && (!isTextInputFocused(screen) || screen instanceof AnvilScreen)
                && client.player != null
                && client.gameMode != null;
    }

    private static void ensureHoldGesture(ThrowMode mode, AbstractContainerScreen<?> screen) {
        if (activeMode == mode && activeScreen == screen) {
            return;
        }

        activeMode = mode;
        activeScreen = screen;
        lastHoveredSlot = null;
        hasLastMousePosition = false;
    }

    private static void resetHoldGesture() {
        activeMode = ThrowMode.NONE;
        activeScreen = null;
        lastHoveredSlot = null;
        hasLastMousePosition = false;
    }

    private static boolean processHoveredSlot(AbstractContainerScreen<?> screen,
                                              Slot hoveredSlot,
                                              ThrowMode mode,
                                              boolean allowRepeatedSlot) {
        if (!allowRepeatedSlot && hoveredSlot == lastHoveredSlot) {
            return false;
        }
        lastHoveredSlot = hoveredSlot;

        ThrowTarget target = getQuickThrowTarget(screen, hoveredSlot);
        if (target == null) {
            return false;
        }

        if (mode == ThrowMode.MATCHING) {
            return dropAllMatchingStacks(screen, target);
        }

        dropWholeStack(target);
        return true;
    }

    private static boolean dropAllMatchingStacks(AbstractContainerScreen<?> screen,
                                                 ThrowTarget hoveredTarget) {
        ItemStack template = hoveredTarget.visibleSlot().getItem();
        if (template.isEmpty()) {
            return false;
        }

        Container targetInventory = hoveredTarget.effectiveSlot().container;

        List<ThrowTarget> matchingTargets = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            ThrowTarget target = getQuickThrowTarget(screen, slot);
            if (target == null || target.effectiveSlot().container != targetInventory) {
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(target.visibleSlot().getItem(), template)) {
                continue;
            }
            matchingTargets.add(target);
        }

        // 先拍快照再点击，避免边遍历边修改槽位集合时影响本轮决策。
        for (ThrowTarget target : matchingTargets) {
            dropWholeStack(target);
        }

        return !matchingTargets.isEmpty();
    }

    private static void dropWholeStack(ThrowTarget target) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        if (target.screen() instanceof CreativeModeInventoryScreen) {
            sendCreativePlayerThrowPacket(target, client);
            return;
        }

        client.gameMode.handleContainerInput(
                target.handler().containerId,
                target.clickSlotId(),
                1,
                ContainerInput.THROW,
                client.player
        );
    }

    private static void sendCreativePlayerThrowPacket(ThrowTarget target, Minecraft client) {
        if (client.getConnection() == null) {
            return;
        }

        // 1.21.2+ 的创造丢弃包有服务端限流；原版 clickSlot 又会在创造客户端本地先生成掉落。
        // 这里直接给玩家真实背包发 THROW 包，只让服务端执行一次丢弃。
        target.visibleSlot().set(ItemStack.EMPTY);
        target.effectiveSlot().set(ItemStack.EMPTY);
        Int2ObjectMap<HashedStack> modifiedStacks = new Int2ObjectOpenHashMap<>();
        modifiedStacks.put(target.clickSlotId(), HashedStack.EMPTY);
        client.getConnection().send(new ServerboundContainerClickPacket(
                target.handler().containerId,
                target.handler().getStateId(),
                (short) target.clickSlotId(),
                (byte) 1,
                ContainerInput.THROW,
                modifiedStacks,
                HashedStack.EMPTY
        ));
    }

    private static List<Slot> findHoveredSlotsAlongPath(AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
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

    private static Slot findHoveredSlot(AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
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

    private static ThrowTarget getQuickThrowTarget(AbstractContainerScreen<?> screen, Slot slot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return null;
        }
        if (!isVisibleSlot(slot) || !slot.hasItem()
                || !slot.mayPickup(client.player)) {
            return null;
        }

        Slot effectiveSlot = unwrapCreativeSlot(slot);
        AbstractContainerMenu handler = screen.getMenu();
        int clickSlotId;
        if (screen instanceof CreativeModeInventoryScreen) {
            if (!(effectiveSlot.container instanceof Inventory) || !isPlayerStorageIndex(effectiveSlot.getContainerSlot())) {
                return null;
            }
            // 创造界面只允许处理解包后的玩家真实背包槽，避免误碰上方创造物品列表。
            handler = client.player.inventoryMenu;
            clickSlotId = getMatchingInventorySlotId(handler, effectiveSlot);
        } else {
            clickSlotId = getClickSlotId(handler, effectiveSlot);
        }

        if (clickSlotId < 0 || QuickContainerLock.isLockedSlot(handler, effectiveSlot)) {
            return null;
        }
        return new ThrowTarget(screen, handler, slot, effectiveSlot, clickSlotId);
    }

    private static boolean shouldUseVanillaCreativeThrow(AbstractContainerScreen<?> screen, Slot slot) {
        if (!(screen instanceof CreativeModeInventoryScreen) || slot == null) {
            return false;
        }

        Slot effectiveSlot = unwrapCreativeSlot(slot);
        return !(effectiveSlot.container instanceof Inventory)
                || !isPlayerStorageIndex(effectiveSlot.getContainerSlot());
    }

    private static int getClickSlotId(AbstractContainerMenu handler, Slot slot) {
        int slotIndex = handler.slots.indexOf(slot);
        return slotIndex >= 0 ? slotIndex : -1;
    }

    private static int getMatchingInventorySlotId(AbstractContainerMenu handler, Slot slot) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot candidate = handler.slots.get(i);
            if (candidate.container == slot.container && candidate.getContainerSlot() == slot.getContainerSlot()) {
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

    private static boolean isTextInputFocused(AbstractContainerScreen<?> screen) {
        GuiEventListener focused = screen.getFocused();
        return focused instanceof EditBox;
    }

    private static boolean isVisibleSlot(Slot slot) {
        return slot.isActive() && slot.x >= 0 && slot.y >= 0;
    }

    private static boolean isMouseOverSlot(Slot slot, int guiLeft, int guiTop, int mouseX, int mouseY) {
        int slotLeft = guiLeft + slot.x;
        int slotTop = guiTop + slot.y;
        return mouseX >= slotLeft
                && mouseX < slotLeft + SLOT_SIZE
                && mouseY >= slotTop
                && mouseY < slotTop + SLOT_SIZE;
    }

    private static int getMouseX(Minecraft client) {
        return (int) (client.mouseHandler.getScaledXPos(client.getWindow()));
    }

    private static int getMouseY(Minecraft client) {
        return (int) (client.mouseHandler.getScaledYPos(client.getWindow()));
    }

    private enum ThrowMode {
        NONE,
        MATCHING,
        WHOLE_STACK
    }

    private record ThrowTarget(AbstractContainerScreen<?> screen,
                               AbstractContainerMenu handler,
                               Slot visibleSlot,
                               Slot effectiveSlot,
                               int clickSlotId) {
    }
}
