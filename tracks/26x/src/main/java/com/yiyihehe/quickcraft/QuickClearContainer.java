package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

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
    private static ClientLevel pendingLevel;
    private static BlockPos pendingPos;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isQuickClearContainerEnabled()) {
            clearPending();
        }

        handleUseAttempt(client);
        processPendingOpen(client);
    }

    private void handleUseAttempt(Minecraft client) {
        if (!QuickCraftConfigs.isQuickClearContainerEnabled() || client.player == null || client.level == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.keyUse);
        if (useDown
                && !lastUseDown
                && currentScreen(client) == null
                && !QuickMaterialCollector.shouldHandleCurrentTarget(client)
                && !QuickLitematicaContainerAutofill.shouldHandleCurrentTarget(client)
                && client.hitResult instanceof BlockHitResult blockHitResult
                && isLookingAtSupportedContainer(client)) {
            pendingOpen = true;
            pendingTicks = 0;
            pendingLevel = client.level;
            pendingPos = blockHitResult.getBlockPos().immutable();
        }

        lastUseDown = useDown;
    }

    private void processPendingOpen(Minecraft client) {
        if (!pendingOpen) {
            return;
        }

        pendingTicks++;
        if (!pendingTargetStillValid(client)) {
            clearPending();
            return;
        }
        if (!(currentScreen(client) instanceof AbstractContainerScreen<?> screen)) {
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

    private boolean pendingTargetStillValid(Minecraft client) {
        if (client.level == null || pendingLevel != client.level || pendingPos == null) {
            return false;
        }
        return client.level.getBlockEntity(pendingPos) != null
                && !client.level.getBlockState(pendingPos).isAir();
    }

    private boolean isConfirmedTargetScreen(Minecraft client, AbstractContainerScreen<?> screen) {
        if (client.player == null || client.level == null || pendingPos == null) {
            return false;
        }
        // 客户端菜单由 MenuType 工厂新建独立 SimpleContainer，不与世界方块实体共享对象；
        // 这里只能按“开屏源自对 pendingPos 的实际交互”和“菜单容量与目标容器一致”做关联。
        if (!(client.hitResult instanceof BlockHitResult hit)
                || !hit.getBlockPos().equals(pendingPos)) {
            return false;
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (handler == client.player.inventoryMenu) {
            return false;
        }
        int targetSlotCount = targetContainerSlotCount(client);
        return targetSlotCount >= 0 && targetSlotCount == countContainerSlots(handler);
    }

    private int targetContainerSlotCount(Minecraft client) {
        var state = client.level.getBlockState(pendingPos);
        BlockEntity blockEntity = client.level.getBlockEntity(pendingPos);
        if (blockEntity instanceof Container container) {
            if (state.getBlock() instanceof ChestBlock
                    && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                // 双箱菜单两半合计 54 格；客户端半侧方块实体自身仍是 27 格。
                return container.getContainerSize() * 2;
            }
            return container.getContainerSize();
        }
        return state.getBlock() instanceof EnderChestBlock ? ENDER_CHEST_SLOT_COUNT : -1;
    }

    private int countContainerSlots(AbstractContainerMenu handler) {
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
        pendingLevel = null;
        pendingPos = null;
    }

    private static Screen currentScreen(Minecraft client) {
        return QuickClientScreenAccess.currentScreen(client);
    }

    private boolean isLookingAtSupportedContainer(Minecraft client) {
        HitResult hitResult = client.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || client.level == null) {
            return false;
        }

        BlockEntity blockEntity = client.level.getBlockEntity(blockHitResult.getBlockPos());
        if (blockEntity instanceof Container) {
            return true;
        }

        return client.level.getBlockState(blockHitResult.getBlockPos()).getBlock() instanceof EnderChestBlock;
    }

    void clearContainer(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (!handler.getCarried().isEmpty()) {
            return;
        }

        for (int slotId : getContainerSlotIds(handler)) {
            Slot slot = handler.getSlot(slotId);
            if (!isVisibleSlot(slot)
                    || isPlayerStorageSlot(slot)
                    || QuickContainerLock.isLockedSlot(handler, slot)
                    || !slot.hasItem()
                    || !slot.mayPickup(client.player)) {
                continue;
            }

            client.gameMode.handleContainerInput(
                    handler.containerId,
                    slotId,
                    1,
                    ContainerInput.THROW,
                    client.player
            );
        }
    }

    private List<Integer> getContainerSlotIds(AbstractContainerMenu handler) {
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
                .thenComparingInt(slot -> slot.index));

        return containerSlots.stream()
                .map(slot -> slot.index)
                .toList();
    }

    private boolean isPlayerStorageSlot(Slot slot) {
        // 玩家菜单里的装备、副手和合成槽也不能被当作目标容器槽。
        return slot.container instanceof Inventory;
    }

    private boolean isVisibleSlot(Slot slot) {
        return slot.isActive() && slot.x >= 0 && slot.y >= 0;
    }

    private void closeCurrentScreen(Minecraft client) {
        if (client.player != null) {
            client.player.closeContainer();
        }
    }
}
