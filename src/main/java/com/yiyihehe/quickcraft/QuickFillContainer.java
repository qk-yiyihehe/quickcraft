package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fills the clicked container with the most abundant transferable item in the player inventory. */
public final class QuickFillContainer implements ClientModInitializer {
    private static final int OPEN_TIMEOUT_TICKS = 20;

    private static boolean lastUseDown;
    private static boolean pendingOpen;
    private static int pendingTicks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isQuickFillContainerEnabled()) {
            pendingOpen = false;
            pendingTicks = 0;
        }

        handleUseAttempt(client);
        processPendingOpen(client);
    }

    private void handleUseAttempt(MinecraftClient client) {
        if (!QuickCraftConfigs.isQuickFillContainerEnabled() || client.player == null || client.world == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.useKey);
        if (useDown
                && !lastUseDown
                && client.currentScreen == null
                && !QuickMaterialCollector.shouldHandleCurrentTarget(client)
                && !QuickLitematicaContainerAutofill.shouldHandleCurrentTarget(client)
                && isLookingAtSupportedContainer(client)) {
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
        if (!hasVisibleContainerSlots(screen.getScreenHandler())) {
            return;
        }
        fillContainer(screen);
        closeCurrentScreen(client);
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

    private void fillContainer(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }

        List<Integer> playerSlotIds = getPlayerStorageSlotIds(handler);
        ItemStack mostAbundant = findMostAbundantStack(handler, playerSlotIds);
        if (mostAbundant.isEmpty()) {
            return;
        }

        List<Integer> containerSlotIds = getContainerSlotIds(handler);
        int remainingCapacity = getContainerCapacity(client, handler, containerSlotIds, mostAbundant);
        if (remainingCapacity <= 0) {
            notifyFillSkipped(client);
            return;
        }

        boolean movedAny = false;
        for (int playerSlotId : playerSlotIds) {
            Slot slot = handler.getSlot(playerSlotId);
            if (!slot.hasStack()
                    || !slot.canTakeItems(client.player)
                    || !ItemStack.areItemsAndComponentsEqual(slot.getStack(), mostAbundant)) {
                continue;
            }

            int sourceCount = slot.getStack().getCount();
            int beforeContainer = countMatchingInContainer(handler, containerSlotIds, mostAbundant);
            client.interactionManager.clickSlot(
                    handler.syncId,
                    playerSlotId,
                    0,
                    SlotActionType.QUICK_MOVE,
                    client.player
            );
            int afterContainer = countMatchingInContainer(handler, containerSlotIds, mostAbundant);
            int afterSource = handler.getSlot(playerSlotId).getStack().getCount();
            int movedToContainer = afterContainer - beforeContainer;
            if (movedToContainer <= 0
                    || movedToContainer > remainingCapacity
                    || afterSource != sourceCount - movedToContainer) {
                notifyFillSkipped(client);
                return;
            }
            movedAny = true;
            remainingCapacity -= movedToContainer;
            if (remainingCapacity <= 0) {
                break;
            }
        }
        if (!movedAny) {
            notifyFillSkipped(client);
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
        return containerSlots.stream()
                .map(slot -> handler.slots.indexOf(slot))
                .toList();
    }

    private int getContainerCapacity(MinecraftClient client, ScreenHandler handler,
                                     List<Integer> containerSlotIds, ItemStack stack) {
        int capacity = 0;
        for (int slotId : containerSlotIds) {
            Slot slot = handler.getSlot(slotId);
            if (handler instanceof AbstractFurnaceScreenHandler
                    && slot == handler.slots.get(0)
                    && !isFurnaceInputAccepted(client, handler, stack)) {
                continue;
            }
            capacity += getSlotCapacity(slot, stack);
        }
        return capacity;
    }

    private int getSlotCapacity(Slot slot, ItemStack stack) {
        if (!slot.isEnabled() || !slot.canInsert(stack)) {
            return 0;
        }
        int maxCount = Math.min(stack.getMaxCount(), slot.getMaxItemCount(stack));
        if (!slot.hasStack()) {
            return Math.max(0, maxCount);
        }
        if (!ItemStack.areItemsAndComponentsEqual(slot.getStack(), stack)) {
            return 0;
        }
        return Math.max(0, maxCount - slot.getStack().getCount());
    }

    private boolean isFurnaceInputAccepted(MinecraftClient client, ScreenHandler handler, ItemStack stack) {
        return QuickFillContainerRecipeAccess.isAccepted(client, handler, stack);
    }

    private int countMatchingInContainer(ScreenHandler handler, List<Integer> containerSlotIds, ItemStack expected) {
        int count = 0;
        for (int slotId : containerSlotIds) {
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (ItemStack.areItemsAndComponentsEqual(stack, expected)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void notifyFillSkipped(MinecraftClient client) {
        if (client.player != null) {
            client.player.sendMessage(Text.translatable("quickcraft.message.fill_container.not_insertable"), true);
        }
    }

    private ItemStack findMostAbundantStack(ScreenHandler handler, List<Integer> playerSlotIds) {
        ItemStack mostAbundant = ItemStack.EMPTY;
        int largestTotal = 0;

        for (int candidateSlotId : playerSlotIds) {
            ItemStack candidate = handler.getSlot(candidateSlotId).getStack();
            if (candidate.isEmpty()) {
                continue;
            }

            int total = 0;
            for (int playerSlotId : playerSlotIds) {
                ItemStack stack = handler.getSlot(playerSlotId).getStack();
                if (ItemStack.areItemsAndComponentsEqual(stack, candidate)) {
                    total += stack.getCount();
                }
            }

            if (total > largestTotal) {
                largestTotal = total;
                mostAbundant = candidate.copyWithCount(1);
            }
        }

        return mostAbundant;
    }

    private boolean hasVisibleContainerSlots(ScreenHandler handler) {
        for (Slot slot : handler.slots) {
            if (isVisibleSlot(slot) && !isPlayerStorageSlot(slot)) {
                return true;
            }
        }
        return false;
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
