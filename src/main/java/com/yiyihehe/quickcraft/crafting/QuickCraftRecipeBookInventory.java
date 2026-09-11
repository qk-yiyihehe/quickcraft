package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
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

    static boolean isPatternComplete(ScreenHandler handler,
                                     QuickCraftRecipeBookLayout.Layout layout,
                                     List<ItemStack> pattern) {
        if (handler == null || layout == null || pattern == null
                || pattern.size() != layout.gridSize()) {
            return false;
        }
        for (int i = 0; i < layout.gridSize(); i++) {
            ItemStack template = pattern.get(i);
            ItemStack existing = handler.getSlot(layout.gridSlotId(i)).getStack();
            if (template == null || template.isEmpty()) {
                if (!existing.isEmpty()) {
                    return false;
                }
                continue;
            }
            if (existing.isEmpty() || !ItemStack.areItemsAndComponentsEqual(existing, template)) {
                return false;
            }
        }
        return true;
    }

    static boolean canAcceptUnlocked(ScreenHandler handler,
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

    static int unlockedEmptySlots(ScreenHandler handler,
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
            ItemStack existing = handler.getSlot(handlerSlot).getStack();
            if (existing == null || existing.isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    static int unlockedCapacity(ScreenHandler handler,
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
            if (!slot.canInsert(stack)) {
                continue;
            }
            ItemStack existing = slot.getStack();
            if (existing.isEmpty()) {
                capacity += slot.getMaxItemCount(stack);
            } else if (ItemStack.areItemsAndComponentsEqual(existing, stack)) {
                capacity += Math.max(0, slot.getMaxItemCount(stack) - existing.getCount());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, capacity);
    }

    static int findAcceptingUnlockedSlot(ScreenHandler handler,
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
            if (slot.canInsert(stack) && canAccept(slot, stack)) {
                return handlerSlot;
            }
        }
        return -1;
    }

    static boolean hasUnevenMatchingGridStacks(ScreenHandler handler,
                                               QuickCraftRecipeBookLayout.Layout layout) {
        if (handler == null || layout == null) {
            return false;
        }
        for (int i = 0; i < layout.gridSize(); i++) {
            ItemStack left = handler.getSlot(layout.gridSlotId(i)).getStack();
            if (left.isEmpty()) {
                continue;
            }
            for (int j = i + 1; j < layout.gridSize(); j++) {
                ItemStack right = handler.getSlot(layout.gridSlotId(j)).getStack();
                if (right.isEmpty() || !ItemStack.areItemsAndComponentsEqual(left, right)) {
                    continue;
                }
                if (left.getCount() != right.getCount()) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean takeOneOutputToUnlockedInventory(MinecraftClient client,
                                                    ScreenHandler handler,
                                                    QuickCraftRecipeBookLayout.Layout layout) {
        if (client == null || client.player == null || client.interactionManager == null
                || handler == null || !handler.getCursorStack().isEmpty()
                || !handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).hasStack()) {
            return false;
        }

        ItemStack output = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getStack().copy();
        int targetSlot = findAcceptingUnlockedSlot(handler, layout, output);
        if (targetSlot < 0) {
            return false;
        }
        int countBefore = countMatching(handler, layout, output);
        client.interactionManager.clickSlot(
                handler.syncId,
                QuickCraftRecipeBookLayout.OUTPUT_SLOT,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty() || !ItemStack.areItemsAndComponentsEqual(cursor, output)) {
            return false;
        }
        client.interactionManager.clickSlot(
                handler.syncId,
                targetSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        ItemStack after = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getStack();
        boolean outputChanged = after.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(output, after)
                || output.getCount() != after.getCount();
        return handler.getCursorStack().isEmpty()
                && didOutputMove(outputChanged, countMatching(handler, layout, output) > countBefore);
    }

    static boolean moveOutputToUnlockedInventory(MinecraftClient client,
                                                 ScreenHandler handler,
                                                 QuickCraftRecipeBookLayout.Layout layout) {
        return moveOutputToUnlockedInventory(client, handler, layout, false);
    }

    private static boolean moveOutputToUnlockedInventory(MinecraftClient client,
                                                         ScreenHandler handler,
                                                         QuickCraftRecipeBookLayout.Layout layout,
                                                         boolean preferQuickMove) {
        if (client == null || client.player == null || client.interactionManager == null
                || handler == null || !handler.getCursorStack().isEmpty()
                || !handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).hasStack()) {
            return false;
        }

        ItemStack output = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getStack().copy();
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

    private static boolean moveOutputWithPickup(MinecraftClient client,
                                                ScreenHandler handler,
                                                QuickCraftRecipeBookLayout.Layout layout) {
        if (client == null || client.player == null || client.interactionManager == null
                || handler == null || !handler.getCursorStack().isEmpty()
                || !handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).hasStack()) {
            return false;
        }

        ItemStack output = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getStack().copy();
        return moveOutputWithPickup(client, handler, layout, output);
    }

    private static boolean moveOutputWithPickup(MinecraftClient client,
                                                ScreenHandler handler,
                                                QuickCraftRecipeBookLayout.Layout layout,
                                                ItemStack output) {
        if (findAcceptingUnlockedSlot(handler, layout, output) < 0) {
            return false;
        }
        int countBefore = countMatching(handler, layout, output);
        client.interactionManager.clickSlot(
                handler.syncId,
                QuickCraftRecipeBookLayout.OUTPUT_SLOT,
                0,
                SlotActionType.PICKUP,
                client.player
        );
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty() || !ItemStack.areItemsAndComponentsEqual(cursor, output)) {
            return false;
        }
        int targetSlot = findAcceptingUnlockedSlot(handler, layout, cursor);
        if (targetSlot < 0) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    -999,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
            return handler.getCursorStack().isEmpty();
        }
        client.interactionManager.clickSlot(
                handler.syncId,
                targetSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        int countAfter = countMatching(handler, layout, output);
        ItemStack after = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getStack();
        boolean outputChanged = after.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(output, after)
                || output.getCount() != after.getCount();
        return handler.getCursorStack().isEmpty()
                && didOutputMove(outputChanged, countAfter > countBefore);
    }

    static boolean moveOutputWithQuickMove(MinecraftClient client,
                                           ScreenHandler handler,
                                           QuickCraftRecipeBookLayout.Layout layout) {
        if (client == null || client.player == null || client.interactionManager == null
                || handler == null
                || !handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).hasStack()) {
            return false;
        }

        ItemStack output = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getStack().copy();
        int countBefore = countMatching(handler, layout, output);
        client.interactionManager.clickSlot(
                handler.syncId,
                QuickCraftRecipeBookLayout.OUTPUT_SLOT,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        ItemStack after = handler.getSlot(QuickCraftRecipeBookLayout.OUTPUT_SLOT).getStack();
        boolean outputChanged = after.isEmpty()
                || !ItemStack.areItemsAndComponentsEqual(output, after)
                || output.getCount() != after.getCount();
        return didOutputMove(outputChanged,
                countMatching(handler, layout, output) > countBefore);
    }

    static boolean didOutputMove(boolean outputChanged,
                                 boolean matchingInventoryIncreased) {
        return outputChanged || matchingInventoryIncreased;
    }

    static int findMatchingUnlockedSlot(ScreenHandler handler,
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
            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                return handlerSlot;
            }
        }
        return -1;
    }

    static int countMatchingUnlockedSlots(ScreenHandler handler,
                                          QuickCraftRecipeBookLayout.Layout layout,
                                          ItemStack template) {
        if (handler == null || layout == null || template == null || template.isEmpty()) {
            return 0;
        }
        int matchingSlots = 0;
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                matchingSlots++;
            }
        }
        return matchingSlots;
    }

    static int dropMatchingUnlockedInventory(MinecraftClient client,
                                             ScreenHandler handler,
                                             QuickCraftRecipeBookLayout.Layout layout,
                                             ItemStack template,
                                             String reason) {
        if (client == null || client.player == null || client.interactionManager == null
                || handler == null || layout == null || template == null || template.isEmpty()
                || !handler.getCursorStack().isEmpty()) {
            return 0;
        }

        int droppedSlots = 0;
        for (int inventoryIndex = 0;
             inventoryIndex < QuickCraftRecipeBookLayout.PLAYER_INVENTORY_SIZE;
             inventoryIndex++) {
            int handlerSlot = layout.handlerSlotForInventoryIndex(inventoryIndex);
            if (handlerSlot < 0 || QuickContainerLock.isLockedSlot(handler, handlerSlot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, template)) {
                continue;
            }
            ItemStack dropped = stack.copy();
            client.interactionManager.clickSlot(
                    handler.syncId, handlerSlot, 1, SlotActionType.THROW, client.player);
            droppedSlots++;
            LOGGER.info("{}：界面={}，槽={}，物品={}", reason, layout.name(), handlerSlot, dropped);
        }
        return droppedSlots;
    }

    static boolean hasLockedPlayerSlot(ScreenHandler handler,
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

    private static int countMatching(ScreenHandler handler,
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
            ItemStack stack = handler.getSlot(handlerSlot).getStack();
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean canAccept(Slot slot, ItemStack incoming) {
        ItemStack existing = slot.getStack();
        return existing.isEmpty()
                || (ItemStack.areItemsAndComponentsEqual(existing, incoming)
                && existing.getCount() + incoming.getCount()
                <= slot.getMaxItemCount(incoming));
    }

    static int firstPatternSlotWithRoom(ScreenHandler handler,
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
                    || !ItemStack.areItemsAndComponentsEqual(template, stack)) {
                continue;
            }
            int slotId = layout.gridSlotId(i);
            Slot slot = handler.getSlot(slotId);
            if (slot.canInsert(stack) && canTakePartial(slot, stack)) {
                return slotId;
            }
        }
        return -1;
    }

    static int firstAcceptingGridSlot(ScreenHandler handler,
                                      QuickCraftRecipeBookLayout.Layout layout,
                                      ItemStack template) {
        if (handler == null || layout == null || template == null || template.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < layout.gridSize(); i++) {
            int slotId = layout.gridSlotId(i);
            Slot slot = handler.getSlot(slotId);
            if (slot.canInsert(template) && canTakePartial(slot, template)) {
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

    static int tailItemsPerSlot(int sourceCount,
                                int targetSlots,
                                int remainingCapacityPerSlot) {
        if (sourceCount <= 0 || targetSlots <= 0 || remainingCapacityPerSlot <= 0) {
            return 0;
        }
        return Math.min(sourceCount / targetSlots, remainingCapacityPerSlot);
    }

    static boolean canQuickTopUpCompleteGroup(int occurrences,
                                               int availableItems,
                                               int remainingCapacity) {
        return occurrences == 1
                || (occurrences > 1 && remainingCapacity > 0
                && availableItems >= remainingCapacity);
    }

    static boolean returnCursorToUnlockedInventory(MinecraftClient client,
                                                   ScreenHandler handler,
                                                   QuickCraftRecipeBookLayout.Layout layout) {
        if (client == null || client.player == null || client.interactionManager == null
                || handler == null) {
            return false;
        }
        if (handler.getCursorStack().isEmpty()) {
            return true;
        }
        for (int attempt = 0; attempt < 40 && !handler.getCursorStack().isEmpty(); attempt++) {
            ItemStack cursor = handler.getCursorStack();
            int before = cursor.getCount();
            int slot = findPartialAcceptingUnlockedSlot(handler, layout, cursor);
            if (slot < 0) {
                break;
            }
            client.interactionManager.clickSlot(
                    handler.syncId,
                    slot,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
            ItemStack remaining = handler.getCursorStack();
            if (!remaining.isEmpty() && remaining.getCount() >= before) {
                break;
            }
        }
        return handler.getCursorStack().isEmpty();
    }

    private static int findPartialAcceptingUnlockedSlot(ScreenHandler handler,
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
            if (slot.canInsert(stack) && canTakePartial(slot, stack)) {
                return handlerSlot;
            }
        }
        return -1;
    }

    private static boolean canTakePartial(Slot slot, ItemStack incoming) {
        ItemStack existing = slot.getStack();
        return existing.isEmpty()
                || (ItemStack.areItemsAndComponentsEqual(existing, incoming)
                && existing.getCount() < slot.getMaxItemCount(incoming));
    }
}
