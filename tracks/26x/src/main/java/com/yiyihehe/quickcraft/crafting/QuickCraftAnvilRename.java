package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;

import static com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckRules.shouldEnterOutputSprayMode;

/**
 * 铁砧快速命名：按左槽物品和输出名批量重命名。
 */
public final class QuickCraftAnvilRename implements ClientModInitializer {
    private static final int INPUT_SLOT = AnvilMenu.INPUT_SLOT;
    private static final int ADDITION_SLOT = AnvilMenu.ADDITIONAL_SLOT;
    private static final int OUTPUT_SLOT = AnvilMenu.RESULT_SLOT;
    private static boolean consumeNextRenameHotkeyChar = false;
    private static QuickCraftAnvilRename instance;
    private static AnvilMenu renamingHandler;

    private final QuickCraftRateLimiter rateLimiter = new QuickCraftRateLimiter();
    private boolean lastVDown = false;
    private boolean lastAltCDown = false;
    private boolean rapidRenameActive = false;
    private boolean outputSprayMode;
    private boolean sprayInventoryCleared;
    private AnvilMenu sessionHandler;
    private RenameSnapshot lockedSnapshot = null;

    @Override
    public void onInitializeClient() {
        instance = this;
        QuickCraftAnvilAssistant.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean isRapidRenameActive() {
        return instance != null && instance.rapidRenameActive;
    }

    public static boolean shouldConsumeRenameHotkeyInput() {
        Minecraft client = Minecraft.getInstance();
        return QuickCraftAnvilAssistant.consumesHotkey(-1) || QuickCraftConfigs.isAnvilRenameQuickCraftEnabled()
                && client != null
                && QuickClientScreenAccess.currentScreen(client) instanceof AnvilScreen
                && (QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getSingleCraftHotkey())
                || QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey()));
    }

    public static boolean shouldConsumeRenameHotkeyKeyPress(int keyCode) {
        if (QuickCraftAnvilAssistant.consumesHotkey(keyCode)) {
            consumeNextRenameHotkeyChar = true;
            return true;
        }
        if (!isAnvilRenameScreenActive()) {
            consumeNextRenameHotkeyChar = false;
            return false;
        }

        boolean singleCraftKeyPressed = QuickCraftConfigs.getSingleCraftHotkey().matches(keyCode);
        if (singleCraftKeyPressed) {
            consumeNextRenameHotkeyChar = true;
            return true;
        }

        return QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getSingleCraftHotkey())
                || QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());
    }

    public static boolean consumePendingRenameHotkeyChar() {
        if (!consumeNextRenameHotkeyChar || !(QuickClientScreenAccess.currentScreen(Minecraft.getInstance()) instanceof AnvilScreen)) {
            consumeNextRenameHotkeyChar = false;
            return false;
        }

        consumeNextRenameHotkeyChar = false;
        return true;
    }

    private static boolean isAnvilRenameScreenActive() {
        Minecraft client = Minecraft.getInstance();
        return QuickCraftConfigs.isAnvilRenameQuickCraftEnabled()
                && client != null
                && QuickClientScreenAccess.currentScreen(client) instanceof AnvilScreen;
    }

    public static boolean isRenamingBatch(AbstractContainerMenu handler) {
        return renamingHandler == handler;
    }

    public static void onServerContainerUpdate(int syncId, int slotId) {
        QuickCraftAnvilRename active = instance;
        if (active == null || !active.rapidRenameActive || active.sessionHandler == null
                || active.sessionHandler.containerId != syncId) return;
        // 预期的改名结果包不阻塞发送；输入纠正或全量同步后不再按旧库存继续。
        if (slotId < 0 || slotId == INPUT_SLOT || slotId == ADDITION_SLOT) {
            // 纠正后只停止，不按旧快照执行结束丢弃。
            active.rapidRenameActive = false;
            active.stopRapidRename(Minecraft.getInstance(), Component.translatable("quickcraft.message.crafting.stopped"));
        }
    }

    private void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isAnvilRenameQuickCraftEnabled() || !isAnvilContextValid(client)) {
            resetAll();
            return;
        }
        AnvilMenu handler = (AnvilMenu) client.player.containerMenu;
        if (sessionHandler != handler) {
            resetAll();
            sessionHandler = handler;
        }
        consumeNextRenameHotkeyChar = false;
        if (QuickCraftAnvilAssistant.ownsAnvil()) {
            lastVDown = lastAltCDown = false;
            return;
        }
        handleHotkeys(client, handler);
        if (rapidRenameActive) processRapidRename(client, handler, lockedSnapshot);
    }

    private void processRapidRename(Minecraft client, AnvilMenu handler, RenameSnapshot snapshot) {
        int rate = QuickCraftConfigs.getCraftsPerSecond();
        while (rapidRenameActive && rateLimiter.isReady(rate, System.nanoTime())) {
            if (!QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey())) {
                stopRapidRename(client, Component.translatable("quickcraft.message.crafting.stopped"));
                return;
            }
            long dispatchNanos = System.nanoTime();
            if (!renameBatch(client, handler, snapshot)) {
                stopRapidRename(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
                return;
            }
            rateLimiter.recordDispatch(rate, dispatchNanos);
        }
        rateLimiter.resumeWhenReady(rate, client, () -> {
            if (rapidRenameActive && lockedSnapshot == snapshot && sessionHandler == handler
                    && QuickCraftConfigs.isAnvilRenameQuickCraftEnabled() && isAnvilContextValid(client)
                    && client.player.containerMenu == handler) {
                processRapidRename(client, handler, snapshot);
            }
        });
    }

    private boolean renameBatch(Minecraft client, AnvilMenu handler, RenameSnapshot snapshot) {
        if (!handler.getCarried().isEmpty() || hasUnexpectedAddition(handler)
                || QuickContainerLock.isLockedSlot(handler, INPUT_SLOT)
                || QuickContainerLock.isLockedSlot(handler, OUTPUT_SLOT)) return false;

        renamingHandler = handler;
        try {
            if (!hasInput(handler)) {
                int source = findNextTargetHandlerSlot(handler, snapshot);
                if (source < 0) return false;
                clickSlot(client, handler, source, 0, ContainerInput.QUICK_MOVE);
            }
            if (!snapshot.matchesTarget(handler.getSlot(INPUT_SLOT).getItem())) return false;

            if (handler.setItemName(snapshot.targetName())) {
                client.getConnection().send(new ServerboundRenameItemPacket(snapshot.targetName()));
            }
            // 同名时 setter 不重算；显式重算也排除迟到的结果/费用包影响本批判断。
            handler.createResult();
            if (!hasTargetOutput(handler, snapshot) || !handler.getSlot(OUTPUT_SLOT).mayPickup(client.player)) {
                return false;
            }
            moveOrThrowOutput(client, handler, snapshot);
            return !hasInput(handler) && !hasOutput(handler) && handler.getCarried().isEmpty();
        } finally {
            renamingHandler = null;
        }
    }

    private void resetOutputPolicy(AnvilMenu handler) {
        // 初始满包锁定喷射，其余批次按补料后的实际空格判断。
        outputSprayMode = unlockedEmptySlots(handler) == 0;
        sprayInventoryCleared = false;
    }

    private void moveOrThrowOutput(Minecraft client, AnvilMenu handler, RenameSnapshot snapshot) {
        ItemStack output = handler.getSlot(OUTPUT_SLOT).getItem();
        int target = findAcceptingSlot(handler, output);
        if (shouldEnterOutputSprayMode(outputSprayMode, unlockedEmptySlots(handler), target >= 0)) {
            activateOutputSprayMode(client, handler, snapshot);
            clickSlot(client, handler, OUTPUT_SLOT, 1, ContainerInput.THROW);
            return;
        }
        if (!hasLockedPlayerSlots(handler)) {
            clickSlot(client, handler, OUTPUT_SLOT, 0, ContainerInput.QUICK_MOVE);
        } else {
            // 显式放入未锁定槽位，避免原版 Shift 转移合并进锁格。
            ItemStack expected = output.copy();
            clickSlot(client, handler, OUTPUT_SLOT, 0, ContainerInput.PICKUP);
            ItemStack carried = handler.getCarried();
            if (!ItemStack.isSameItemSameComponents(carried, expected) || carried.getCount() != expected.getCount()) return;
            clickSlot(client, handler, target, 0, ContainerInput.PICKUP);
        }
        if (!hasInput(handler) && !hasOutput(handler) && handler.getCarried().isEmpty()
                && shouldEnterOutputSprayMode(false, unlockedEmptySlots(handler), true)) {
            activateOutputSprayMode(client, handler, snapshot);
        }
    }

    private void activateOutputSprayMode(Minecraft client, AnvilMenu handler, RenameSnapshot snapshot) {
        outputSprayMode = true;
        if (!sprayInventoryCleared) {
            sprayInventoryCleared = true;
            dropInventoryResults(client, handler, snapshot);
        }
    }

    private int unlockedEmptySlots(AnvilMenu handler) {
        int count = 0;
        for (int i = OUTPUT_SLOT + 1; i < handler.slots.size(); i++) {
            if (!QuickContainerLock.isLockedSlot(handler, i) && !handler.getSlot(i).hasItem()) count++;
        }
        return count;
    }

    private boolean hasLockedPlayerSlots(AnvilMenu handler) {
        for (int i = OUTPUT_SLOT + 1; i < handler.slots.size(); i++) {
            if (QuickContainerLock.isLockedSlot(handler, i)) return true;
        }
        return false;
    }

    private int findAcceptingSlot(AnvilMenu handler, ItemStack output) {
        int empty = -1;
        for (int i = OUTPUT_SLOT + 1; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (QuickContainerLock.isLockedSlot(handler, slot) || !slot.mayPlace(output)) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                if (empty < 0 && output.getCount() <= slot.getMaxStackSize(output)) empty = i;
            } else if (ItemStack.isSameItemSameComponents(stack, output)
                    && stack.getCount() + output.getCount() <= slot.getMaxStackSize(output)) {
                return i;
            }
        }
        return empty;
    }

    private void dropInventoryResults(Minecraft client, AnvilMenu handler, RenameSnapshot snapshot) {
        if (!handler.getCarried().isEmpty()) return;
        for (int i = OUTPUT_SLOT + 1; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (!stack.isEmpty() && !QuickContainerLock.isLockedSlot(handler, i)
                    && ItemStack.isSameItemSameComponents(stack, snapshot.resultTemplate())) {
                clickSlot(client, handler, i, 1, ContainerInput.THROW);
            }
        }
    }

    private void handleSingleRename(Minecraft client, AnvilMenu handler) {
        RenameSnapshot snapshot = captureSnapshot(handler);
        if (snapshot != null) {
            lockedSnapshot = snapshot;
        }

        if (lockedSnapshot == null) {
            return;
        }

        resetOutputPolicy(handler);
        renameBatch(client, handler, lockedSnapshot);
    }

    private void handleHotkeys(Minecraft client, AnvilMenu handler) {
        boolean vDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getSingleCraftHotkey());
        boolean rapidDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());

        if (vDown && !lastVDown) {
            handleSingleRename(client, handler);
        }

        if (rapidDown && !lastAltCDown) {
            startRapidRename(client, handler);
        }

        if (!rapidDown && rapidRenameActive) {
            stopRapidRename(client, Component.translatable("quickcraft.message.crafting.stopped"));
        }

        lastVDown = vDown;
        lastAltCDown = rapidDown;
    }

    private void startRapidRename(Minecraft client, AnvilMenu handler) {
        RenameSnapshot snapshot = captureSnapshot(handler);
        if (snapshot != null) {
            lockedSnapshot = snapshot;
        }

        if (lockedSnapshot == null) {
            rapidRenameActive = false;
            return;
        }

        resetOutputPolicy(handler);
        rapidRenameActive = true;
        sendStatusMessage(client, Component.translatable("quickcraft.message.crafting.started"));
    }

    private RenameSnapshot captureSnapshot(AnvilMenu handler) {
        if (handler == null || hasUnexpectedAddition(handler) || !hasInput(handler) || !hasOutput(handler)) {
            return null;
        }

        ItemStack input = handler.getSlot(INPUT_SLOT).getItem();
        ItemStack output = handler.getSlot(OUTPUT_SLOT).getItem();
        if (input.isEmpty() || output.isEmpty() || !ItemStack.isSameItem(input, output)) {
            return null;
        }

        String originalName = getStackRenameName(input);
        String targetName = getStackRenameName(output);
        if (targetName.isBlank() || targetName.equals(originalName)) {
            return null;
        }

        return new RenameSnapshot(input.getItem(), originalName, targetName, output.copy());
    }

    private int findNextTargetHandlerSlot(AnvilMenu handler, RenameSnapshot snapshot) {
        for (int slotId = 3; slotId < handler.slots.size(); slotId++) {
            Slot slot = handler.getSlot(slotId);
            if (QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (snapshot.matchesTarget(stack)) {
                return slotId;
            }
        }
        return -1;
    }

    private boolean isAnvilContextValid(Minecraft client) {
        return client.player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null
                && QuickClientScreenAccess.currentScreen(client) instanceof AnvilScreen
                && client.player.containerMenu instanceof AnvilMenu;
    }

    private boolean hasInput(AnvilMenu handler) {
        return handler.getSlot(INPUT_SLOT).hasItem();
    }

    private boolean hasOutput(AnvilMenu handler) {
        return handler.getSlot(OUTPUT_SLOT).hasItem();
    }

    private boolean hasTargetOutput(AnvilMenu handler, RenameSnapshot snapshot) {
        ItemStack output = handler.getSlot(OUTPUT_SLOT).getItem();
        return !output.isEmpty()
                && output.is(snapshot.item())
                && getStackRenameName(output).equals(snapshot.targetName());
    }

    private boolean hasUnexpectedAddition(AnvilMenu handler) {
        return handler.getSlot(ADDITION_SLOT).hasItem();
    }

    private static String getStackRenameName(ItemStack stack) {
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
            return customName.getString();
        }
        return stack.getHoverName().getString();
    }

    private void clickSlot(Minecraft client,
                           AbstractContainerMenu handler,
                           int slotId,
                           int button,
                           ContainerInput actionType) {
        client.gameMode.handleContainerInput(
                handler.containerId,
                slotId,
                button,
                actionType,
                client.player
        );
    }

    private void stopRapidRename(Minecraft client, Component message) {
        if (rapidRenameActive && lockedSnapshot != null && QuickCraftConfigs.isAnvilRenameQuickCraftEnabled()
                && isAnvilContextValid(client) && client.player.containerMenu == sessionHandler
                && QuickCraftConfigs.isDropCraftResultsOnStopEnabled()) {
            dropInventoryResults(client, sessionHandler, lockedSnapshot);
        }
        rapidRenameActive = false;
        sendStatusMessage(client, message);
    }

    private void resetAll() {
        lastVDown = false;
        lastAltCDown = false;
        rapidRenameActive = false;
        lockedSnapshot = null;
        sessionHandler = null;
        outputSprayMode = false;
        sprayInventoryCleared = false;
        consumeNextRenameHotkeyChar = false;
    }

    private void sendStatusMessage(Minecraft client, Component message) {
        if (client.player != null) {
            client.player.sendOverlayMessage(message);
        }
    }

    private record RenameSnapshot(Item item, String originalName, String targetName, ItemStack resultTemplate) {
        private boolean matchesTarget(ItemStack stack) {
            return !stack.isEmpty()
                    && stack.is(item)
                    && getStackRenameName(stack).equals(originalName);
        }
    }
}
