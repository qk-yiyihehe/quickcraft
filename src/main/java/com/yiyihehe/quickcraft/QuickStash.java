package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 容器一键回存。
 *
 * <p>玩家右键支持的容器后，等待原版容器界面打开，再把玩家背包内“容器已有种类”的同类物品尽量放回去，
 * 完成后自动关闭界面。投影自动填充和材料收集优先级更高，命中那些目标时这里不会接管右键。</p>
 */
public final class QuickStash implements ClientModInitializer {
    // 右键后最多等 20 tick。超过 1 秒仍没打开容器，认为本次交互不属于回存流程。
    private static final int OPEN_TIMEOUT_TICKS = 20;

    private static boolean lastUseDown;
    private static boolean pendingOpen;
    private static int pendingTicks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isQuickStashEnabled()) {
            pendingOpen = false;
            pendingTicks = 0;
        }

        handleUseAttempt(client);
        processPendingOpen(client);
    }

    private void handleUseAttempt(MinecraftClient client) {
        if (!QuickCraftConfigs.isQuickStashEnabled() || client.player == null || client.world == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isBoundKeyDown(client, client.options.useKey);
        if (useDown
                && !lastUseDown
                && client.currentScreen == null
                && !QuickMaterialCollector.shouldHandleCurrentTarget(client)
                && !QuickLitematicaContainerAutofill.shouldHandleCurrentTarget(client)
                && isLookingAtSupportedBlock(client)) {
            pendingOpen = true;
            pendingTicks = 0;
        }

        lastUseDown = useDown;
    }

    private void processPendingOpen(MinecraftClient client) {
        if (!pendingOpen) {
            return;
        }

        pendingTicks++;
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                pendingOpen = false;
                pendingTicks = 0;
            }
            return;
        }

        pendingOpen = false;
        pendingTicks = 0;
        if (!isSupportedHandler(screen.getScreenHandler())) {
            return;
        }
        stashMatchingPlayerItems(screen);
        closeCurrentScreen(client);
    }

    private boolean isLookingAtSupportedBlock(MinecraftClient client) {
        HitResult hitResult = client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || client.world == null) {
            return false;
        }

        Block block = client.world.getBlockState(blockHitResult.getBlockPos()).getBlock();
        return block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof EnderChestBlock
                || block instanceof ShulkerBoxBlock;
    }

    private boolean isSupportedHandler(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler || handler instanceof ShulkerBoxScreenHandler;
    }

    private void stashMatchingPlayerItems(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }

        List<ItemStack> containerTemplates = snapshotContainerTemplates(handler);
        if (containerTemplates.isEmpty()) {
            return;
        }

        for (int playerSlotId : getPlayerStorageSlotIds(handler)) {
            Slot slot = handler.getSlot(playerSlotId);
            if (!isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.hasStack()
                    || !slot.canTakeItems(client.player)) {
                continue;
            }
            if (!matchesAnyTemplate(slot.getStack(), containerTemplates)) {
                continue;
            }

            client.interactionManager.clickSlot(
                    handler.syncId,
                    playerSlotId,
                    0,
                    SlotActionType.QUICK_MOVE,
                    client.player
            );
        }
    }

    private List<ItemStack> snapshotContainerTemplates(ScreenHandler handler) {
        List<ItemStack> templates = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot)
                    || isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.hasStack()) {
                continue;
            }
            // 锁住的容器格不参与“已有种类”判定，避免它间接决定回存结果。
            templates.add(slot.getStack().copyWithCount(1));
        }
        return templates;
    }

    private List<Integer> getPlayerStorageSlotIds(ScreenHandler handler) {
        List<Slot> playerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot)
                    || !isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            playerSlots.add(slot);
        }

        playerSlots.sort(Comparator
                .comparingInt((Slot slot) -> slot.getIndex() >= 9 ? 0 : 1)
                .thenComparingInt(Slot::getIndex)
                .thenComparingInt(slot -> slot.id));

        return playerSlots.stream()
                .map(slot -> slot.id)
                .toList();
    }

    private boolean matchesAnyTemplate(ItemStack stack, List<ItemStack> templates) {
        for (ItemStack template : templates) {
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlayerStorageSlot(Slot slot) {
        return slot.inventory instanceof net.minecraft.entity.player.PlayerInventory
                && slot.getIndex() >= 0
                && slot.getIndex() < 36;
    }

    private boolean isVisibleSlot(Slot slot) {
        return slot.isEnabled() && slot.x >= 0 && slot.y >= 0;
    }

    private void closeCurrentScreen(MinecraftClient client) {
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
    }
}
