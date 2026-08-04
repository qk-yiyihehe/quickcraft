package com.yiyihehe.quickcraft.crafting;

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
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;

/**
 * 铁砧快速命名：按左槽物品和输出名批量重命名。
 */
public final class QuickCraftAnvilRename implements ClientModInitializer {
    private static final int RAPID_INTERVAL = 1;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final int INPUT_SLOT = AnvilMenu.INPUT_SLOT;
    private static final int ADDITION_SLOT = AnvilMenu.ADDITIONAL_SLOT;
    private static final int OUTPUT_SLOT = AnvilMenu.RESULT_SLOT;
    private static boolean consumeNextRenameHotkeyChar = false;

    private boolean lastVDown = false;
    private boolean lastAltCDown = false;
    private boolean rapidRenameActive = false;
    private int rapidCooldown = 0;
    private int consecutiveFailures = 0;
    private RenameSnapshot lockedSnapshot = null;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public static boolean shouldConsumeRenameHotkeyInput() {
        Minecraft client = Minecraft.getInstance();
        return QuickCraftConfigs.isAnvilRenameQuickCraftEnabled()
                && client != null
                && client.gui.screen() instanceof AnvilScreen
                && (QuickCraftConfigs.getSingleCraftHotkey().isKeybindHeld()
                || QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld());
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

        return QuickCraftConfigs.getSingleCraftHotkey().isKeybindHeld()
                || QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld();
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
        Minecraft client = Minecraft.getInstance();
        return QuickCraftConfigs.isAnvilRenameQuickCraftEnabled()
                && client != null
                && client.gui.screen() instanceof AnvilScreen;
    }

