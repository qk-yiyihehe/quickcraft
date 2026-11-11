package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一键清空容器：
 * - 通过工具页里的单项开关切换启用状态
 * - 对着容器右键后，把容器里能拿走的物品尽可能全部整组丢到地上
 * - 完成后自动关闭界面
 */
public final class QuickClearContainer implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;
    private static final int ENDER_CHEST_SLOT_COUNT = 27;

    private static boolean lastUseDown;
    private static boolean pendingOpen;
    private static int pendingTicks;
    private static ClientWorld pendingWorld;
    private static BlockPos pendingPos;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isQuickClearContainerEnabled()) {
            clearPending();
        }

        handleUseAttempt(client);
        processPendingOpen(client);
    }

    private void handleUseAttempt(MinecraftClient client) {
        if (!QuickCraftConfigs.isQuickClearContainerEnabled() || client.player == null || client.world == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.useKey);
        if (useDown
                && !lastUseDown
                && client.currentScreen == null
                && !QuickMaterialCollector.shouldHandleCurrentTarget(client)
                && !QuickLitematicaContainerAutofill.shouldHandleCurrentTarget(client)
                && client.crosshairTarget instanceof BlockHitResult blockHitResult
                && isLookingAtSupportedContainer(client)) {
            pendingOpen = true;
            pendingTicks = 0;
            pendingWorld = client.world;
            pendingPos = blockHitResult.getBlockPos().toImmutable();
        }

        lastUseDown = useDown;
    }

    private void processPendingOpen(MinecraftClient client) {
        if (!pendingOpen) {
            return;
        }

        pendingTicks++;
        if (!pendingTargetStillValid(client)) {
            clearPending();
            return;
        }
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            if (pendingTicks > OPEN_TIMEOUT_TICKS) {
                clearPending();
            }
            return;
        }

        if (!isConfirmedTargetScreen(client, screen)) {
            clearPending();
            return;
        }

        clearPending();
        clearContainer(screen);
        closeCurrentScreen(client);
    }

    private boolean pendingTargetStillValid(MinecraftClient client) {
        if (client.world == null || pendingWorld != client.world || pendingPos == null) {
            return false;
        }
        return client.world.getBlockEntity(pendingPos) != null
                && !client.world.getBlockState(pendingPos).isAir();
    }

    private boolean isConfirmedTargetScreen(MinecraftClient client, HandledScreen<?> screen) {
        if (client.player == null || client.world == null || pendingPos == null) {
            return false;
        }
        // 客户端菜单由 ScreenHandlerType 工厂新建独立 SimpleInventory，不与世界方块实体共享对象；
        // 这里只能按“开屏源自对 pendingPos 的实际交互”和“菜单容量与目标容器一致”做关联。
        if (!(client.crosshairTarget instanceof BlockHitResult hit)
                || !hit.getBlockPos().equals(pendingPos)) {
            return false;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (handler == client.player.playerScreenHandler) {
            return false;
        }
        int targetSlotCount = targetContainerSlotCount(client);
        return targetSlotCount >= 0 && targetSlotCount == countContainerSlots(handler);
    }

    private int targetContainerSlotCount(MinecraftClient client) {
        var state = client.world.getBlockState(pendingPos);
        BlockEntity blockEntity = client.world.getBlockEntity(pendingPos);
        if (blockEntity instanceof Inventory inventory) {
            if (state.getBlock() instanceof ChestBlock
                    && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                // 双箱菜单两半合计 54 格；客户端半侧方块实体自身仍是 27 格。
                return inventory.size() * 2;
            }
            return inventory.size();
        }
        return state.getBlock() instanceof EnderChestBlock ? ENDER_CHEST_SLOT_COUNT : -1;
    }

    private int countContainerSlots(ScreenHandler handler) {
        int count = 0;
        for (Slot slot : handler.slots) {
            if (slot.x >= 0 && slot.y >= 0 && !isPlayerStorageSlot(slot)) {
                count++;
            }
        }
        return count;
    }

    private void clearPending() {
        pendingOpen = false;
        pendingTicks = 0;
        pendingWorld = null;
        pendingPos = null;
    }

    private boolean isLookingAtSupportedContainer(MinecraftClient client) {
        HitResult hitResult = client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || client.world == null) {
            return false;
        }

        BlockEntity blockEntity = client.world.getBlockEntity(blockHitResult.getBlockPos());
        if (blockEntity instanceof Inventory) {
            return true;
        }

        return client.world.getBlockState(blockHitResult.getBlockPos()).getBlock() instanceof EnderChestBlock;
    }

    private void clearContainer(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }

        for (int slotId : getContainerSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!isVisibleSlot(slot)
                    || isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.hasStack()
                    || !slot.canTakeItems(client.player)) {
                continue;
            }

            client.interactionManager.clickSlot(
                    handler.syncId,
                    slotId,
                    1,
                    SlotActionType.THROW,
                    client.player
            );
        }
    }

    private List<Integer> getContainerSlotIds(ScreenHandler handler) {
        List<Slot> containerSlots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isVisibleSlot(slot)
                    || isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            containerSlots.add(slot);
        }

        containerSlots.sort(Comparator
                .comparingInt((Slot slot) -> slot.y)
                .thenComparingInt(slot -> slot.x)
                .thenComparingInt(slot -> slot.id));

        return containerSlots.stream()
                .map(slot -> slot.id)
                .toList();
    }

    private boolean isPlayerStorageSlot(Slot slot) {
        // 玩家菜单里的装备、副手和合成槽也不能被当作目标容器槽。
        return slot.inventory instanceof PlayerInventory;
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
