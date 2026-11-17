package com.yiyihehe.quickcraft;

import static com.yiyihehe.quickcraft.QuickContainerCopySources.*;

import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

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

    public static void stashInventory(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(screen instanceof InventoryScreen) || client.player == null || client.interactionManager == null
                || client.currentScreen != screen || screen.getScreenHandler() != client.player.currentScreenHandler
                || !isAvailable() || !screen.getScreenHandler().getCursorStack().isEmpty()
                || QuickStashShulkerTransfer.isRunning() || QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()
                || QuickContainerCopy.shouldHideBackgroundHandledScreen()
                || QuickMaterialCollector.shouldHideBackgroundHandledScreen()) {
            return;
        }
        QuickContainerLock.bindCurrentScreen(screen);
        if (QuickContainerLock.handleLockedAutomationAttempt(
                client, screen, Text.translatable("quickcraft.button.quick_stash"))) {
            return;
        }
        ScreenHandler handler = screen.getScreenHandler();
        List<Integer> slots = getPlayerStorageSlotIds(handler);
        for (int sourceId : slots) {
            Slot source = handler.getSlot(sourceId);
            if (!source.hasStack() || isShulkerBox(source.getStack()) || !source.canTakeItems(client.player)) {
                continue;
            }
            if (!packMatching(handler, sourceId, slots, client)) {
                if (!handler.getCursorStack().isEmpty()) {
                    client.player.sendMessage(Text.translatable("quickcraft.message.quick_stash.cursor_blocked"), true);
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
            if (ItemStack.areItemsAndComponentsEqual(stored, template)) {
                return true;
            }
        }
        return false;
    }

    static int matchingPartialCapacity(ItemStack shulker, ItemStack template) {
        int capacity = 0;
        for (ItemStack stored : getStoredStacksBySlot(shulker)) {
            if (ItemStack.areItemsAndComponentsEqual(stored, template)) {
                capacity += Math.max(0, stored.getMaxCount() - stored.getCount());
            }
        }
        return capacity;
    }

    static boolean canReceive(ScreenHandler handler, int slotId, ItemStack template, MinecraftClient client) {
        Slot slot = handler.getSlot(slotId);
        return isVisibleSlot(slot) && !QuickContainerLock.isLockedSlot(handler, slot)
                && slot.getStack().getCount() == 1 && slot.canTakeItems(client.player)
                && slot.canInsert(slot.getStack()) && contains(slot.getStack(), template)
                && getShulkerCapacityFor(slot.getStack(), template) > 0;
    }

    static boolean packMatching(ScreenHandler handler, int sourceId,
                                List<Integer> destinationIds, MinecraftClient client) {
        Slot source = handler.getSlot(sourceId);
        if (!handler.getCursorStack().isEmpty() || QuickContainerLock.isLockedSlot(handler, source)
                || !source.canTakeItems(client.player) || isShulkerBox(source.getStack())) {
            return false;
        }
        ItemStack template = source.getStack().copyWithCount(1);
        List<Integer> targets = new ArrayList<>(destinationIds.stream()
                .filter(id -> id != sourceId && canReceive(handler, id, template, client)).toList());
        // 先选可补同类半组的盒子；同级保持传入槽位顺序，避免两个匹配盒之间反复跳转。
        targets.sort(Comparator.comparing(id -> matchingPartialCapacity(handler.getSlot(id).getStack(), template) == 0));
        for (int targetId : targets) {
            if (!source.hasStack()) {
                break;
            }
            if (!canReceive(handler, targetId, template, client)) {
                continue;
            }
            Slot target = handler.getSlot(targetId);
            int before = source.getStack().getCount();
            click(client, handler, sourceId, 0);
            if (handler.getCursorStack().getCount() != before
                    || !ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), template)) {
                return false;
            }
            click(client, handler, targetId, 1);
            if (isShulkerBox(handler.getCursorStack()) && !isShulkerBox(target.getStack())) {
                // 快盒未启用装盒时，原版右键会交换物品，先撤销交换再归还来源。
                click(client, handler, targetId, 0);
            }
            ItemStack remainder = handler.getCursorStack();
            if (!remainder.isEmpty() && !ItemStack.areItemsAndComponentsEqual(remainder, template)) {
                return false;
            }
            int remaining = remainder.getCount();
            if (!remainder.isEmpty()) {
                click(client, handler, sourceId, 0);
            }
            if (!handler.getCursorStack().isEmpty() || remaining >= before) {
                return false;
            }
        }
        return true;
    }

    private static void click(MinecraftClient client, ScreenHandler handler, int slotId, int button) {
        client.interactionManager.clickSlot(handler.syncId, slotId, button, SlotActionType.PICKUP, client.player);
    }
}