    private void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isAnvilRenameQuickCraftEnabled()) {
            resetAll();
            return;
        }

        if (!isAnvilContextValid(client)) {
            resetAll();
            return;
        }

        consumeNextRenameHotkeyChar = false;

        AnvilMenu handler = (AnvilMenu) client.player.containerMenu;
        handleHotkeys(client, handler);

        if (rapidRenameActive && lockedSnapshot != null) {
            rapidCooldown++;
            if (rapidCooldown >= RAPID_INTERVAL) {
                rapidCooldown = 0;
                processRapidRenameTick(client, handler, lockedSnapshot);
            }
        }
    }

    private void processRapidRenameTick(Minecraft client,
                                        AnvilMenu handler,
                                        RenameSnapshot snapshot) {
        boolean anyProgress = false;
        int loopsPerTick = QuickCraftConfigs.getCraftLoopsPerTick();

        for (int loop = 0; loop < loopsPerTick; loop++) {
            if (runOneRenameSubLoop(client, handler, snapshot)) {
                anyProgress = true;
            }
            if (!rapidRenameActive) {
                break;
            }
        }

        if (anyProgress) {
            consecutiveFailures = 0;
            return;
        }

        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            stopRapidRename(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
        }
    }

    private boolean runOneRenameSubLoop(Minecraft client,
                                        AnvilMenu handler,
                                        RenameSnapshot snapshot) {
        if (client.player == null || client.gameMode == null || client.getConnection() == null) {
            return false;
        }

        if (hasTargetOutput(handler, snapshot)) {
            return tryQuickMoveOutput(client, handler);
        }

        if (hasUnexpectedAddition(handler)) {
            return false;
        }

        if (!hasInput(handler)) {
            if (!quickMoveNextTargetToInput(client, handler, snapshot)) {
                if (rapidRenameActive) {
                    stopRapidRename(client, Component.translatable("quickcraft.message.crafting.no_ingredients"));
                }
                return false;
            }
        }

        ItemStack input = handler.getSlot(INPUT_SLOT).getItem();
        if (!snapshot.matchesTarget(input)) {
            return false;
        }

        if (!syncRenameText(client, handler, snapshot.targetName())) {
            return false;
        }

        if (!hasTargetOutput(handler, snapshot)) {
            return false;
        }

        return tryQuickMoveOutput(client, handler);
    }

    private void handleSingleRename(Minecraft client, AnvilMenu handler) {
        RenameSnapshot snapshot = captureSnapshot(handler);
        if (snapshot != null) {
            lockedSnapshot = snapshot;
        }

        if (lockedSnapshot == null) {
            return;
        }

        runOneRenameSubLoop(client, handler, lockedSnapshot);
    }

    private void handleHotkeys(Minecraft client, AnvilMenu handler) {
        boolean vDown = QuickCraftConfigs.getSingleCraftHotkey().isKeybindHeld();
        boolean rapidDown = QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld();

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

        rapidRenameActive = true;
        rapidCooldown = 0;
        consecutiveFailures = 0;
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

        return new RenameSnapshot(input.getItem(), originalName, targetName);
    }

    private boolean quickMoveNextTargetToInput(Minecraft client,
                                               AnvilMenu handler,
                                               RenameSnapshot snapshot) {
        int handlerSlot = findNextTargetHandlerSlot(handler, snapshot);
        if (handlerSlot == -1) {
            return false;
        }

        ItemStack beforeInput = handler.getSlot(INPUT_SLOT).getItem().copy();
        ItemStack beforeSource = handler.getSlot(handlerSlot).getItem().copy();
        clickSlot(client, handler, handlerSlot, 0, ContainerInput.QUICK_MOVE);

        ItemStack afterInput = handler.getSlot(INPUT_SLOT).getItem();
        ItemStack afterSource = handler.getSlot(handlerSlot).getItem();
        return !afterInput.isEmpty()
                || !ItemStack.isSameItemSameComponents(beforeInput, afterInput)
                || afterSource.getCount() != beforeSource.getCount();
    }

    private int findNextTargetHandlerSlot(AnvilMenu handler, RenameSnapshot snapshot) {
        for (int slotId = 3; slotId < handler.slots.size(); slotId++) {
            ItemStack stack = handler.getSlot(slotId).getItem();
            if (snapshot.matchesTarget(stack)) {
                return slotId;
            }
        }
        return -1;
    }

    private boolean syncRenameText(Minecraft client,
                                   AnvilMenu handler,
                                   String targetName) {
        if (handler.setItemName(targetName)) {
            client.getConnection().send(new ServerboundRenameItemPacket(targetName));
        }
        return true;
    }

    private boolean tryQuickMoveOutput(Minecraft client, AnvilMenu handler) {
        if (!hasOutput(handler)) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getItem().copy();
        clickSlot(client, handler, OUTPUT_SLOT, 0, ContainerInput.QUICK_MOVE);

        ItemStack after = handler.getSlot(OUTPUT_SLOT).getItem();
        return after.isEmpty()
                || !ItemStack.isSameItemSameComponents(before, after)
                || after.getCount() != before.getCount();
    }

    private boolean isAnvilContextValid(Minecraft client) {
        return client.player != null
                && client.level != null
                && client.gui.screen() instanceof AnvilScreen
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

    private String getStackRenameName(ItemStack stack) {
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
        if (client.player == null || client.gameMode == null) {
            return;
        }

        client.gameMode.handleContainerInput(
                handler.containerId,
                slotId,
                button,
                actionType,
                client.player
        );
    }

    private void stopRapidRename(Minecraft client, Component message) {
        rapidRenameActive = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        sendStatusMessage(client, message);
    }

    private void resetAll() {
        lastVDown = false;
        lastAltCDown = false;
        rapidRenameActive = false;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        lockedSnapshot = null;
        consumeNextRenameHotkeyChar = false;
    }

    private void sendStatusMessage(Minecraft client, Component message) {
        if (client.player != null) {
            client.player.sendOverlayMessage(message);
        }
    }

    private record RenameSnapshot(Item item, String originalName, String targetName) {
        private boolean matchesTarget(ItemStack stack) {
            return !stack.isEmpty()
                    && stack.is(item)
                    && getPlainRenameName(stack).equals(originalName)
                    && !originalName.equals(targetName);
        }

        private static String getPlainRenameName(ItemStack stack) {
            Component customName = stack.get(DataComponents.CUSTOM_NAME);
            if (customName != null) {
                return customName.getString();
            }
            return stack.getHoverName().getString();
        }
    }
}
