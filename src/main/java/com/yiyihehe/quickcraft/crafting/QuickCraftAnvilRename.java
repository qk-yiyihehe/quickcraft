package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.RenameItemC2SPacket;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import static com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckRules.shouldEnterOutputSprayMode;

/**
 * 铁砧快速命名：按左槽物品和输出名批量重命名。
 */
public final class QuickCraftAnvilRename implements ClientModInitializer {
    private static final int INPUT_SLOT = AnvilScreenHandler.INPUT_1_ID;
    private static final int ADDITION_SLOT = AnvilScreenHandler.INPUT_2_ID;
    private static final int OUTPUT_SLOT = AnvilScreenHandler.OUTPUT_ID;
    private static boolean consumeNextRenameHotkeyChar = false;
    private static QuickCraftAnvilRename instance;
    private static AnvilScreenHandler renamingHandler;

    private final QuickCraftRateLimiter rateLimiter = new QuickCraftRateLimiter();
    private boolean lastVDown = false;
    private boolean lastAltCDown = false;
    private boolean rapidRenameActive = false;
    private boolean outputSprayMode;
    private boolean sprayInventoryCleared;
    private AnvilScreenHandler sessionHandler;
    private RenameSnapshot lockedSnapshot = null;

    @Override
    public void onInitializeClient() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean shouldConsumeRenameHotkeyInput() {
        MinecraftClient client = MinecraftClient.getInstance();
        return QuickCraftConfigs.isAnvilRenameQuickCraftEnabled()
                && client != null
                && client.currentScreen instanceof AnvilScreen
                && (QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getSingleCraftHotkey())
                || QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey()));
    }

    public static boolean shouldConsumeRenameHotkeyKeyPress(int keyCode) {
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
        if (!consumeNextRenameHotkeyChar || !isAnvilRenameScreenActive()) {
            consumeNextRenameHotkeyChar = false;
            return false;
        }

        consumeNextRenameHotkeyChar = false;
        return true;
    }

    private static boolean isAnvilRenameScreenActive() {
        MinecraftClient client = MinecraftClient.getInstance();
        return QuickCraftConfigs.isAnvilRenameQuickCraftEnabled()
                && client != null
                && client.currentScreen instanceof AnvilScreen;
    }

    public static boolean isRenamingBatch(ScreenHandler handler) {
        return renamingHandler == handler;
    }

    public static void onServerContainerUpdate(int syncId, int slotId) {
        QuickCraftAnvilRename active = instance;
        if (active == null || !active.rapidRenameActive || active.sessionHandler == null
                || active.sessionHandler.syncId != syncId) return;
        // 预期的改名结果包不阻塞发送；输入纠正或全量同步后不再按旧库存继续。
        if (slotId < 0 || slotId == INPUT_SLOT || slotId == ADDITION_SLOT) {
            // 纠正后只停止，不按旧快照执行结束丢弃。
            active.rapidRenameActive = false;
            active.stopRapidRename(MinecraftClient.getInstance(), Text.translatable("quickcraft.message.crafting.stopped"));
        }
    }

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isAnvilRenameQuickCraftEnabled() || !isAnvilContextValid(client)) {
            resetAll();
            return;
        }
        AnvilScreenHandler handler = (AnvilScreenHandler) client.player.currentScreenHandler;
        if (sessionHandler != handler) {
            resetAll();
            sessionHandler = handler;
        }
        consumeNextRenameHotkeyChar = false;
        handleHotkeys(client, handler);
        if (rapidRenameActive) processRapidRename(client, handler, lockedSnapshot);
    }

    private void processRapidRename(MinecraftClient client, AnvilScreenHandler handler, RenameSnapshot snapshot) {
        int rate = QuickCraftConfigs.getCraftsPerSecond();
        while (rapidRenameActive && rateLimiter.isReady(rate, System.nanoTime())) {
            if (!QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey())) {
                stopRapidRename(client, Text.translatable("quickcraft.message.crafting.stopped"));
                return;
            }
            long dispatchNanos = System.nanoTime();
            if (!renameBatch(client, handler, snapshot)) {
                stopRapidRename(client, Text.translatable("quickcraft.message.crafting.no_ingredients"));
                return;
            }
            rateLimiter.recordDispatch(rate, dispatchNanos);
        }
        rateLimiter.resumeWhenReady(rate, client, () -> {
            if (rapidRenameActive && lockedSnapshot == snapshot && sessionHandler == handler
                    && QuickCraftConfigs.isAnvilRenameQuickCraftEnabled() && isAnvilContextValid(client)
                    && client.player.currentScreenHandler == handler) {
                processRapidRename(client, handler, snapshot);
            }
        });
    }

    private boolean renameBatch(MinecraftClient client, AnvilScreenHandler handler, RenameSnapshot snapshot) {
        if (!handler.getCursorStack().isEmpty() || hasUnexpectedAddition(handler)
                || QuickContainerLock.isLockedSlot(handler, INPUT_SLOT)
                || QuickContainerLock.isLockedSlot(handler, OUTPUT_SLOT)) return false;

        renamingHandler = handler;
        try {
            if (!hasInput(handler)) {
                int source = findNextTargetHandlerSlot(handler, snapshot);
                if (source < 0) return false;
                clickSlot(client, handler, source, 0, SlotActionType.QUICK_MOVE);
            }
            if (!snapshot.matchesTarget(handler.getSlot(INPUT_SLOT).getStack())) return false;

            if (handler.setNewItemName(snapshot.targetName())) {
                client.getNetworkHandler().sendPacket(new RenameItemC2SPacket(snapshot.targetName()));
            }
            // 同名时 setter 不重算；显式重算也排除迟到的结果/费用包影响本批判断。
            handler.updateResult();
            if (!hasTargetOutput(handler, snapshot) || !handler.getSlot(OUTPUT_SLOT).canTakeItems(client.player)) {
                return false;
            }
            moveOrThrowOutput(client, handler, snapshot);
            return !hasInput(handler) && !hasOutput(handler) && handler.getCursorStack().isEmpty();
        } finally {
            renamingHandler = null;
        }
    }

    private void resetOutputPolicy(AnvilScreenHandler handler) {
        // 初始满包锁定喷射，其余批次按补料后的实际空格判断。
        outputSprayMode = unlockedEmptySlots(handler) == 0;
        sprayInventoryCleared = false;
    }

    private void moveOrThrowOutput(MinecraftClient client, AnvilScreenHandler handler, RenameSnapshot snapshot) {
        ItemStack output = handler.getSlot(OUTPUT_SLOT).getStack();
        int target = findAcceptingSlot(handler, output);
        if (shouldEnterOutputSprayMode(outputSprayMode, unlockedEmptySlots(handler), target >= 0)) {
            activateOutputSprayMode(client, handler, snapshot);
            clickSlot(client, handler, OUTPUT_SLOT, 1, SlotActionType.THROW);
            return;
        }
        if (!hasLockedPlayerSlots(handler)) {
            clickSlot(client, handler, OUTPUT_SLOT, 0, SlotActionType.QUICK_MOVE);
        } else {
            // 显式放入未锁定槽位，避免原版 Shift 转移合并进锁格。
            ItemStack expected = output.copy();
            clickSlot(client, handler, OUTPUT_SLOT, 0, SlotActionType.PICKUP);
            ItemStack carried = handler.getCursorStack();
            if (!ItemStack.areItemsAndComponentsEqual(carried, expected) || carried.getCount() != expected.getCount()) return;
            clickSlot(client, handler, target, 0, SlotActionType.PICKUP);
        }
        if (!hasInput(handler) && !hasOutput(handler) && handler.getCursorStack().isEmpty()
                && shouldEnterOutputSprayMode(false, unlockedEmptySlots(handler), true)) {
            activateOutputSprayMode(client, handler, snapshot);
        }
    }

    private void activateOutputSprayMode(MinecraftClient client, AnvilScreenHandler handler, RenameSnapshot snapshot) {
        outputSprayMode = true;
        if (!sprayInventoryCleared) {
            sprayInventoryCleared = true;
            dropInventoryResults(client, handler, snapshot);
        }
    }

    private int unlockedEmptySlots(AnvilScreenHandler handler) {
        int count = 0;
        for (int i = OUTPUT_SLOT + 1; i < handler.slots.size(); i++) {
            if (!QuickContainerLock.isLockedSlot(handler, i) && !handler.getSlot(i).hasStack()) count++;
        }
        return count;
    }

    private boolean hasLockedPlayerSlots(AnvilScreenHandler handler) {
        for (int i = OUTPUT_SLOT + 1; i < handler.slots.size(); i++) {
            if (QuickContainerLock.isLockedSlot(handler, i)) return true;
        }
        return false;
    }

    private int findAcceptingSlot(AnvilScreenHandler handler, ItemStack output) {
        int empty = -1;
        for (int i = OUTPUT_SLOT + 1; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (QuickContainerLock.isLockedSlot(handler, slot) || !slot.canInsert(output)) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                if (empty < 0 && output.getCount() <= slot.getMaxItemCount(output)) empty = i;
            } else if (ItemStack.areItemsAndComponentsEqual(stack, output)
                    && stack.getCount() + output.getCount() <= slot.getMaxItemCount(output)) {
                return i;
            }
        }
        return empty;
    }

    private void dropInventoryResults(MinecraftClient client, AnvilScreenHandler handler, RenameSnapshot snapshot) {
        if (!handler.getCursorStack().isEmpty()) return;
        for (int i = OUTPUT_SLOT + 1; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && !QuickContainerLock.isLockedSlot(handler, i)
                    && ItemStack.areItemsAndComponentsEqual(stack, snapshot.resultTemplate())) {
                clickSlot(client, handler, i, 1, SlotActionType.THROW);
            }
        }
    }

    private void handleSingleRename(MinecraftClient client, AnvilScreenHandler handler) {
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

    private void handleHotkeys(MinecraftClient client, AnvilScreenHandler handler) {
        boolean vDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getSingleCraftHotkey());
        boolean rapidDown = QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.getRapidCraftHotkey());

        if (vDown && !lastVDown) {
            handleSingleRename(client, handler);
        }

        if (rapidDown && !lastAltCDown) {
            startRapidRename(client, handler);
        }

        if (!rapidDown && rapidRenameActive) {
            stopRapidRename(client, Text.translatable("quickcraft.message.crafting.stopped"));
        }

        lastVDown = vDown;
        lastAltCDown = rapidDown;
    }

    private void startRapidRename(MinecraftClient client, AnvilScreenHandler handler) {
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
        sendStatusMessage(client, Text.translatable("quickcraft.message.crafting.started"));
    }

    private RenameSnapshot captureSnapshot(AnvilScreenHandler handler) {
        if (handler == null || hasUnexpectedAddition(handler) || !hasInput(handler) || !hasOutput(handler)) {
            return null;
        }

        ItemStack input = handler.getSlot(INPUT_SLOT).getStack();
        ItemStack output = handler.getSlot(OUTPUT_SLOT).getStack();
        if (input.isEmpty() || output.isEmpty() || !ItemStack.areItemsEqual(input, output)) {
            return null;
        }

        String originalName = getStackRenameName(input);
        String targetName = getStackRenameName(output);
        if (targetName.isBlank() || targetName.equals(originalName)) {
            return null;
        }

        return new RenameSnapshot(input.getItem(), originalName, targetName, output.copy());
    }

    private int findNextTargetHandlerSlot(AnvilScreenHandler handler, RenameSnapshot snapshot) {
        for (int slotId = 3; slotId < handler.slots.size(); slotId++) {
            Slot slot = handler.getSlot(slotId);
            if (QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            ItemStack stack = slot.getStack();
            if (snapshot.matchesTarget(stack)) {
                return slotId;
            }
        }
        return -1;
    }

    private boolean isAnvilContextValid(MinecraftClient client) {
        return client.player != null
                && client.world != null
                && client.interactionManager != null
                && client.getNetworkHandler() != null
                && client.currentScreen instanceof AnvilScreen
                && client.player.currentScreenHandler instanceof AnvilScreenHandler;
    }

    private boolean hasInput(AnvilScreenHandler handler) {
        return handler.getSlot(INPUT_SLOT).hasStack();
    }

    private boolean hasOutput(AnvilScreenHandler handler) {
        return handler.getSlot(OUTPUT_SLOT).hasStack();
    }

    private boolean hasTargetOutput(AnvilScreenHandler handler, RenameSnapshot snapshot) {
        ItemStack output = handler.getSlot(OUTPUT_SLOT).getStack();
        return !output.isEmpty()
                && output.isOf(snapshot.item())
                && getStackRenameName(output).equals(snapshot.targetName());
    }

    private boolean hasUnexpectedAddition(AnvilScreenHandler handler) {
        return handler.getSlot(ADDITION_SLOT).hasStack();
    }

    private static String getStackRenameName(ItemStack stack) {
        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (customName != null) {
            return customName.getString();
        }
        return stack.getName().getString();
    }

    private void clickSlot(MinecraftClient client,
                           ScreenHandler handler,
                           int slotId,
                           int button,
                           SlotActionType actionType) {
        client.interactionManager.clickSlot(
                handler.syncId,
                slotId,
                button,
                actionType,
                client.player
        );
    }

    private void stopRapidRename(MinecraftClient client, Text message) {
        if (rapidRenameActive && lockedSnapshot != null && QuickCraftConfigs.isAnvilRenameQuickCraftEnabled()
                && isAnvilContextValid(client) && client.player.currentScreenHandler == sessionHandler
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

    private void sendStatusMessage(MinecraftClient client, Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    private record RenameSnapshot(Item item, String originalName, String targetName, ItemStack resultTemplate) {
        private boolean matchesTarget(ItemStack stack) {
            return !stack.isEmpty()
                    && stack.isOf(item)
                    && getStackRenameName(stack).equals(originalName);
        }
    }
}
