package com.yiyihehe.quickcraft.crafting;

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
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

/**
 * 铁砧快速命名的客户端入口。
 *
 * <p>本类只负责铁砧界面里的单次/连续重命名：从当前左槽和输出槽锁定“原名称 -> 目标名称”，
 * 再用原版槽位点击和 {@link RenameItemC2SPacket} 批量处理同类物品。按钮注入、配置定义和翻译文本
 * 由其它包负责。</p>
 */
public final class QuickCraftAnvilRename implements ClientModInitializer {
    // 每 tick 驱动一次；实际单 tick 操作次数由 craftLoopsPerTick 配置决定。
    private static final int RAPID_INTERVAL = 1;
    // 连续几轮没有移动物品或生成输出时停止，避免界面状态异常时一直发点击包。
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    // 1.21/1.21.1 的 AnvilScreenHandler 固定槽位：0 左输入，1 副输入，2 输出。
    private static final int INPUT_SLOT = AnvilScreenHandler.INPUT_1_ID;
    private static final int ADDITION_SLOT = AnvilScreenHandler.INPUT_2_ID;
    private static final int OUTPUT_SLOT = AnvilScreenHandler.OUTPUT_ID;

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
        MinecraftClient client = MinecraftClient.getInstance();
        return QuickCraftConfigs.isAnvilRenameQuickCraftEnabled()
                && client != null
                && client.currentScreen instanceof AnvilScreen
                && (QuickCraftConfigs.getSingleCraftHotkey().isKeybindHeld()
                || QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld());
    }

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isAnvilRenameQuickCraftEnabled()) {
            resetAll();
            return;
        }

        if (!isAnvilContextValid(client)) {
            resetAll();
            return;
        }

        AnvilScreenHandler handler = (AnvilScreenHandler) client.player.currentScreenHandler;
        handleHotkeys(client, handler);

        if (rapidRenameActive && lockedSnapshot != null) {
            rapidCooldown++;
            if (rapidCooldown >= RAPID_INTERVAL) {
                rapidCooldown = 0;
                processRapidRenameTick(client, handler, lockedSnapshot);
            }
        }
    }

    private void processRapidRenameTick(MinecraftClient client,
                                        AnvilScreenHandler handler,
                                        RenameSnapshot snapshot) {
        boolean anyProgress = false;
        int loopsPerTick = QuickCraftConfigs.getCraftLoopsPerTick();

        for (int loop = 0; loop < loopsPerTick; loop++) {
            if (runOneRenameSubLoop(client, handler, snapshot)) {
                anyProgress = true;
            }
        }

        if (anyProgress) {
            consecutiveFailures = 0;
            return;
        }

        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            stopRapidRename(client, Text.translatable("quickcraft.message.anvil_rename.no_progress"));
        }
    }

    /**
     * 执行一次“补左槽 -> 同步目标名称 -> 取输出”的最小循环。
     *
     * <p>副槽必须保持为空；这个功能只处理纯重命名，不接管修复、材料消耗或附魔合并。</p>
     */
    private boolean runOneRenameSubLoop(MinecraftClient client,
                                        AnvilScreenHandler handler,
                                        RenameSnapshot snapshot) {
        if (client.player == null || client.interactionManager == null || client.getNetworkHandler() == null) {
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
                return false;
            }
        }

        ItemStack input = handler.getSlot(INPUT_SLOT).getStack();
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

    private void handleSingleRename(MinecraftClient client, AnvilScreenHandler handler) {
        RenameSnapshot snapshot = captureSnapshot(handler);
        if (snapshot != null) {
            lockedSnapshot = snapshot;
        }

        if (lockedSnapshot == null) {
            return;
        }

        runOneRenameSubLoop(client, handler, lockedSnapshot);
    }

    private void handleHotkeys(MinecraftClient client, AnvilScreenHandler handler) {
        boolean vDown = QuickCraftConfigs.getSingleCraftHotkey().isKeybindHeld();
        boolean rapidDown = QuickCraftConfigs.getRapidCraftHotkey().isKeybindHeld();

        if (vDown && !lastVDown) {
            handleSingleRename(client, handler);
        }

        if (rapidDown && !lastAltCDown) {
            startRapidRename(client, handler);
        }

        if (!rapidDown && rapidRenameActive) {
            stopRapidRename(client, Text.translatable("quickcraft.message.anvil_rename.stopped"));
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

        rapidRenameActive = true;
        rapidCooldown = 0;
        consecutiveFailures = 0;
        sendStatusMessage(client, Text.translatable("quickcraft.message.anvil_rename.started"));
    }

    /**
     * 从当前铁砧状态建立重命名计划。
     *
     * <p>只有左槽和输出槽是同一种物品，且输出名确实不同，才认为玩家已经用原版铁砧确认了目标名称。</p>
     */
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

        return new RenameSnapshot(input.getItem(), originalName, targetName);
    }

    private boolean quickMoveNextTargetToInput(MinecraftClient client,
                                               AnvilScreenHandler handler,
                                               RenameSnapshot snapshot) {
        int handlerSlot = findNextTargetHandlerSlot(handler, snapshot);
        if (handlerSlot == -1) {
            return false;
        }

        ItemStack beforeInput = handler.getSlot(INPUT_SLOT).getStack().copy();
        ItemStack beforeSource = handler.getSlot(handlerSlot).getStack().copy();
        clickSlot(client, handler, handlerSlot, 0, SlotActionType.QUICK_MOVE);

        ItemStack afterInput = handler.getSlot(INPUT_SLOT).getStack();
        ItemStack afterSource = handler.getSlot(handlerSlot).getStack();
        return !afterInput.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(beforeInput, afterInput)
                || afterSource.getCount() != beforeSource.getCount();
    }

    private int findNextTargetHandlerSlot(AnvilScreenHandler handler, RenameSnapshot snapshot) {
        for (int slotId = 3; slotId < handler.slots.size(); slotId++) {
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (snapshot.matchesTarget(stack)) {
                return slotId;
            }
        }
        return -1;
    }

    private boolean syncRenameText(MinecraftClient client,
                                   AnvilScreenHandler handler,
                                   String targetName) {
        // setNewItemName 只改本地 handler，RenameItemC2SPacket 才会让服务端按同一个名称重新计算输出。
        if (handler.setNewItemName(targetName)) {
            client.getNetworkHandler().sendPacket(new RenameItemC2SPacket(targetName));
        }
        return true;
    }

    private boolean tryQuickMoveOutput(MinecraftClient client, AnvilScreenHandler handler) {
        if (!hasOutput(handler)) {
            return false;
        }

        ItemStack before = handler.getSlot(OUTPUT_SLOT).getStack().copy();
        clickSlot(client, handler, OUTPUT_SLOT, 0, SlotActionType.QUICK_MOVE);

        ItemStack after = handler.getSlot(OUTPUT_SLOT).getStack();
        return after.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(before, after)
                || after.getCount() != before.getCount();
    }

    private boolean isAnvilContextValid(MinecraftClient client) {
        return client.player != null
                && client.world != null
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

    private String getStackRenameName(ItemStack stack) {
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
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                slotId,
                button,
                actionType,
                client.player
        );
    }

    private void stopRapidRename(MinecraftClient client, Text message) {
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
    }

    private void sendStatusMessage(MinecraftClient client, Text message) {
        if (client.player != null) {
            client.player.sendMessage(message, true);
        }
    }

    /**
     * 连续重命名期间锁定的计划。
     *
     * <p>这里只记录物品类型和名称，不记录整份组件：铁砧重命名会改变组件，锁得太细会把刚生成的输出
     * 误判成不可继续处理的输入。副槽有物品时不建立快照，避免把修复、合并附魔等铁砧行为混进“只改名”的流程。</p>
     */
    private record RenameSnapshot(Item item, String originalName, String targetName) {
        private boolean matchesTarget(ItemStack stack) {
            return !stack.isEmpty()
                    && stack.isOf(item)
                    && !getPlainRenameName(stack).equals(targetName);
        }

        private static String getPlainRenameName(ItemStack stack) {
            Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
            if (customName != null) {
                return customName.getString();
            }
            return stack.getName().getString();
        }
    }
}
