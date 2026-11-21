package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fills the clicked container with the most abundant transferable item in the player inventory. */
public final class QuickFillContainer implements ClientModInitializer {

    private static boolean lastUseDown;
    private static QuickContainerToolSync.PendingOpen pendingOpen;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft client) {
        if (!QuickCraftConfigs.isQuickFillContainerEnabled()) {
            pendingOpen = null;
        }

        handleUseAttempt(client);
        processPendingOpen(client);
    }

    private void handleUseAttempt(Minecraft client) {
        if (!QuickCraftConfigs.isQuickFillContainerEnabled() || client.player == null || client.level == null) {
            lastUseDown = false;
            return;
        }

        boolean useDown = QuickCraftKeyBindings.isVanillaKeyDown(client, client.options.keyUse);
        if (useDown
                && !lastUseDown
                && pendingOpen == null
                && QuickClientScreenAccess.currentScreen(client) == null
                && !QuickMaterialCollector.shouldHandleCurrentTarget(client)
                && !QuickLitematicaContainerAutofill.shouldHandleCurrentTarget(client)
                && isLookingAtSupportedContainer(client)) {
            pendingOpen = new QuickContainerToolSync.PendingOpen(client);
        }

        lastUseDown = useDown;
    }

    private void processPendingOpen(Minecraft client) {
        if (pendingOpen == null) {
            return;
        }
        if (!pendingOpen.tick(client)) {
            pendingOpen = null;
            return;
        }
        if (!(QuickClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!hasVisibleContainerSlots(screen.getMenu())) {
            pendingOpen = null;
            return;
        }
        if (!pendingOpen.inventoryReady(screen)) {
            return;
        }

        pendingOpen = null;
        fillContainer(screen);
        closeCurrentScreen(client);
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

    void fillContainer(AbstractContainerScreen<?> screen) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        AbstractContainerMenu handler = screen.getMenu();
        if (!handler.getCarried().isEmpty()) {
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
            if (!slot.hasItem()
                    || !slot.mayPickup(client.player)
                    || !ItemStack.isSameItemSameComponents(slot.getItem(), mostAbundant)) {
                continue;
            }

            int sourceCount = slot.getItem().getCount();
            int beforeContainer = countMatchingInContainer(handler, containerSlotIds, mostAbundant);
            QuickSlotTransfer.quickMove(client, handler, playerSlotId,
                    target -> !isPlayerStorageSlot(target) && isVisibleSlot(target), false);
            int afterContainer = countMatchingInContainer(handler, containerSlotIds, mostAbundant);
            int afterSource = handler.getSlot(playerSlotId).getItem().getCount();
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
        return containerSlots.stream()
                .map(slot -> handler.slots.indexOf(slot))
                .toList();
    }

    private int getContainerCapacity(Minecraft client, AbstractContainerMenu handler,
                                     List<Integer> containerSlotIds, ItemStack stack) {
        int capacity = 0;
        for (int slotId : containerSlotIds) {
            Slot slot = handler.getSlot(slotId);
            if (handler instanceof AbstractFurnaceMenu
                    && slot == handler.slots.get(0)
                    && !isFurnaceInputAccepted(client, handler, stack)) {
                continue;
            }
            capacity += getSlotCapacity(slot, stack);
        }
        return capacity;
    }

    private int getSlotCapacity(Slot slot, ItemStack stack) {
        if (!slot.isActive() || !slot.mayPlace(stack)) {
            return 0;
        }
        int maxCount = Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack));
        if (!slot.hasItem()) {
            return Math.max(0, maxCount);
        }
        if (!ItemStack.isSameItemSameComponents(slot.getItem(), stack)) {
            return 0;
        }
        return Math.max(0, maxCount - slot.getItem().getCount());
    }

    private boolean isFurnaceInputAccepted(Minecraft client, AbstractContainerMenu handler, ItemStack stack) {
        if (client.level == null) {
            return false;
        }
        if (handler instanceof BlastFurnaceMenu) {
            return client.level.recipeAccess().propertySet(RecipePropertySet.BLAST_FURNACE_INPUT).test(stack);
        }
        if (handler instanceof SmokerMenu) {
            return client.level.recipeAccess().propertySet(RecipePropertySet.SMOKER_INPUT).test(stack);
        }
        return client.level.recipeAccess().propertySet(RecipePropertySet.FURNACE_INPUT).test(stack);
    }

    private int countMatchingInContainer(AbstractContainerMenu handler, List<Integer> containerSlotIds, ItemStack expected) {
        int count = 0;
        for (int slotId : containerSlotIds) {
            ItemStack stack = handler.getSlot(slotId).getItem();
            if (ItemStack.isSameItemSameComponents(stack, expected)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void notifyFillSkipped(Minecraft client) {
        if (client.player != null) {
            client.player.sendOverlayMessage(Component.translatable("quickcraft.message.fill_container.not_insertable"));
        }
    }

    private ItemStack findMostAbundantStack(AbstractContainerMenu handler, List<Integer> playerSlotIds) {
        ItemStack mostAbundant = ItemStack.EMPTY;
        int largestTotal = 0;

        for (int candidateSlotId : playerSlotIds) {
            ItemStack candidate = handler.getSlot(candidateSlotId).getItem();
            if (candidate.isEmpty()) {
                continue;
            }

            int total = 0;
            for (int playerSlotId : playerSlotIds) {
                ItemStack stack = handler.getSlot(playerSlotId).getItem();
                if (ItemStack.isSameItemSameComponents(stack, candidate)) {
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

    private boolean hasVisibleContainerSlots(AbstractContainerMenu handler) {
        for (Slot slot : handler.slots) {
            if (isVisibleSlot(slot) && !isPlayerStorageSlot(slot)) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> getPlayerStorageSlotIds(AbstractContainerMenu handler) {
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
                .comparingInt((Slot slot) -> slot.getContainerSlot() >= 9 ? 0 : 1)
                .thenComparingInt(Slot::getContainerSlot)
                .thenComparingInt(slot -> slot.index));

        return playerSlots.stream()
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
