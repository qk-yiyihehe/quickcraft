package com.yiyihehe.quickcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.CreativeSlotAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 容器锁：
 * - 对指定箱子记录一个客户端侧“锁定”状态
 * - 槽位锁分为玩家背包全局锁和容器内部锁两类
 * - 只隐藏锁按钮时，不清除已经记录的锁状态
 */
public final class QuickContainerLock implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_LOCK_WIDTH = 8;
    private static final int SLOT_LOCK_HEIGHT = 9;
    private static final int SLOT_LOCK_TEXTURE_WIDTH = 28;
    private static final int SLOT_LOCK_TEXTURE_HEIGHT = 30;
    private static final int SLOT_LOCK_X_OFFSET = 10;
    private static final int SLOT_LOCK_Y_OFFSET = -3;
    private static final int PLAYER_STORAGE_SLOT_COUNT = 36;
    private static final int HOTBAR_SLOT_COUNT = 9;
    private static final int INVALID_LOCK_SLOT = -1;
    private static final int LEFT_PICKUP_BUTTON = 0;
    private static final int AUTO_ELYTRA_CALLER_NONE = 0;
    private static final int AUTO_ELYTRA_CALLER_TWEAKEROO = 1;
    private static final int AUTO_ELYTRA_CALLER_OMMC = 2;
    private static final int AUTO_ELYTRA_SESSION_CLICK_COUNT = 3;
    private static final int AUTO_ELYTRA_LINGER_TICKS = 2;
    private static final String PLAYER_CONTAINER_KEY = "player_inventory";
    private static final String TWEAKEROO_ELYTRA_SWAP_CLASS = "fi.dy.masa.tweakeroo.util.InventoryUtils";
    private static final String TWEAKEROO_ELYTRA_SWAP_METHOD = "swapElytraAndChestPlate";
    private static final String TWEAKEROO_EQUIP_BEST_ELYTRA_METHOD = "equipBestElytra";
    private static final String TWEAKEROO_SWAP_ITEM_TO_EQUIPMENT_SLOT_METHOD = "swapItemToEquipmentSlot";
    private static final String TWEAKEROO_SWAP_SLOTS_METHOD = "swapSlots";
    private static final String OMMC_ELYTRA_SWAP_CLASS = "com.plusls.ommc.feature.autoSwitchElytra.AutoSwitchElytraUtil";
    private static final String OMMC_ELYTRA_SWAP_METHOD = "autoSwitch";
    private static final Identifier SLOT_LOCK_TEXTURE = Identifier.of("quickcraft", "textures/gui/slot_lock.png");

    private static final Set<String> LOCKED_CONTAINERS = new HashSet<>();
    private static final Set<Integer> LOCKED_PLAYER_SLOTS = new HashSet<>();
    private static final Map<String, Set<Integer>> LOCKED_CONTAINER_SLOTS = new HashMap<>();
    private static final Map<Integer, String> SYNC_ID_TO_CONTAINER_KEY = new HashMap<>();
    private static boolean lastUseDown;
    private static int pendingTicks;
    private static String pendingContainerKey;
    private static String currentScreenContainerKey;
    private static boolean bypassPlayerSlotLocks;
    private static final Set<Integer> activeAutoElytraPlayerSlots = new HashSet<>();
    private static final Set<Integer> pendingAutoElytraPlayerSlots = new HashSet<>();
    private static int activeAutoElytraHotbarIndex = INVALID_LOCK_SLOT;
    private static int pendingAutoElytraCaller = AUTO_ELYTRA_CALLER_NONE;
    private static int pendingAutoElytraButton = INVALID_LOCK_SLOT;
    private static int pendingAutoElytraRemainingClicks;
    private static SlotActionType pendingAutoElytraActionType;
    private static final Set<Integer> lingeringAutoElytraPlayerSlots = new HashSet<>();
    private static int lingeringAutoElytraHotbarIndex = INVALID_LOCK_SLOT;
    private static int lingeringAutoElytraTicks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        tickLingeringAutoElytraSession();
        QuickPersistentState.onClientTick(client);
        handleUseAttempt(client);
        processPendingOpen(client);
        clearCurrentScreenKeyIfNeeded(client);
    }

    public static boolean shouldShowLockButton(HandledScreen<?> screen) {
        return QuickCraftConfigs.isContainerLockButtonVisible()
                && !(screen instanceof CreativeInventoryScreen)
                && isLockButtonSupportedHandler(screen.getScreenHandler())
                && getCurrentScreenContainerKey(screen) != null;
    }

    public static boolean shouldShowSlotLocks(HandledScreen<?> screen) {
        return QuickCraftConfigs.isSlotLockOverlayVisible()
                && hasAnyLockableSlot(screen);
    }

    public static void bindCurrentScreen(HandledScreen<?> screen) {
        rememberHandlerKey(screen.getScreenHandler(), getCurrentScreenContainerKey(screen));

        if (isCreativePlayerInventoryScreen(screen)
                || screen.getScreenHandler() instanceof PlayerScreenHandler) {
            currentScreenContainerKey = PLAYER_CONTAINER_KEY;
            rememberHandlerKey(screen.getScreenHandler(), PLAYER_CONTAINER_KEY);
            pendingContainerKey = null;
            pendingTicks = 0;
            return;
        }

        if (pendingContainerKey == null || !isContainerSlotLockSupportedHandler(screen.getScreenHandler())) {
            return;
        }

        currentScreenContainerKey = pendingContainerKey;
        rememberHandlerKey(screen.getScreenHandler(), currentScreenContainerKey);
        pendingContainerKey = null;
        pendingTicks = 0;
    }

    public static Text getLockButtonText(HandledScreen<?> screen) {
        return Text.translatable(isCurrentScreenLocked(screen)
                ? "quickcraft.button.container_lock.unlock"
                : "quickcraft.button.container_lock.lock");
    }

    public static void toggleCurrentScreenLock(MinecraftClient client, HandledScreen<?> screen) {
        String key = getCurrentScreenContainerKey(screen);
        if (key == null) {
            return;
        }

        if (!LOCKED_CONTAINERS.add(key)) {
            LOCKED_CONTAINERS.remove(key);
            QuickPersistentState.saveCurrentProfileState();
            sendStatusMessage(client, Text.translatable("quickcraft.message.container_lock.unlocked"));
            return;
        }

        QuickPersistentState.saveCurrentProfileState();
        sendStatusMessage(client, Text.translatable("quickcraft.message.container_lock.locked"));
    }

    public static boolean isCurrentScreenLocked(HandledScreen<?> screen) {
        String key = getCurrentScreenContainerKey(screen);
        return key != null && LOCKED_CONTAINERS.contains(key);
    }

    public static boolean handleLockedAutomationAttempt(MinecraftClient client, HandledScreen<?> screen, Text actionName) {
        if (!isCurrentScreenLocked(screen)) {
            return false;
        }

        sendStatusMessage(client, Text.translatable("quickcraft.message.container_lock.blocked", actionName));
        return true;
    }

    public static boolean handleLockedSortAttempt(MinecraftClient client, HandledScreen<?> screen) {
        return handleLockedAutomationAttempt(client, screen, Text.translatable("quickcraft.action.sort"));
    }

    public static boolean handleLockedPlayerInventorySortAttempt(MinecraftClient client) {
        if (!LOCKED_CONTAINERS.contains(PLAYER_CONTAINER_KEY)) {
            return false;
        }

        sendStatusMessage(client, Text.translatable(
                "quickcraft.message.container_lock.blocked",
                Text.translatable("quickcraft.action.sort")
        ));
        return true;
    }

    public static boolean handleSlotLockClick(HandledScreen<?> screen, double mouseX, double mouseY, int guiLeft, int guiTop) {
        if (!shouldShowSlotLocks(screen)) {
            return false;
        }

        for (Slot slot : screen.getScreenHandler().slots) {
            if (!isLockableSlot(screen, slot) || !isMouseOverSlotLock(slot, guiLeft, guiTop, mouseX, mouseY)) {
                continue;
            }

            toggleSlotLock(screen, slot);
            return true;
        }

        return false;
    }

    public static boolean handleSlotLockHotkey(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            return false;
        }
        bindCurrentScreen(screen);

        double mouseX = getMouseX(client);
        double mouseY = getMouseY(client);
        return handleSlotLockHotkey(screen, mouseX, mouseY, getGuiLeft(screen), getGuiTop(screen));
    }

    public static boolean handleSlotLockHotkey(HandledScreen<?> screen, double mouseX, double mouseY, int guiLeft, int guiTop) {
        if (!shouldShowSlotLocks(screen)) {
            return false;
        }

        for (Slot slot : screen.getScreenHandler().slots) {
            if (!isLockableSlot(screen, slot) || !isMouseOverSlot(slot, guiLeft, guiTop, mouseX, mouseY)) {
                continue;
            }

            toggleSlotLock(screen, slot);
            return true;
        }

        return false;
    }

    public static void renderSlotLocks(HandledScreen<?> screen, DrawContext context, int guiLeft, int guiTop) {
        if (!shouldShowSlotLocks(screen)) {
            return;
        }

        for (Slot slot : screen.getScreenHandler().slots) {
            if (!isLockableSlot(screen, slot) || !isLockedSlot(screen.getScreenHandler(), slot)) {
                continue;
            }

            renderSlotLockIcon(context, guiLeft + slot.x + SLOT_LOCK_X_OFFSET, guiTop + slot.y + SLOT_LOCK_Y_OFFSET);
        }
    }

    public static boolean isLockedSlot(Slot slot) {
        if (slot == null) {
            return false;
        }

        slot = unwrapCreativeSlot(slot);

        if (isPlayerStorageSlot(slot)) {
            return !shouldBypassPlayerSlotLock(slot) && LOCKED_PLAYER_SLOTS.contains(slot.getIndex());
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !(client.currentScreen instanceof HandledScreen<?> screen)) {
            return false;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (handler.slots.indexOf(slot) < 0) {
            return false;
        }

        return isLockedSlot(handler, slot);
    }

    public static boolean isLockedSlot(ScreenHandler handler, int slotId) {
        Slot slot = getSlot(handler, slotId);
        return slot != null && isLockedSlot(handler, slot);
    }

    public static boolean isLockedSlot(ScreenHandler handler, Slot slot) {
        return isLockedSlotInternal(handler, slot);
    }

    public static boolean hasLockedSlotInRange(ScreenHandler handler, int startIndex, int endIndex) {
        if (handler == null) {
            return false;
        }

        int from = Math.max(0, startIndex);
        int to = Math.min(endIndex, handler.slots.size());
        for (int slotId = from; slotId < to; slotId++) {
            if (isLockedSlot(handler, slotId)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isLockedHotbarSwapTarget(ScreenHandler handler, int hotbarIndex) {
        if (hotbarIndex < 0 || hotbarIndex >= HOTBAR_SLOT_COUNT) {
            return false;
        }

        if (hotbarIndex == activeAutoElytraHotbarIndex || hotbarIndex == lingeringAutoElytraHotbarIndex) {
            return false;
        }

        for (Slot slot : handler.slots) {
            if (isPlayerHotbarSlot(slot) && slot.getIndex() == hotbarIndex) {
                return isLockedSlot(handler, slot);
            }
        }

        return LOCKED_PLAYER_SLOTS.contains(hotbarIndex);
    }

    public static boolean hasLockedPlayerHotbarSlot() {
        for (int slotIndex : LOCKED_PLAYER_SLOTS) {
            if (slotIndex >= 0 && slotIndex < HOTBAR_SLOT_COUNT) {
                return true;
            }
        }
        return false;
    }

    public static boolean isLockedPlayerHotbarSlot(int hotbarIndex) {
        return hotbarIndex >= 0
                && hotbarIndex < HOTBAR_SLOT_COUNT
                && LOCKED_PLAYER_SLOTS.contains(hotbarIndex);
    }

    public static boolean shouldBlockClick(ScreenHandler handler, int slotId, int button, SlotActionType actionType) {
        if (handler == null || actionType == null) {
            return false;
        }

        if (isLockedSlot(handler, slotId)) {
            return true;
        }

        return actionType == SlotActionType.SWAP && isLockedHotbarSwapTarget(handler, button);
    }

    public static void beginSlotClickContext(ScreenHandler handler, int slotId, int button, SlotActionType actionType) {
        bypassPlayerSlotLocks = false;
        activeAutoElytraPlayerSlots.clear();
        activeAutoElytraHotbarIndex = INVALID_LOCK_SLOT;

        if (continueTrustedAutoElytraSession(handler, button, actionType)) {
            return;
        }

        clearTrustedAutoElytraSession();
        if (startTrustedAutoElytraSession(handler, slotId, button, actionType)) {
            continueTrustedAutoElytraSession(handler, button, actionType);
        }
    }

    public static void endSlotClickContext() {
        bypassPlayerSlotLocks = false;
        activeAutoElytraPlayerSlots.clear();
        activeAutoElytraHotbarIndex = INVALID_LOCK_SLOT;
    }

    private static boolean isLockedSlotInternal(ScreenHandler handler, Slot slot) {
        if (slot == null) {
            return false;
        }

        slot = unwrapCreativeSlot(slot);

        if (isPlayerStorageSlot(slot)) {
            return !shouldBypassPlayerSlotLock(slot) && LOCKED_PLAYER_SLOTS.contains(slot.getIndex());
        }

        int containerSlotIndex = getContainerSlotLockIndex(handler, slot);
        if (containerSlotIndex == INVALID_LOCK_SLOT) {
            return false;
        }

        return isContainerSlotLocked(getContainerKey(handler), containerSlotIndex);
    }

    private static void toggleSlotLock(HandledScreen<?> screen, Slot slot) {
        slot = unwrapCreativeSlot(slot);
        ScreenHandler handler = screen.getScreenHandler();
        if (isPlayerStorageSlot(slot)) {
            togglePlayerSlotLock(slot.getIndex());
            return;
        }

        String key = getCurrentScreenContainerKey(screen);
        int containerSlotIndex = getContainerSlotLockIndex(handler, slot);
        if (key == null || containerSlotIndex == INVALID_LOCK_SLOT) {
            return;
        }

        Set<Integer> slots = LOCKED_CONTAINER_SLOTS.computeIfAbsent(key, ignored -> new HashSet<>());
        if (!slots.add(containerSlotIndex)) {
            slots.remove(containerSlotIndex);
            if (slots.isEmpty()) {
                LOCKED_CONTAINER_SLOTS.remove(key);
            }
        }

        QuickPersistentState.saveCurrentProfileState();
    }

    private static void togglePlayerSlotLock(int inventoryIndex) {
        if (!LOCKED_PLAYER_SLOTS.add(inventoryIndex)) {
            LOCKED_PLAYER_SLOTS.remove(inventoryIndex);
        }

        QuickPersistentState.saveCurrentProfileState();
    }

    private static boolean isLockableSlot(HandledScreen<?> screen, Slot slot) {
        Slot effectiveSlot = unwrapCreativeSlot(slot);
        ScreenHandler handler = screen.getScreenHandler();
        if (isCreativePlayerInventoryScreen(screen)) {
            return slot.isEnabled()
                    && slot.x >= 0
                    && slot.y >= 0
                    && handler.slots.indexOf(slot) >= 0
                    && isPlayerStorageSlot(effectiveSlot);
        }

        return slot.isEnabled()
                && slot.x >= 0
                && slot.y >= 0
                && handler.slots.indexOf(slot) >= 0
                && (isPlayerStorageSlot(effectiveSlot) || isContainerSlotLockable(handler, effectiveSlot));
    }

    private static boolean isContainerSlotLockable(ScreenHandler handler, Slot slot) {
        return isContainerSlotLockSupportedHandler(handler)
                && !isPlayerStorageSlot(slot)
                && slot.getIndex() >= 0;
    }

    private static boolean isPlayerStorageSlot(Slot slot) {
        return slot.inventory instanceof PlayerInventory
                && slot.getIndex() >= 0
                && slot.getIndex() < PLAYER_STORAGE_SLOT_COUNT;
    }

    private static boolean isPlayerHotbarSlot(Slot slot) {
        return isPlayerStorageSlot(slot) && slot.getIndex() < HOTBAR_SLOT_COUNT;
    }

    /**
     * tweakeroo / OMMC 的自动穿脱鞘翅都会连续发 3 次 clickSlot。
     * 这里只给这一小段确定的点击序列开后门，平时锁格子仍然完全生效。
     */
    private static boolean startTrustedAutoElytraSession(ScreenHandler handler, int slotId, int button, SlotActionType actionType) {
        if (!(handler instanceof PlayerScreenHandler)) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null
                || client.player == null
                || client.currentScreen != null
                || client.player.currentScreenHandler != handler) {
            return false;
        }

        Slot slot = getSlot(handler, slotId);
        if (slot == null || !isPlayerStorageSlot(slot)) {
            return false;
        }

        int caller = getTrustedAutoElytraCaller();
        if (caller == AUTO_ELYTRA_CALLER_TWEAKEROO) {
            if (actionType != SlotActionType.SWAP || button < 0 || button >= HOTBAR_SLOT_COUNT) {
                return false;
            }

            pendingAutoElytraCaller = caller;
            pendingAutoElytraActionType = actionType;
            pendingAutoElytraButton = button;
            pendingAutoElytraRemainingClicks = AUTO_ELYTRA_SESSION_CLICK_COUNT;
            pendingAutoElytraPlayerSlots.add(slot.getIndex());
            pendingAutoElytraPlayerSlots.add(button);
            return true;
        }

        if (caller == AUTO_ELYTRA_CALLER_OMMC) {
            if (actionType != SlotActionType.PICKUP || button != LEFT_PICKUP_BUTTON) {
                return false;
            }

            pendingAutoElytraCaller = caller;
            pendingAutoElytraActionType = actionType;
            pendingAutoElytraButton = button;
            pendingAutoElytraRemainingClicks = AUTO_ELYTRA_SESSION_CLICK_COUNT;
            pendingAutoElytraPlayerSlots.add(slot.getIndex());
            return true;
        }

        return false;
    }

    /**
     * 只给 tweakeroo / OMMC 的自动穿脱鞘翅开后门，普通无界面点击仍然照常锁死。
     */
    private static int getTrustedAutoElytraCaller() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            String methodName = element.getMethodName();
            if ((TWEAKEROO_ELYTRA_SWAP_CLASS.equals(className)
                    && (TWEAKEROO_ELYTRA_SWAP_METHOD.equals(methodName)
                    || TWEAKEROO_EQUIP_BEST_ELYTRA_METHOD.equals(methodName)
                    || TWEAKEROO_SWAP_ITEM_TO_EQUIPMENT_SLOT_METHOD.equals(methodName)
                    || TWEAKEROO_SWAP_SLOTS_METHOD.equals(methodName)))) {
                return AUTO_ELYTRA_CALLER_TWEAKEROO;
            }

            if (OMMC_ELYTRA_SWAP_CLASS.equals(className) && OMMC_ELYTRA_SWAP_METHOD.equals(methodName)) {
                return AUTO_ELYTRA_CALLER_OMMC;
            }
        }
        return AUTO_ELYTRA_CALLER_NONE;
    }

    private static boolean continueTrustedAutoElytraSession(ScreenHandler handler, int button, SlotActionType actionType) {
        if (!(handler instanceof PlayerScreenHandler)
                || pendingAutoElytraCaller == AUTO_ELYTRA_CALLER_NONE
                || pendingAutoElytraActionType != actionType
                || pendingAutoElytraButton != button
                || getTrustedAutoElytraCaller() != pendingAutoElytraCaller) {
            return false;
        }

        bypassPlayerSlotLocks = true;
        activeAutoElytraPlayerSlots.addAll(pendingAutoElytraPlayerSlots);
        if (actionType == SlotActionType.SWAP) {
            activeAutoElytraHotbarIndex = button;
        }

        pendingAutoElytraRemainingClicks--;
        if (pendingAutoElytraRemainingClicks <= 0) {
            beginLingeringAutoElytraSession();
            clearTrustedAutoElytraSession();
        }

        return true;
    }

    private static void clearTrustedAutoElytraSession() {
        pendingAutoElytraCaller = AUTO_ELYTRA_CALLER_NONE;
        pendingAutoElytraActionType = null;
        pendingAutoElytraButton = INVALID_LOCK_SLOT;
        pendingAutoElytraRemainingClicks = 0;
        pendingAutoElytraPlayerSlots.clear();
    }

    /**
     * 自动换鞘翅最后一次 SWAP 结束后，原版/服务端还可能再做一轮槽位校验。
     * 这里把本次会话涉及的玩家格再保留极短的一小段时间，避免回落到原锁格时被尾声校验拦下。
     */
    private static void beginLingeringAutoElytraSession() {
        lingeringAutoElytraPlayerSlots.clear();
        lingeringAutoElytraPlayerSlots.addAll(activeAutoElytraPlayerSlots);
        lingeringAutoElytraPlayerSlots.addAll(pendingAutoElytraPlayerSlots);
        lingeringAutoElytraHotbarIndex = activeAutoElytraHotbarIndex;
        lingeringAutoElytraTicks = AUTO_ELYTRA_LINGER_TICKS;
    }

    private static void tickLingeringAutoElytraSession() {
        if (lingeringAutoElytraTicks <= 0) {
            return;
        }

        lingeringAutoElytraTicks--;
        if (lingeringAutoElytraTicks <= 0) {
            clearLingeringAutoElytraSession();
        }
    }

    private static void clearLingeringAutoElytraSession() {
        lingeringAutoElytraPlayerSlots.clear();
        lingeringAutoElytraHotbarIndex = INVALID_LOCK_SLOT;
        lingeringAutoElytraTicks = 0;
    }

    private static boolean shouldBypassPlayerSlotLock(Slot slot) {
        return isPlayerStorageSlot(slot)
                && (bypassPlayerSlotLocks || isAutoElytraSessionSlot(slot.getIndex()));
    }

    /**
     * 有些槽位校验会在客户端 clickSlot 返回后、服务端处理线程里再次触发。
     * 这里继续认这次自动鞘翅会话涉及的玩家格，避免只放行前半段点击导致回落失败。
     */
    private static boolean isAutoElytraSessionSlot(int inventoryIndex) {
        return activeAutoElytraPlayerSlots.contains(inventoryIndex)
                || pendingAutoElytraPlayerSlots.contains(inventoryIndex)
                || lingeringAutoElytraPlayerSlots.contains(inventoryIndex);
    }

    private static boolean isMouseOverSlotLock(Slot slot, int guiLeft, int guiTop, double mouseX, double mouseY) {
        int left = guiLeft + slot.x + SLOT_LOCK_X_OFFSET;
        int top = guiTop + slot.y + SLOT_LOCK_Y_OFFSET;
        return mouseX >= left
                && mouseX < left + SLOT_LOCK_WIDTH
                && mouseY >= top
                && mouseY < top + SLOT_LOCK_HEIGHT;
    }

    private static boolean isMouseOverSlot(Slot slot, int guiLeft, int guiTop, double mouseX, double mouseY) {
        int slotLeft = guiLeft + slot.x;
        int slotTop = guiTop + slot.y;
        return mouseX >= slotLeft
                && mouseX < slotLeft + SLOT_SIZE
                && mouseY >= slotTop
                && mouseY < slotTop + SLOT_SIZE;
    }

    public static void renderHotbarLocks(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null
                || client.player == null
                || client.currentScreen != null
                || !QuickCraftConfigs.isSlotLockOverlayVisible()
                || !hasLockedPlayerHotbarSlot()) {
            return;
        }

        int hotbarLeft = context.getScaledWindowWidth() / 2 - 91;
        int hotbarTop = context.getScaledWindowHeight() - 22;
        for (int hotbarIndex = 0; hotbarIndex < HOTBAR_SLOT_COUNT; hotbarIndex++) {
            if (!isLockedPlayerHotbarSlot(hotbarIndex)) {
                continue;
            }

            int slotLeft = hotbarLeft + hotbarIndex * 20 + 3;
            int slotTop = hotbarTop + 3;
            renderSlotLockIcon(context, slotLeft + SLOT_LOCK_X_OFFSET, slotTop + SLOT_LOCK_Y_OFFSET);
        }
    }

    private static void renderSlotLockIcon(DrawContext context, int left, int top) {
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                SLOT_LOCK_TEXTURE,
                left,
                top,
                0.0F,
                0.0F,
                SLOT_LOCK_WIDTH,
                SLOT_LOCK_HEIGHT,
                SLOT_LOCK_TEXTURE_WIDTH,
                SLOT_LOCK_TEXTURE_HEIGHT,
                SLOT_LOCK_TEXTURE_WIDTH,
                SLOT_LOCK_TEXTURE_HEIGHT
        );
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

    private void handleUseAttempt(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null || client.currentScreen != null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isBoundKeyDown(client, client.options.useKey);
        if (useDown && !lastUseDown && client.crosshairTarget instanceof BlockHitResult blockHitResult) {
            String containerKey = getSupportedContainerKey(client, blockHitResult);
            if (containerKey != null) {
                pendingContainerKey = containerKey;
                pendingTicks = 0;
            }
        }

        lastUseDown = useDown;
    }

    private void processPendingOpen(MinecraftClient client) {
        if (pendingContainerKey == null) {
            return;
        }

        pendingTicks++;
        if (client.currentScreen instanceof HandledScreen<?> screen
                && isContainerSlotLockSupportedHandler(screen.getScreenHandler())) {
            currentScreenContainerKey = pendingContainerKey;
            rememberHandlerKey(screen.getScreenHandler(), currentScreenContainerKey);
            pendingContainerKey = null;
            pendingTicks = 0;
            return;
        }

        if (pendingTicks > OPEN_TIMEOUT_TICKS) {
            pendingContainerKey = null;
            pendingTicks = 0;
        }
    }

    private void clearCurrentScreenKeyIfNeeded(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            currentScreenContainerKey = null;
            return;
        }

        if (screen.getScreenHandler() instanceof PlayerScreenHandler) {
            currentScreenContainerKey = PLAYER_CONTAINER_KEY;
            rememberHandlerKey(screen.getScreenHandler(), PLAYER_CONTAINER_KEY);
            return;
        }

        if (isContainerSlotLockSupportedHandler(screen.getScreenHandler())) {
            rememberHandlerKey(screen.getScreenHandler(), currentScreenContainerKey);
            return;
        }

        currentScreenContainerKey = null;
    }

    private static String getCurrentScreenContainerKey(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.currentScreen != screen) {
            return null;
        }

        if (screen.getScreenHandler() instanceof PlayerScreenHandler) {
            return PLAYER_CONTAINER_KEY;
        }

        if (!isContainerSlotLockSupportedHandler(screen.getScreenHandler())) {
            return null;
        }

        if (currentScreenContainerKey != null) {
            return currentScreenContainerKey;
        }

        return SYNC_ID_TO_CONTAINER_KEY.get(screen.getScreenHandler().syncId);
    }

    private static boolean hasAnyLockableSlot(HandledScreen<?> screen) {
        ScreenHandler handler = screen.getScreenHandler();
        for (Slot slot : handler.slots) {
            if (!slot.isEnabled() || slot.x < 0 || slot.y < 0) {
                continue;
            }

            Slot effectiveSlot = unwrapCreativeSlot(slot);
            if (isPlayerStorageSlot(effectiveSlot) || isContainerSlotLockable(handler, effectiveSlot)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCreativePlayerInventoryScreen(HandledScreen<?> screen) {
        return screen instanceof CreativeInventoryScreen;
    }

    private static String getContainerKey(ScreenHandler handler) {
        if (handler instanceof PlayerScreenHandler) {
            return PLAYER_CONTAINER_KEY;
        }
        if (!isContainerSlotLockSupportedHandler(handler)) {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.currentScreen instanceof HandledScreen<?> screen && screen.getScreenHandler() == handler) {
            String currentKey = getCurrentScreenContainerKey(screen);
            if (currentKey != null) {
                rememberHandlerKey(handler, currentKey);
                return currentKey;
            }
        }

        return SYNC_ID_TO_CONTAINER_KEY.get(handler.syncId);
    }

    private static void rememberHandlerKey(ScreenHandler handler, String containerKey) {
        if (handler == null || containerKey == null) {
            return;
        }

        SYNC_ID_TO_CONTAINER_KEY.put(handler.syncId, containerKey);
    }

    private static boolean isContainerSlotLocked(String key, int slotIndex) {
        if (key == null) {
            return false;
        }

        Set<Integer> slots = LOCKED_CONTAINER_SLOTS.get(key);
        return slots != null && slots.contains(slotIndex);
    }

    private static int getContainerSlotLockIndex(ScreenHandler handler, Slot slot) {
        if (!isContainerSlotLockable(handler, slot)) {
            return INVALID_LOCK_SLOT;
        }
        return slot.getIndex();
    }

    private static Slot getSlot(ScreenHandler handler, int slotId) {
        if (handler == null || slotId < 0 || slotId >= handler.slots.size()) {
            return null;
        }
        return handler.getSlot(slotId);
    }

    private static boolean isLockButtonSupportedHandler(ScreenHandler handler) {
        return isContainerSlotLockSupportedHandler(handler) || handler instanceof PlayerScreenHandler;
    }

    private static boolean isContainerSlotLockSupportedHandler(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler
                || handler instanceof ShulkerBoxScreenHandler;
    }

    private static String getSupportedContainerKey(MinecraftClient client, BlockHitResult blockHitResult) {
        World world = client.world;
        if (world == null) {
            return null;
        }

        Block block = world.getBlockState(blockHitResult.getBlockPos()).getBlock();
        if (!(block instanceof ChestBlock)
                && !(block instanceof BarrelBlock)
                && !(block instanceof EnderChestBlock)
                && !(block instanceof ShulkerBoxBlock)) {
            return null;
        }

        return buildContainerKey(world, blockHitResult.getBlockPos().asLong());
    }

    private static String buildContainerKey(World world, long blockPosLong) {
        return world.getRegistryKey().getValue() + "#" + blockPosLong;
    }

    private static int getGuiLeft(HandledScreen<?> screen) {
        return ((com.yiyihehe.quickcraft.mixin.HandledScreenAccessor) screen).quickcraft$getGuiLeft();
    }

    private static int getGuiTop(HandledScreen<?> screen) {
        return ((com.yiyihehe.quickcraft.mixin.HandledScreenAccessor) screen).quickcraft$getGuiTop();
    }

    private static double getMouseX(MinecraftClient client) {
        return client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
    }

    private static double getMouseY(MinecraftClient client) {
        return client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
    }

    private static void sendStatusMessage(MinecraftClient client, Text message) {
        if (client != null && client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    static void clearPersistentState() {
        LOCKED_CONTAINERS.clear();
        LOCKED_PLAYER_SLOTS.clear();
        LOCKED_CONTAINER_SLOTS.clear();
        SYNC_ID_TO_CONTAINER_KEY.clear();
        pendingContainerKey = null;
        currentScreenContainerKey = null;
        pendingTicks = 0;
        lastUseDown = false;
        bypassPlayerSlotLocks = false;
        activeAutoElytraPlayerSlots.clear();
        pendingAutoElytraPlayerSlots.clear();
        activeAutoElytraHotbarIndex = INVALID_LOCK_SLOT;
        clearLingeringAutoElytraSession();
        clearTrustedAutoElytraSession();
    }

    static void loadPersistentState(JsonObject root) {
        JsonObject state = getObject(root, "containerLock");
        if (state == null) {
            return;
        }

        readStringSet(getElement(state, "lockedContainers"), LOCKED_CONTAINERS);
        readIntSet(getElement(state, "lockedPlayerSlots"), LOCKED_PLAYER_SLOTS);

        JsonObject lockedContainerSlots = getObject(state, "lockedContainerSlots");
        if (lockedContainerSlots == null) {
            return;
        }

        for (Map.Entry<String, JsonElement> entry : lockedContainerSlots.entrySet()) {
            Set<Integer> slots = new HashSet<>();
            readIntSet(entry.getValue(), slots);
            if (!slots.isEmpty()) {
                LOCKED_CONTAINER_SLOTS.put(entry.getKey(), slots);
            }
        }
    }

    static void writePersistentState(JsonObject root) {
        JsonObject state = new JsonObject();
        state.add("lockedContainers", toStringArray(LOCKED_CONTAINERS));
        state.add("lockedPlayerSlots", toIntArray(LOCKED_PLAYER_SLOTS));

        JsonObject lockedContainerSlots = new JsonObject();
        for (Map.Entry<String, Set<Integer>> entry : LOCKED_CONTAINER_SLOTS.entrySet()) {
            lockedContainerSlots.add(entry.getKey(), toIntArray(entry.getValue()));
        }
        state.add("lockedContainerSlots", lockedContainerSlots);

        root.add("containerLock", state);
    }

    private static JsonObject getObject(JsonObject root, String key) {
        JsonElement element = getElement(root, key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonElement getElement(JsonObject root, String key) {
        return root != null && root.has(key) ? root.get(key) : null;
    }

    private static void readStringSet(JsonElement element, Set<String> target) {
        if (element == null || !element.isJsonArray()) {
            return;
        }

        for (JsonElement value : element.getAsJsonArray()) {
            if (value != null && value.isJsonPrimitive()) {
                target.add(value.getAsString());
            }
        }
    }

    private static void readIntSet(JsonElement element, Set<Integer> target) {
        if (element == null || !element.isJsonArray()) {
            return;
        }

        for (JsonElement value : element.getAsJsonArray()) {
            if (value != null && value.isJsonPrimitive()) {
                target.add(value.getAsInt());
            }
        }
    }

    private static JsonArray toStringArray(Set<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray toIntArray(Set<Integer> values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }
}
