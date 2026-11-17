package com.yiyihehe.quickcraft;

import static com.yiyihehe.quickcraft.QuickContainerCopySources.*;

import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 背包收纳和容器回存共用的快盒装入规则；不打开盒子，也不改变盒子所在槽位。 */
public final class QuickShulkerStash {
    private QuickShulkerStash() {
    }

    public static boolean isAvailable() {
        return QuickCraftConfigs.ModSupport.ENABLE_QUICK_SHULKER.getBooleanValue()
                && QuickShulkerConnection.canBundle();
    }

    public static void stashInventory(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();
        if (!(screen instanceof InventoryScreen) || client.player == null || client.gameMode == null
                || QuickClientScreenAccess.currentScreen(client) != screen || screen.getMenu() != client.player.containerMenu
                || !isAvailable() || !screen.getMenu().getCarried().isEmpty()
                || QuickStashShulkerTransfer.isRunning() || QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()
                || QuickContainerCopy.shouldHideBackgroundHandledScreen()
                || QuickMaterialCollector.shouldHideBackgroundHandledScreen()) {
            return;
        }
        QuickContainerLock.bindCurrentScreen(screen);
        if (QuickContainerLock.handleLockedAutomationAttempt(
                client, screen, Component.translatable("quickcraft.button.quick_stash"))) {
            return;
        }
        AbstractContainerMenu handler = screen.getMenu();
        List<Integer> slots = getPlayerStorageSlotIds(handler);
        for (int sourceId : slots) {
            Slot source = handler.getSlot(sourceId);
            if (!source.hasItem() || isShulkerBox(source.getItem()) || !source.mayPickup(client.player)) {
                continue;
            }
            if (!packMatching(handler, sourceId, slots, client)) {
                if (!handler.getCarried().isEmpty()) {
                    client.player.sendOverlayMessage(Component.translatable("quickcraft.message.quick_stash.cursor_blocked"));
                }
                return;
            }
        }
    }

    static boolean contains(ItemStack shulker, ItemStack template) {
        if (!isShulkerBox(shulker) || template.isEmpty()) {
            return false;
        }
        for (ItemStack stored : getStoredStacksBySlot(shulker)) {
            if (ItemStack.isSameItemSameComponents(stored, template)) {
                return true;
            }
        }
        return false;
    }

    static int matchingPartialCapacity(ItemStack shulker, ItemStack template) {
        int capacity = 0;
        for (ItemStack stored : getStoredStacksBySlot(shulker)) {
            if (ItemStack.isSameItemSameComponents(stored, template)) {
                capacity += Math.max(0, stored.getMaxStackSize() - stored.getCount());
            }
        }
        return capacity;
    }

    static boolean canReceive(AbstractContainerMenu handler, int slotId, ItemStack template, Minecraft client) {
        Slot slot = handler.getSlot(slotId);
        return isVisibleSlot(slot) && !QuickContainerLock.isLockedSlot(handler, slot)
                && slot.getItem().getCount() == 1 && slot.mayPickup(client.player)
                && slot.mayPlace(slot.getItem()) && contains(slot.getItem(), template)
                && getShulkerCapacityFor(slot.getItem(), template) > 0;
    }

    static boolean packMatching(AbstractContainerMenu handler, int sourceId,
                                List<Integer> destinationIds, Minecraft client) {
        Slot source = handler.getSlot(sourceId);
        if (!handler.getCarried().isEmpty() || QuickContainerLock.isLockedSlot(handler, source)
                || !source.mayPickup(client.player) || isShulkerBox(source.getItem())) {
            return false;
        }
        ItemStack template = source.getItem().copyWithCount(1);
        List<Integer> targets = new ArrayList<>(destinationIds.stream()
                .filter(id -> id != sourceId && canReceive(handler, id, template, client)).toList());
        // 先选可补同类半组的盒子；同级保持传入槽位顺序，避免两个匹配盒之间反复跳转。
        targets.sort(Comparator.comparing(id -> matchingPartialCapacity(handler.getSlot(id).getItem(), template) == 0));
        for (int targetId : targets) {
            if (!source.hasItem()) {
                break;
            }
            if (!canReceive(handler, targetId, template, client)) {
                continue;
            }
            Slot target = handler.getSlot(targetId);
            int before = source.getItem().getCount();
            click(client, handler, sourceId, 0);
            if (handler.getCarried().getCount() != before
                    || !ItemStack.isSameItemSameComponents(handler.getCarried(), template)) {
                return false;
            }
            click(client, handler, targetId, 1);
            if (isShulkerBox(handler.getCarried()) && !isShulkerBox(target.getItem())) {
                // 快盒未启用装盒时，原版右键会交换物品，先撤销交换再归还来源。
                click(client, handler, targetId, 0);
            }
            ItemStack remainder = handler.getCarried();
            if (!remainder.isEmpty() && !ItemStack.isSameItemSameComponents(remainder, template)) {
                return false;
            }
            int remaining = remainder.getCount();
            if (!remainder.isEmpty()) {
                click(client, handler, sourceId, 0);
            }
            if (!handler.getCarried().isEmpty() || remaining >= before) {
                return false;
            }
        }
        return true;
    }

    private static void click(Minecraft client, AbstractContainerMenu handler, int slotId, int button) {
        client.gameMode.handleContainerInput(handler.containerId, slotId, button, ContainerInput.PICKUP, client.player);
    }
}
