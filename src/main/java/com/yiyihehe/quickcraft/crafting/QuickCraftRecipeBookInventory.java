package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 两种原版合成界面共用的玩家库存输出操作。
 */
final class QuickCraftRecipeBookInventory {
    private static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft/RecipeBookCraft");

    private QuickCraftRecipeBookInventory() {
    }

    static boolean isPatternComplete(AbstractContainerMenu handler,
                                     QuickCraftRecipeBookLayout.Layout layout,
                                     List<ItemStack> pattern) {
        if (handler == null || layout == null || pattern == null
                || pattern.size() != layout.gridSize()) {
            return false;
        }
        for (int i = 0; i < layout.gridSize(); i++) {
            ItemStack template = pattern.get(i);
            ItemStack existing = handler.getSlot(layout.gridSlotId(i)).getItem();
            if (template == null || template.isEmpty()) {
                if (!existing.isEmpty()) {
                    return false;
                }
                continue;
            }
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, template)) {
                return false;
            }
        }
        return true;
    }

    static boolean canAcceptUnlocked(AbstractContainerMenu handler,
                                     QuickCraftRecipeBookLayout.Layout layout,
                                     ItemStack stack) {
        if (handler == null || layout == null || stack == null || stack.isEmpty()) {
            return false;
        }
        if (hasLockedPlayerSlot(handler, layout)) {
            return findAcceptingUnlockedSlot(handler, layout, stack) >= 0;
        }
        return unlockedCapacity(handler, layout, stack) >= stack.getCount();
    }

    static int unlockedEmptySlots(AbstractContainerMenu handler,
                                  QuickCraftRecipeBookLayout.Layout layout) {
        if (handler == null || layout == null) {
            return 0;
        }
        int empty = 0;
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            ItemStack existing = handler.getSlot(handlerSlot).getItem();
            if (existing == null || existing.isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    static int unlockedCapacity(AbstractContainerMenu handler,
                                QuickCraftRecipeBookLayout.Layout layout,
                                ItemStack stack) {
        if (handler == null || layout == null || stack == null || stack.isEmpty()) {
            return 0;
        }

        long capacity = 0L;
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            Slot slot = handler.getSlot(handlerSlot);
            if (!slot.mayPlace(stack)) {
                continue;
            }
            ItemStack existing = slot.getItem();
            if (existing.isEmpty()) {
                capacity += slot.getMaxStackSize(stack);
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                capacity += Math.max(0, slot.getMaxStackSize(stack) - existing.getCount());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, capacity);
    }

    static int findAcceptingUnlockedSlot(AbstractContainerMenu handler,
                                         QuickCraftRecipeBookLayout.Layout layout,
                                         ItemStack stack) {
        if (handler == null || stack == null || stack.isEmpty()) {
            return -1;
        }
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            Slot slot = handler.getSlot(handlerSlot);
            if (slot.mayPlace(stack) && canAccept(slot, stack)) {
                return handlerSlot;
            }
        }
        return -1;
    }

    static boolean hasUnevenMatchingGridStacks(AbstractContainerMenu handler,
                                               QuickCraftRecipeBookLayout.Layout layout) {
        if (handler == null || layout == null) {
            return false;
        }
        for (int i = 0; i < layout.gridSize(); i++) {
            ItemStack left = handler.getSlot(layout.gridSlotId(i)).getItem();
            if (left.isEmpty()) {
                continue;
            }
            for (int j = i + 1; j < layout.gridSize(); j++) {
                ItemStack right = handler.getSlot(layout.gridSlotId(j)).getItem();
                if (right.isEmpty() || !ItemStack.isSameItemSameComponents(left, right)) {
                    continue;
                }
                if (left.getCount() != right.getCount()) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean takeOneOutputToUnlockedInventory(Minecraft client,
                                                    AbstractContainerMenu handler,
                                                    QuickCraftRecipeBookLayout.Layout layout) {
        if (client == null || client.player == null || client.gameMode == null
                || handler == null || !handler.getCarried().isEmpty()
                || !handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).hasItem()) {
            return false;
        }

        ItemStack output = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getItem().copy();
        int targetSlot = findAcceptingUnlockedSlot(handler, layout, output);
        if (targetSlot < 0) {
            return false;
        }
        int countBefore = countMatching(handler, layout, output);
        client.gameMode.handleContainerInput(
                handler.containerId,
                QuickCraftRecipeBookLayout.OUTPUT_SLOT,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        ItemStack cursor = handler.getCarried();
        if (cursor.isEmpty() || !ItemStack.isSameItemSameComponents(cursor, output)) {
            return false;
        }
        client.gameMode.handleContainerInput(
                handler.containerId,
                targetSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        ItemStack after = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getItem();
        boolean outputChanged = after.isEmpty()
                || !ItemStack.isSameItemSameComponents(output, after)
                || output.getCount() != after.getCount();
        return handler.getCarried().isEmpty()
                && didOutputMove(outputChanged, countMatching(handler, layout, output) > countBefore);
    }

    static boolean moveOutputToUnlockedInventory(Minecraft client,
                                                 AbstractContainerMenu handler,
                                                 QuickCraftRecipeBookLayout.Layout layout) {
        return moveOutputToUnlockedInventory(client, handler, layout, false);
    }

    private static boolean moveOutputToUnlockedInventory(Minecraft client,
                                                         AbstractContainerMenu handler,
                                                         QuickCraftRecipeBookLayout.Layout layout,
                                                         boolean preferQuickMove) {
        if (client == null || client.player == null || client.gameMode == null
                || handler == null || !handler.getCarried().isEmpty()
                || !handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).hasItem()) {
            return false;
        }

        ItemStack output = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getItem().copy();
        // The ACK path follows ItemScroller's shift-click fast path. The legacy
        // path retains its direct pickup behavior when locked slots are present.
        if (preferQuickMove && moveOutputWithQuickMove(client, handler, layout)) {
            return true;
        }

        if (!preferQuickMove && !hasLockedPlayerSlot(handler, layout)) {
            return moveOutputWithQuickMove(client, handler, layout);
        }

        return moveOutputWithPickup(client, handler, layout, output);
    }

    private static boolean moveOutputWithPickup(Minecraft client,
                                                AbstractContainerMenu handler,
                                                QuickCraftRecipeBookLayout.Layout layout) {
        if (client == null || client.player == null || client.gameMode == null
                || handler == null || !handler.getCarried().isEmpty()
                || !handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).hasItem()) {
            return false;
        }

        ItemStack output = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getItem().copy();
        return moveOutputWithPickup(client, handler, layout, output);
    }

    private static boolean moveOutputWithPickup(Minecraft client,
                                                AbstractContainerMenu handler,
                                                QuickCraftRecipeBookLayout.Layout layout,
                                                ItemStack output) {
        if (findAcceptingUnlockedSlot(handler, layout, output) < 0) {
            return false;
        }
        int countBefore = countMatching(handler, layout, output);
        client.gameMode.handleContainerInput(
                handler.containerId,
                QuickCraftRecipeBookLayout.OUTPUT_SLOT,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        ItemStack cursor = handler.getCarried();
        if (cursor.isEmpty() || !ItemStack.isSameItemSameComponents(cursor, output)) {
            return false;
        }
        int targetSlot = findAcceptingUnlockedSlot(handler, layout, cursor);
        if (targetSlot < 0) {
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    -999,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
            return handler.getCarried().isEmpty();
        }
        client.gameMode.handleContainerInput(
                handler.containerId,
                targetSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );

        int countAfter = countMatching(handler, layout, output);
        ItemStack after = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getItem();
        boolean outputChanged = after.isEmpty()
                || !ItemStack.isSameItemSameComponents(output, after)
                || output.getCount() != after.getCount();
        return handler.getCarried().isEmpty()
                && didOutputMove(outputChanged, countAfter > countBefore);
    }

    static boolean moveOutputWithQuickMove(Minecraft client,
                                           AbstractContainerMenu handler,
                                           QuickCraftRecipeBookLayout.Layout layout) {
        if (client == null || client.player == null || client.gameMode == null
                || handler == null
                || !handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).hasItem()) {
            return false;
        }

        ItemStack output = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getItem().copy();
        int countBefore = countMatching(handler, layout, output);
        client.gameMode.handleContainerInput(
                handler.containerId,
                QuickCraftRecipeBookLayout.OUTPUT_SLOT,
                0,
                ContainerInput.QUICK_MOVE,
                client.player
        );

        ItemStack after = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getItem();
        boolean outputChanged = after.isEmpty()
                || !ItemStack.isSameItemSameComponents(output, after)
                || output.getCount() != after.getCount();
        return didOutputMove(outputChanged,
                countMatching(handler, layout, output) > countBefore);
    }

    static boolean didOutputMove(boolean outputChanged,
                                 boolean matchingInventoryIncreased) {
        return outputChanged || matchingInventoryIncreased;
    }

    static int findMatchingUnlockedSlot(AbstractContainerMenu handler,
                                        QuickCraftRecipeBookLayout.Layout layout,
                                        ItemStack template) {
        if (handler == null || layout == null || template == null || template.isEmpty()) {
            return -1;
        }
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                return handlerSlot;
            }
        }
        return -1;
    }

    static boolean hasLockedPlayerSlot(AbstractContainerMenu handler,
                                       QuickCraftRecipeBookLayout.Layout layout) {
        if (handler == null || layout == null) {
            return false;
        }
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot >= 0 && QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                return true;
            }
        }
        return false;
    }

    private static int countMatching(AbstractContainerMenu handler,
                                     QuickCraftRecipeBookLayout.Layout layout,
                                     ItemStack template) {
        int total = 0;
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean canAccept(Slot slot, ItemStack incoming) {
        ItemStack existing = slot.getItem();
        return existing.isEmpty()
                || (ItemStack.isSameItemSameComponents(existing, incoming)
                && existing.getCount() + incoming.getCount()
                <= slot.getMaxStackSize(incoming));
    }

    static int firstPatternSlotWithRoom(AbstractContainerMenu handler,
                                        QuickCraftRecipeBookLayout.Layout layout,
                                        List<ItemStack> pattern,
                                        ItemStack stack) {
        if (handler == null || layout == null || pattern == null || stack == null || stack.isEmpty()) {
            return -1;
        }
        int limit = Math.min(pattern.size(), layout.gridSize());
        for (int i = 0; i < limit; i++) {
            ItemStack template = pattern.get(i);
            if (template == null || template.isEmpty()
                    || !ItemStack.isSameItemSameComponents(template, stack)) {
                continue;
            }
            int slotId = layout.gridSlotId(i);
            Slot slot = handler.getSlot(slotId);
            if (slot.mayPlace(stack) && canTakePartial(slot, stack)) {
                return slotId;
            }
        }
        return -1;
    }

    static int firstAcceptingGridSlot(AbstractContainerMenu handler,
                                      QuickCraftRecipeBookLayout.Layout layout,
                                      ItemStack template) {
        if (handler == null || layout == null || template == null || template.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < layout.gridSize(); i++) {
            int slotId = layout.gridSlotId(i);
            Slot slot = handler.getSlot(slotId);
            if (slot.mayPlace(template) && canTakePartial(slot, template)) {
                return slotId;
            }
        }
        return -1;
    }

    static int usableIngredientCount(int count, boolean retainSample) {
        return Math.max(0, count - (retainSample ? 1 : 0));
    }

    static int ingredientPickupButton(boolean retainSample) {
        return retainSample ? 1 : 0;
    }

    record GridBalanceMove(int source, int target, int count) {}

    static List<GridBalanceMove> planGridTailBalance(int[] counts) {
        List<GridBalanceMove> moves = new ArrayList<>();
        if (counts.length < 2) {
            return moves;
        }
        int total = 0;
        boolean missing = false;
        for (int count : counts) {
            total += count;
            missing |= count == 0;
        }
        if (!missing || total < counts.length) {
            return moves;
        }
        int[] remaining = counts.clone();
        int[] desired = new int[counts.length];
        for (int index = 0; index < counts.length; index++) {
            desired[index] = total / counts.length + (index < total % counts.length ? 1 : 0);
        }
        for (int target = 0; target < counts.length; target++) {
            for (int source = 0; source < counts.length && remaining[target] < desired[target]; source++) {
                int amount = Math.min(remaining[source] - desired[source], desired[target] - remaining[target]);
                if (amount > 0) {
                    moves.add(new GridBalanceMove(source, target, amount));
                    remaining[source] -= amount;
                    remaining[target] += amount;
                }
            }
        }
        return moves;
    }

    static int[] planSampleRefillSources(int[] sourceCounts, int missingSlots) {
        int[] remaining = sourceCounts.clone();
        int[] plan = new int[missingSlots];
        for (int target = 0; target < missingSlots; target++) {
            int best = -1;
            for (int source = 0; source < remaining.length; source++) {
                if (remaining[source] > 1 && (best < 0 || remaining[source] > remaining[best])) {
                    best = source;
                }
            }
            if (best < 0) {
                return null;
            }
            plan[target] = best;
            remaining[best] /= 2;
        }
        return plan;
    }

    static boolean wholeStackFitsInSlot(int sourceCount, int existingCount, int maxCount) {
        return sourceCount > 0 && existingCount >= 0 && existingCount + sourceCount <= maxCount;
    }

    static boolean canQuickMoveWholeStackToGridSlot(int sourceCount,
                                                    int existingCount,
                                                    int maxCount,
                                                    int firstAcceptingSlot,
                                                    int targetSlot) {
        return targetSlot >= 0
                && firstAcceptingSlot == targetSlot
                && wholeStackFitsInSlot(sourceCount, existingCount, maxCount);
    }

    static boolean shouldKeepFillingManualPattern(boolean missingPatternSlot,
                                                  boolean moveWholeStackToSingleSlot,
                                                  int attempts,
                                                  int sourceStackBudget) {
        return missingPatternSlot
                || (!moveWholeStackToSingleSlot && attempts < sourceStackBudget);
    }

    static boolean returnCursorToUnlockedInventory(Minecraft client,
                                                   AbstractContainerMenu handler,
                                                   QuickCraftRecipeBookLayout.Layout layout) {
        if (client == null || client.player == null || client.gameMode == null
                || handler == null) {
            return false;
        }
        if (handler.getCarried().isEmpty()) {
            return true;
        }
        for (int attempt = 0; attempt < 40 && !handler.getCarried().isEmpty(); attempt++) {
            ItemStack cursor = handler.getCarried();
            int before = cursor.getCount();
            int slot = findPartialAcceptingUnlockedSlot(handler, layout, cursor);
            if (slot < 0) {
                break;
            }
            client.gameMode.handleContainerInput(
                    handler.containerId,
                    slot,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
            ItemStack remaining = handler.getCarried();
            if (!remaining.isEmpty() && remaining.getCount() >= before) {
                break;
            }
        }
        return handler.getCarried().isEmpty();
    }

    private static int findPartialAcceptingUnlockedSlot(AbstractContainerMenu handler,
                                                        QuickCraftRecipeBookLayout.Layout layout,
                                                        ItemStack stack) {
        if (handler == null || layout == null || stack == null || stack.isEmpty()) {
            return -1;
        }
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            Slot slot = handler.getSlot(handlerSlot);
            if (slot.mayPlace(stack) && canTakePartial(slot, stack)) {
                return handlerSlot;
            }
        }
        return -1;
    }

    private static boolean canTakePartial(Slot slot, ItemStack incoming) {
        ItemStack existing = slot.getItem();
        return existing.isEmpty()
                || (ItemStack.isSameItemSameComponents(existing, incoming)
                && existing.getCount() < slot.getMaxStackSize(incoming));
    }
}
