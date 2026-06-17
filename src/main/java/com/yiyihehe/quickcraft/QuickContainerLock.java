package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 容器锁：
 * - 对指定箱子记录一个客户端侧“锁定”状态
 * - 锁定后阻止整理容器区域
 * - 只隐藏锁按钮时，不清除已经记录的锁状态
 */
public final class QuickContainerLock implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_LOCK_SIZE = 8;
    private static final String PLAYER_CONTAINER_KEY = "player_inventory";

    private static final Set<String> LOCKED_CONTAINERS = new HashSet<>();
    private static final Map<String, Set<Integer>> LOCKED_SLOTS = new HashMap<>();
    private static boolean lastUseDown;
    private static int pendingTicks;
    private static String pendingContainerKey;
    private static String currentScreenContainerKey;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        handleUseAttempt(client);
        processPendingOpen(client);
        clearCurrentScreenKeyIfNeeded(client);
    }

    public static boolean shouldShowLockButton(HandledScreen<?> screen) {
        return QuickCraftConfigs.isContainerLockButtonVisible()
                && isSupportedHandler(screen.getScreenHandler())
                && getCurrentScreenContainerKey(screen) != null;
    }

    public static boolean shouldShowSlotLocks(HandledScreen<?> screen) {
        return QuickCraftConfigs.isSlotLockOverlayVisible()
                && isSupportedHandler(screen.getScreenHandler())
                && getCurrentScreenContainerKey(screen) != null;
    }

    public static void bindCurrentScreen(HandledScreen<?> screen) {
        if (screen.getScreenHandler() instanceof PlayerScreenHandler) {
            currentScreenContainerKey = PLAYER_CONTAINER_KEY;
            pendingContainerKey = null;
            pendingTicks = 0;
            return;
        }

        if (pendingContainerKey == null || !isSupportedHandler(screen.getScreenHandler())) {
            return;
        }

        currentScreenContainerKey = pendingContainerKey;
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
            sendStatusMessage(client, Text.translatable("quickcraft.message.container_lock.unlocked"));
            return;
        }

        sendStatusMessage(client, Text.translatable("quickcraft.message.container_lock.locked"));
    }

    public static boolean isCurrentScreenLocked(HandledScreen<?> screen) {
        String key = getCurrentScreenContainerKey(screen);
        return key != null && LOCKED_CONTAINERS.contains(key);
    }

    private static boolean isSlotLocked(HandledScreen<?> screen, int slotId) {
        String key = getCurrentScreenContainerKey(screen);
        return isSlotLocked(key, slotId);
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

    public static boolean handleSlotLockClick(HandledScreen<?> screen, double mouseX, double mouseY, int guiLeft, int guiTop) {
        if (!shouldShowSlotLocks(screen)) {
            return false;
        }

        for (Slot slot : screen.getScreenHandler().slots) {
            if (!isLockableSlot(screen, slot) || !isMouseOverSlotLock(slot, guiLeft, guiTop, mouseX, mouseY)) {
                continue;
            }

            toggleSlotLock(screen, slot.id);
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

            toggleSlotLock(screen, slot.id);
            return true;
        }

        return false;
    }

    public static void renderSlotLocks(HandledScreen<?> screen, DrawContext context, int guiLeft, int guiTop) {
        if (!shouldShowSlotLocks(screen)) {
            return;
        }

        for (Slot slot : screen.getScreenHandler().slots) {
            if (!isLockableSlot(screen, slot) || !isSlotLocked(screen, slot.id)) {
                continue;
            }

            renderSlotLockIcon(context, guiLeft + slot.x + 9, guiTop + slot.y + 1);
        }
    }

    private static void toggleSlotLock(HandledScreen<?> screen, int slotId) {
        String key = getCurrentScreenContainerKey(screen);
        if (key == null) {
            return;
        }

        Set<Integer> slots = LOCKED_SLOTS.computeIfAbsent(key, ignored -> new HashSet<>());
        if (!slots.add(slotId)) {
            slots.remove(slotId);
            if (slots.isEmpty()) {
                LOCKED_SLOTS.remove(key);
            }
        }
    }

    private static boolean isLockableSlot(HandledScreen<?> screen, Slot slot) {
        return slot.isEnabled()
                && slot.x >= 0
                && slot.y >= 0
                && screen.getScreenHandler().slots.indexOf(slot) >= 0
                && (isPlayerStorageSlot(slot) || !(screen.getScreenHandler() instanceof PlayerScreenHandler));
    }

    private static boolean isPlayerStorageSlot(Slot slot) {
        return slot.inventory instanceof PlayerInventory
                && slot.getIndex() >= 0
                && slot.getIndex() < 36;
    }

    private static boolean isMouseOverSlotLock(Slot slot, int guiLeft, int guiTop, double mouseX, double mouseY) {
        int left = guiLeft + slot.x + 9;
        int top = guiTop + slot.y + 1;
        return mouseX >= left
                && mouseX < left + SLOT_LOCK_SIZE
                && mouseY >= top
                && mouseY < top + SLOT_LOCK_SIZE;
    }

    private static boolean isMouseOverSlot(Slot slot, int guiLeft, int guiTop, double mouseX, double mouseY) {
        int slotLeft = guiLeft + slot.x;
        int slotTop = guiTop + slot.y;
        return mouseX >= slotLeft
                && mouseX < slotLeft + SLOT_SIZE
                && mouseY >= slotTop
                && mouseY < slotTop + SLOT_SIZE;
    }

    private static void renderSlotLockIcon(DrawContext context, int left, int top) {
        context.fill(left, top + 3, left + 8, top + 8, 0xCC202735);
        context.fill(left + 1, top + 4, left + 7, top + 8, 0xFFE8C95B);
        context.fill(left + 3, top + 5, left + 5, top + 7, 0xFF4B3A14);
        context.fill(left + 2, top + 1, left + 6, top + 2, 0xFFE8C95B);
        context.fill(left + 1, top + 2, left + 3, top + 4, 0xFFE8C95B);
        context.fill(left + 5, top + 2, left + 7, top + 4, 0xFFE8C95B);
        context.fill(left + 2, top + 2, left + 3, top + 3, 0xFF202735);
        context.fill(left + 5, top + 2, left + 6, top + 3, 0xFF202735);
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
                && isSupportedHandler(screen.getScreenHandler())) {
            currentScreenContainerKey = pendingContainerKey;
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
        if (client.currentScreen instanceof HandledScreen<?> screen
                && isSupportedHandler(screen.getScreenHandler())) {
            return;
        }

        currentScreenContainerKey = null;
    }

    private static String getCurrentScreenContainerKey(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.currentScreen != screen || !isSupportedHandler(screen.getScreenHandler())) {
            return null;
        }

        if (screen.getScreenHandler() instanceof PlayerScreenHandler) {
            return PLAYER_CONTAINER_KEY;
        }

        return currentScreenContainerKey;
    }

    private static boolean isSlotLocked(String key, int slotId) {
        if (key == null) {
            return false;
        }

        Set<Integer> slots = LOCKED_SLOTS.get(key);
        return slots != null && slots.contains(slotId);
    }

    private static boolean isSupportedHandler(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler
                || handler instanceof ShulkerBoxScreenHandler
                || handler instanceof PlayerScreenHandler;
    }

    private static String getSupportedContainerKey(MinecraftClient client, BlockHitResult blockHitResult) {
        if (client.world == null) {
            return null;
        }

        Block block = client.world.getBlockState(blockHitResult.getBlockPos()).getBlock();
        if (!(block instanceof ChestBlock)
                && !(block instanceof BarrelBlock)
                && !(block instanceof EnderChestBlock)
                && !(block instanceof ShulkerBoxBlock)) {
            return null;
        }

        return buildContainerKey(client.world, blockHitResult.getBlockPos().asLong());
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
}
