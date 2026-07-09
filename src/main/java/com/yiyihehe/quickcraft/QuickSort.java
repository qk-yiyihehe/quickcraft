package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import com.yiyihehe.quickcraft.mixin.CreativeInventoryScreenInvoker;
import com.yiyihehe.quickcraft.mixin.CreativeSlotAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一键整理入口。
 * 根据鼠标所在的玩家区/容器区决定整理目标，先合并同类堆叠，再按固定顺序重排槽位。
 */
public class QuickSort implements ClientModInitializer {
    private static final int SLOT_SIZE = 18;
    private static final int BOUNDS_PADDING = 4;
    private static final ItemStack INSERT_TEST_STACK = Items.DIRT.getDefaultStack();
    private static final List<RegistryKey<ItemGroup>> CATEGORY_ORDER = List.of(
        ItemGroups.BUILDING_BLOCKS,
        ItemGroups.COLORED_BLOCKS,
        ItemGroups.NATURAL,
        ItemGroups.FUNCTIONAL,
        ItemGroups.REDSTONE,
        ItemGroups.TOOLS,
        ItemGroups.COMBAT,
        ItemGroups.FOOD_AND_DRINK,
        ItemGroups.INGREDIENTS,
        ItemGroups.SPAWN_EGGS,
        ItemGroups.OPERATOR
    );
    private static final Map<RegistryKey<ItemGroup>, Map<ItemKey, Integer>> CATEGORY_EXACT_ORDER_CACHE = new HashMap<>();
    private static final Map<RegistryKey<ItemGroup>, Map<String, Integer>> CATEGORY_ITEM_ORDER_CACHE = new HashMap<>();
    private boolean lastQuickSortDown;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        boolean quickSortDown = QuickCraftConfigs.getQuickSortHotkey().isKeybindHeld();
        if (quickSortDown && !lastQuickSortDown) {
            handleQuickSortHotkey(client);
        }
        lastQuickSortDown = quickSortDown;
    }

    public static boolean handleQuickSortHotkey(MinecraftClient client) {
        if (client == null || client.player == null || !QuickCraftConfigs.isQuickSortEnabled()) {
            return false;
        }

        if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
            if (isTextInputFocused(handledScreen)) {
                return false;
            }
            sortInventory(handledScreen);
            return true;
        }

        return false;
    }

    public static void sortInventory(HandledScreen<?> gui) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        ScreenHandler handler = gui.getScreenHandler();
        SortTarget target = findSortTarget(gui);
        if (target == null || target.slotIds.size() < 2) {
            return;
        }

        if (isLockedSortTarget(client, gui, target)) {
            return;
        }

        if (!(gui instanceof CreativeInventoryScreen)) {
            ensureItemGroupDisplayContext(client);
        }

        ScreenHandler targetHandler = target.handler();
        if (!targetHandler.getCursorStack().isEmpty() && !storeCursorStackForTarget(gui, target)) {
            return;
        }

        mergeIdenticalStacks(targetHandler, target.slotIds);
        reorderSlots(targetHandler, target.slotIds, buildTargetOrder(targetHandler, target.slotIds), targetHandler == handler);
        if (!targetHandler.getCursorStack().isEmpty()) {
            storeCursorStackForTarget(gui, target);
        }
    }

    private static SortTarget findSortTarget(HandledScreen<?> gui) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return null;
        }

        HandledScreenAccessor accessor = (HandledScreenAccessor) gui;
        int guiLeft = accessor.quickcraft$getGuiLeft();
        int guiTop = accessor.quickcraft$getGuiTop();
        int mouseX = getMouseX(client);
        int mouseY = getMouseY(client);

        List<SortTarget> targets = new ArrayList<>();
        targets.addAll(buildPlayerTargets(gui, guiLeft, guiTop));
        targets.addAll(buildContainerTargets(gui, guiLeft, guiTop));

        return targets.stream()
            .filter(target -> target.bounds.contains(mouseX, mouseY))
            .min(Comparator.comparingInt(target -> target.bounds.area()))
            .orElse(null);
    }

    private static boolean isLockedSortTarget(MinecraftClient client, HandledScreen<?> gui, SortTarget target) {
        return switch (target.label()) {
            case "container" -> QuickContainerLock.handleLockedSortAttempt(client, gui);
            case "player-main", "player-hotbar", "creative-player-main", "creative-player-hotbar" ->
                QuickContainerLock.handleLockedPlayerInventorySortAttempt(client);
            default -> false;
        };
    }

    private static boolean isTextInputFocused(HandledScreen<?> gui) {
        Element focused = gui.getFocused();
        return focused instanceof TextFieldWidget;
    }

    private static List<SortTarget> buildPlayerTargets(HandledScreen<?> gui, int guiLeft, int guiTop) {
        ScreenHandler handler = gui.getScreenHandler();
        List<SortTarget> targets = new ArrayList<>();
        if (!shouldShowPlayerSortTargets(gui)) {
            return targets;
        }

        List<Slot> playerSlots = handler.slots.stream()
            .filter(slot -> isPlayerAreaSlot(gui, slot))
            .filter(QuickSort::isVisibleSlot)
            .toList();

        Map<Integer, List<Slot>> rows = groupSlotsByY(playerSlots);
        List<Integer> rowKeys = rows.keySet().stream().sorted().toList();
        List<List<Slot>> nineWideRows = rowKeys.stream()
            .map(rows::get)
            .map(QuickSort::sortSlotsForLayout)
            .filter(row -> row.size() == 9)
            .toList();

        if (gui instanceof CreativeInventoryScreen creativeScreen) {
            addCreativeTargets(creativeScreen, targets, playerSlots, guiLeft, guiTop);
            return targets;
        }

        if (nineWideRows.size() < 4) {
            return targets;
        }

        List<Slot> hotbarRow = nineWideRows.get(nineWideRows.size() - 1);
        List<Slot> mainRows = new ArrayList<>();
        for (int i = Math.max(0, nineWideRows.size() - 4); i < nineWideRows.size() - 1; i++) {
            mainRows.addAll(nineWideRows.get(i));
        }

        targets.add(new SortTarget(
            "player-main",
            toUnlockedPlayerSortSlotIds(handler, mainRows),
            Bounds.fromSlots(mainRows, guiLeft, guiTop),
            handler
        ));
        targets.add(new SortTarget(
            "player-hotbar",
            toUnlockedPlayerSortSlotIds(handler, hotbarRow),
            Bounds.fromSlots(hotbarRow, guiLeft, guiTop),
            handler
        ));

        return targets;
    }

    private static void addCreativeTargets(CreativeInventoryScreen gui,
                                           List<SortTarget> targets,
                                           List<Slot> playerSlots,
                                           int guiLeft,
                                           int guiTop) {
        if (gui.isInventoryTabSelected()) {
            addCreativeInventoryTabTargets(gui, targets, playerSlots, guiLeft, guiTop);
            return;
        }

        addCreativeHotbarTarget(gui, targets, playerSlots, guiLeft, guiTop);
    }

    private static void addCreativeInventoryTabTargets(CreativeInventoryScreen gui,
                                                       List<SortTarget> targets,
                                                       List<Slot> playerSlots,
                                                       int guiLeft,
                                                       int guiTop) {
        // 创造背包背后的底层玩家 handler 还挂着隐藏槽；这里必须只用当前界面可见槽位所在的 handler，
        // 否则高版本整理时可能会借到隐藏槽位腾挪，表现成穿装备或复制一份。
        ScreenHandler handler = gui.getScreenHandler();
        List<Slot> mainSlots = sortSlotsForLayout(playerSlots).stream()
            .filter(QuickSort::isPlayerMainInventorySlot)
            .toList();
        List<Slot> hotbarSlots = sortSlotsForLayout(playerSlots).stream()
            .filter(QuickSort::isPlayerHotbarSlot)
            .toList();

        if (mainSlots.size() == 27) {
            targets.add(new SortTarget(
                "creative-player-main",
                toUnlockedPlayerSortSlotIds(handler, mainSlots),
                Bounds.fromSlots(mainSlots, guiLeft, guiTop),
                handler
            ));
        }
        if (hotbarSlots.size() == 9) {
            targets.add(new SortTarget(
                "creative-player-hotbar",
                toUnlockedPlayerSortSlotIds(handler, hotbarSlots),
                Bounds.fromSlots(hotbarSlots, guiLeft, guiTop),
                handler
            ));
        }
    }

    private static void addCreativeHotbarTarget(CreativeInventoryScreen gui,
                                                List<SortTarget> targets,
                                                List<Slot> playerSlots,
                                                int guiLeft,
                                                int guiTop) {
        ScreenHandler handler = gui.getScreenHandler();
        List<Slot> hotbarSlots = sortSlotsForLayout(playerSlots).stream()
            .filter(QuickSort::isPlayerHotbarSlot)
            .toList();
        if (hotbarSlots.size() != 9) {
            return;
        }

        // 创造分类页只显示快捷栏，不能把上方物品列表当成玩家背包整理。
        targets.add(new SortTarget(
            "creative-player-hotbar",
            toUnlockedPlayerSortSlotIds(handler, hotbarSlots),
            Bounds.fromSlots(hotbarSlots, guiLeft, guiTop),
            handler
        ));
    }

    private static List<SortTarget> buildContainerTargets(HandledScreen<?> gui, int guiLeft, int guiTop) {
        MinecraftClient client = MinecraftClient.getInstance();
        ScreenHandler handler = gui.getScreenHandler();
        List<SortTarget> targets = new ArrayList<>();
        Map<Inventory, List<Slot>> groups = new IdentityHashMap<>();

        if (gui instanceof CreativeInventoryScreen) {
            return targets;
        }

        for (Slot slot : gui.getScreenHandler().slots) {
            if (isPlayerAreaSlot(gui, slot)) {
                continue;
            }
            if (!isContainerSortCandidateSlot(handler, slot, client)) {
                continue;
            }
            groups.computeIfAbsent(slot.inventory, ignored -> new ArrayList<>()).add(slot);
        }

        for (List<Slot> group : groups.values()) {
            List<Slot> ordered = sortSlotsForLayout(group);
            if (!isLikelySortableContainer(gui, ordered)) {
                continue;
            }

            targets.add(new SortTarget(
                "container",
                toUnlockedContainerSortSlotIds(handler, ordered),
                Bounds.fromSlots(ordered, guiLeft, guiTop),
                handler
            ));
        }

        return targets;
    }

    private static boolean shouldShowPlayerSortTargets(HandledScreen<?> gui) {
        if (gui instanceof CreativeInventoryScreen creativeScreen) {
            return creativeScreen.isInventoryTabSelected();
        }
        return true;
    }

    private static boolean isContainerSortCandidateSlot(ScreenHandler handler, Slot slot, MinecraftClient client) {
        if (!isVisibleSlot(slot) || client.player == null) {
            return false;
        }
        if (QuickContainerLock.isLockedSlot(handler, slot)) {
            return true;
        }
        if (!slot.canTakeItems(client.player)) {
            return false;
        }
        return slot.canInsert(INSERT_TEST_STACK);
    }

    private static boolean isContainerSortableSlot(ScreenHandler handler, Slot slot, MinecraftClient client) {
        if (!isVisibleSlot(slot) || client.player == null) {
            return false;
        }
        if (QuickContainerLock.isLockedSlot(handler, slot)) {
            return false;
        }
        if (!slot.canTakeItems(client.player)) {
            return false;
        }
        return slot.canInsert(INSERT_TEST_STACK);
    }

    private static boolean isLikelySortableContainer(HandledScreen<?> gui, List<Slot> slots) {
        if (slots.size() < 5) {
            return false;
        }

        long columnCount = slots.stream().map(slot -> slot.x).distinct().count();
        long rowCount = slots.stream().map(slot -> slot.y).distinct().count();
        String handlerName = gui.getScreenHandler().getClass().getSimpleName();

        if (Objects.equals(handlerName, "CraftingScreenHandler") || Objects.equals(handlerName, "PlayerScreenHandler")) {
            if (columnCount <= 3 && rowCount <= 3) {
                return false;
            }
        }

        return rowCount >= 1 && columnCount >= 3;
    }

    private static boolean isPlayerAreaSlot(HandledScreen<?> gui, Slot slot) {
        Slot effectiveSlot = unwrapCreativeSlot(slot);
        return effectiveSlot.inventory instanceof PlayerInventory
            && effectiveSlot.getIndex() >= 0
            && effectiveSlot.getIndex() < 36;
    }

    private static boolean isPlayerHotbarSlot(Slot slot) {
        Slot effectiveSlot = unwrapCreativeSlot(slot);
        return effectiveSlot.inventory instanceof PlayerInventory
            && effectiveSlot.getIndex() >= 0
            && effectiveSlot.getIndex() < 9;
    }

    private static boolean isPlayerMainInventorySlot(Slot slot) {
        Slot effectiveSlot = unwrapCreativeSlot(slot);
        return effectiveSlot.inventory instanceof PlayerInventory
            && effectiveSlot.getIndex() >= 9
            && effectiveSlot.getIndex() < 36;
    }

    private static Slot unwrapCreativeSlot(Slot slot) {
        if (slot instanceof CreativeSlotAccessor accessor) {
            Slot wrappedSlot = accessor.quickcraft$getWrappedSlot();
            if (wrappedSlot != null) {
                return wrappedSlot;
            }
        }
        return slot;
    }

    private static void mergeIdenticalStacks(ScreenHandler handler, List<Integer> slotIds) {
        Map<ItemKey, Integer> primarySlots = new HashMap<>();

        for (int slotId : slotIds) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack()) {
                continue;
            }

            ItemStack stack = slot.getStack();
            ItemKey key = new ItemKey(stack);
            if (stack.getCount() >= stack.getMaxCount()) {
                continue;
            }

            Integer primarySlotId = primarySlots.get(key);
            if (primarySlotId == null) {
                primarySlots.put(key, slotId);
                continue;
            }

            Slot primarySlot = handler.getSlot(primarySlotId);
            if (!canStacksMerge(primarySlot.getStack(), stack)) {
                primarySlots.put(key, slotId);
                continue;
            }

            mergeSlots(handler, slotId, primarySlotId);

            if (slot.hasStack()) {
                primarySlots.put(key, slotId);
            } else if (!primarySlot.hasStack() || primarySlot.getStack().getCount() >= primarySlot.getStack().getMaxCount()) {
                primarySlots.remove(key);
            }
        }
    }

    private static void reorderSlots(ScreenHandler handler,
                                     List<Integer> slotIds,
                                     List<ItemStack> targetOrder,
                                     boolean useVisibleStorageFallback) {
        for (int targetIndex = 0; targetIndex < slotIds.size(); targetIndex++) {
            int targetSlotId = slotIds.get(targetIndex);
            Slot targetSlot = handler.getSlot(targetSlotId);
            ItemStack expected = targetIndex < targetOrder.size() ? targetOrder.get(targetIndex) : ItemStack.EMPTY;

            if (stacksEqualExactly(targetSlot.getStack(), expected)) {
                continue;
            }

            int sourceIndex = findMatchingSourceIndex(handler, slotIds, targetIndex + 1, expected);
            if (sourceIndex < 0) {
                continue;
            }

            int sourceSlotId = slotIds.get(sourceIndex);

            if (expected.isEmpty()) {
                moveStackToEmptySlot(handler, targetSlotId, sourceSlotId);
            } else if (targetSlot.getStack().isEmpty()) {
                moveStackToEmptySlot(handler, sourceSlotId, targetSlotId);
            } else {
                swapSlots(handler, targetSlotId, sourceSlotId);
            }

            if (!handler.getCursorStack().isEmpty()) {
                boolean stored = useVisibleStorageFallback
                    ? storeCursorStackInVisibleSlots(handler)
                    : storeCursorStack(handler, slotIds);
                if (!stored) {
                    return;
                }
            }

        }
    }

    private static List<ItemStack> buildTargetOrder(ScreenHandler handler, List<Integer> slotIds) {
        List<ItemStack> priorityStacks = new ArrayList<>();
        List<ItemStack> normalStacks = new ArrayList<>();
        List<ItemStack> bundleStacks = new ArrayList<>();
        List<ItemStack> shulkerStacks = new ArrayList<>();

        for (int slotId : slotIds) {
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack copy = stack.copy();
            if (isPriorityFrontStack(copy)) {
                priorityStacks.add(copy);
            } else if (isBundle(copy)) {
                bundleStacks.add(copy);
            } else if (isShulkerBox(copy)) {
                shulkerStacks.add(copy);
            } else {
                normalStacks.add(copy);
            }
        }

        priorityStacks.sort(QuickSort::compareStacks);
        normalStacks.sort(QuickSort::compareStacks);
        bundleStacks.sort(QuickSort::compareBundleStacks);
        shulkerStacks.sort(QuickSort::compareShulkerStacks);

        int totalSlots = slotIds.size();
        int reservedBottomSlots = Math.min(bundleStacks.size() + shulkerStacks.size(), totalSlots);
        int normalSlotCount = totalSlots - reservedBottomSlots;
        List<ItemStack> result = new ArrayList<>(totalSlots);

        for (int i = 0; i < priorityStacks.size() && result.size() < normalSlotCount; i++) {
            result.add(priorityStacks.get(i));
        }

        for (int i = 0; i < normalStacks.size() && result.size() < normalSlotCount; i++) {
            result.add(normalStacks.get(i));
        }

        while (result.size() < normalSlotCount) {
            result.add(ItemStack.EMPTY);
        }

        result.addAll(bundleStacks);
        result.addAll(shulkerStacks);

        while (result.size() < totalSlots) {
            result.add(ItemStack.EMPTY);
        }

        return result;
    }

    private static int findMatchingSourceIndex(ScreenHandler handler, List<Integer> slotIds, int startIndex, ItemStack expected) {
        for (int i = startIndex; i < slotIds.size(); i++) {
            Slot slot = handler.getSlot(slotIds.get(i));
            if (stacksEqualExactly(slot.getStack(), expected)) {
                return i;
            }
        }
        return -1;
    }

    private static void mergeSlots(ScreenHandler handler, int sourceSlotId, int targetSlotId) {
        clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        clickSlot(handler, targetSlotId, 0, SlotActionType.PICKUP);
        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(handler, sourceSlotId, 0, SlotActionType.PICKUP);
        }
    }

    private static void moveStackToEmptySlot(ScreenHandler handler, int fromSlotId, int toSlotId) {
        clickSlot(handler, fromSlotId, 0, SlotActionType.PICKUP);
        clickSlot(handler, toSlotId, 0, SlotActionType.PICKUP);
    }

    private static void swapSlots(ScreenHandler handler, int slotA, int slotB) {
        int swapBufferHotbar = findSwapBufferHotbarIndex(handler, slotA, slotB);
        if (swapBufferHotbar >= 0) {
            clickSlot(handler, slotA, swapBufferHotbar, SlotActionType.SWAP);
            clickSlot(handler, slotB, swapBufferHotbar, SlotActionType.SWAP);
            clickSlot(handler, slotA, swapBufferHotbar, SlotActionType.SWAP);
            return;
        }

        clickSlot(handler, slotA, 0, SlotActionType.PICKUP);
        clickSlot(handler, slotB, 0, SlotActionType.PICKUP);
        clickSlot(handler, slotA, 0, SlotActionType.PICKUP);
    }

    /**
     * 用一个不参与当前交换的快捷栏槽位做缓冲，可以避免同类可堆叠物品在交换时被误合并。
     * 缓冲物品也必须能临时放进两个目标槽，避免潜影盒整理时 SWAP 被服务端拒绝。
     */
    private static int findSwapBufferHotbarIndex(ScreenHandler handler, int slotA, int slotB) {
        MinecraftClient client = MinecraftClient.getInstance();
        Slot targetA = handler.getSlot(slotA);
        Slot targetB = handler.getSlot(slotB);

        for (Slot slot : handler.slots) {
            Slot effectiveSlot = unwrapCreativeSlot(slot);
            if (!(effectiveSlot.inventory instanceof PlayerInventory)) {
                continue;
            }

            int playerInventoryIndex = effectiveSlot.getIndex();
            if (playerInventoryIndex < 0 || playerInventoryIndex > 8) {
                continue;
            }

            int clickSlotId = getClickSlotId(handler, slot);
            if (clickSlotId == slotA || clickSlotId == slotB) {
                continue;
            }
            if (QuickContainerLock.isLockedSlot(handler, slot)) {
                continue;
            }
            if (client.player != null && !slot.canTakeItems(client.player)) {
                continue;
            }

            ItemStack bufferStack = effectiveSlot.getStack();
            if (bufferStack.isEmpty()) {
                return playerInventoryIndex;
            }
            if (targetA.canInsert(bufferStack)
                && targetB.canInsert(bufferStack)) {
                return playerInventoryIndex;
            }
        }

        return -1;
    }

    private static boolean storeCursorStackInVisibleSlots(ScreenHandler handler) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false;
        }

        ItemStack cursorStack = handler.getCursorStack();
        if (cursorStack.isEmpty()) {
            return true;
        }

        // 满背包/满容器时，优先尝试并入可叠加的同类堆叠，避免因为没有空位直接失败。
        for (Slot slot : handler.slots) {
            if (!isSafeVisibleStorageSlot(handler, slot, client)) {
                continue;
            }
            if (!isVisibleSlot(slot) || !slot.hasStack()) {
                continue;
            }
            if (!slot.canTakeItems(client.player) || !slot.canInsert(cursorStack)) {
                continue;
            }
            if (!canStacksMerge(slot.getStack(), cursorStack)) {
                continue;
            }

            clickSlot(handler, getClickSlotId(handler, slot), 0, SlotActionType.PICKUP);
            if (handler.getCursorStack().isEmpty()) {
                return true;
            }
        }

        for (Slot slot : handler.slots) {
            if (!isSafeVisibleStorageSlot(handler, slot, client)) {
                continue;
            }
            if (!isVisibleSlot(slot) || slot.hasStack()) {
                continue;
            }
            if (!slot.canTakeItems(client.player) || !slot.canInsert(cursorStack)) {
                continue;
            }

            clickSlot(handler, getClickSlotId(handler, slot), 0, SlotActionType.PICKUP);
            if (handler.getCursorStack().isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private static boolean isSafeVisibleStorageSlot(ScreenHandler handler, Slot slot, MinecraftClient client) {
        if (client.currentScreen instanceof CreativeInventoryScreen creativeScreen
            && handler == creativeScreen.getScreenHandler()) {
            return isPlayerAreaSlot(creativeScreen, slot);
        }
        return true;
    }

    private static boolean storeCursorStack(ScreenHandler handler, List<Integer> slotIds) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false;
        }

        ItemStack cursorStack = handler.getCursorStack();
        if (cursorStack.isEmpty()) {
            return true;
        }

        for (int slotId : slotIds) {
            Slot slot = handler.getSlot(slotId);
            if (!slot.hasStack()) {
                continue;
            }
            if (!slot.canTakeItems(client.player) || !slot.canInsert(cursorStack)) {
                continue;
            }
            if (!canStacksMerge(slot.getStack(), cursorStack)) {
                continue;
            }

            clickSlot(handler, slotId, 0, SlotActionType.PICKUP);
            if (handler.getCursorStack().isEmpty()) {
                return true;
            }
        }

        for (int slotId : slotIds) {
            Slot slot = handler.getSlot(slotId);
            if (slot.hasStack()) {
                continue;
            }
            if (!slot.canTakeItems(client.player) || !slot.canInsert(cursorStack)) {
                continue;
            }

            clickSlot(handler, slotId, 0, SlotActionType.PICKUP);
            if (handler.getCursorStack().isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private static boolean storeCursorStackForTarget(HandledScreen<?> gui, SortTarget target) {
        if (target.handler() == gui.getScreenHandler()) {
            return storeCursorStackInVisibleSlots(gui.getScreenHandler());
        }
        return storeCursorStack(target.handler(), target.slotIds);
    }

    private static boolean canStacksMerge(ItemStack target, ItemStack source) {
        if (target.isEmpty() || source.isEmpty()) {
            return false;
        }
        if (!ItemStack.areItemsAndComponentsEqual(target, source)) {
            return false;
        }
        return target.getCount() < target.getMaxCount();
    }

    private static int compareStacks(ItemStack a, ItemStack b) {
        int categoryCompare = Integer.compare(getCategoryIndex(a), getCategoryIndex(b));
        if (categoryCompare != 0) {
            return categoryCompare;
        }

        int categoryIndex = getCategoryIndex(a);
        int creativeTabOrderCompare = Integer.compare(
            getCreativeTabOrderIndex(a, categoryIndex),
            getCreativeTabOrderIndex(b, categoryIndex)
        );
        if (creativeTabOrderCompare != 0) {
            return creativeTabOrderCompare;
        }

        String aItemId = getItemId(a);
        String bItemId = getItemId(b);
        int itemIdCompare = aItemId.compareTo(bItemId);
        if (itemIdCompare != 0) {
            return itemIdCompare;
        }

        int componentCompare = Integer.compare(a.getComponents().hashCode(), b.getComponents().hashCode());
        if (componentCompare != 0) {
            return componentCompare;
        }

        return Integer.compare(b.getCount(), a.getCount());
    }

    private static int compareShulkerStacks(ItemStack a, ItemStack b) {
        return compareStorageStacks(a, b, getShulkerContentsSortKey(a), getShulkerContentsSortKey(b));
    }

    private static int compareBundleStacks(ItemStack a, ItemStack b) {
        return compareStorageStacks(a, b, getBundleContentsSortKey(a), getBundleContentsSortKey(b));
    }

    private static int compareStorageStacks(ItemStack a,
                                            ItemStack b,
                                            StorageContentsSortKey aKey,
                                            StorageContentsSortKey bKey) {
        int kindCompare = Integer.compare(aKey.kindRank(), bKey.kindRank());
        if (kindCompare != 0) {
            return kindCompare;
        }

        if (!aKey.representativeStack().isEmpty() && !bKey.representativeStack().isEmpty()) {
            int representativeCompare = compareStacks(aKey.representativeStack(), bKey.representativeStack());
            if (representativeCompare != 0) {
                return representativeCompare;
            }
        }

        int contentsCompare = aKey.contentsKey().compareTo(bKey.contentsKey());
        if (contentsCompare != 0) {
            return contentsCompare;
        }
        return compareStacks(a, b);
    }

    private static int getCategoryIndex(ItemStack stack) {
        ItemStack normalizedStack = normalizeForLookup(stack);
        String itemId = getItemId(normalizedStack);
        for (int i = 0; i < CATEGORY_ORDER.size(); i++) {
            ItemGroup group = Registries.ITEM_GROUP.get(CATEGORY_ORDER.get(i).getValue());
            if (group != null && group.contains(normalizedStack)) {
                return i;
            }
        }

        // 带附魔/名称等组件变化的物品，精确匹配不到时回退到物品 id，
        // 这样附魔铁剑之类仍按基础物品分类，而不会被扔到最后。
        for (int i = 0; i < CATEGORY_ORDER.size(); i++) {
            RegistryKey<ItemGroup> groupKey = CATEGORY_ORDER.get(i);
            Map<String, Integer> itemOrder = CATEGORY_ITEM_ORDER_CACHE.computeIfAbsent(
                groupKey,
                QuickSort::buildItemOrderMap
            );
            if (itemOrder.containsKey(itemId)) {
                return i;
            }
        }

        return CATEGORY_ORDER.size();
    }

    private static int getCreativeTabOrderIndex(ItemStack stack, int categoryIndex) {
        if (categoryIndex < 0 || categoryIndex >= CATEGORY_ORDER.size()) {
            return Integer.MAX_VALUE;
        }

        RegistryKey<ItemGroup> groupKey = CATEGORY_ORDER.get(categoryIndex);
        Map<ItemKey, Integer> exactOrder = CATEGORY_EXACT_ORDER_CACHE.computeIfAbsent(
            groupKey,
            QuickSort::buildExactOrderMap
        );
        Map<String, Integer> itemOrder = CATEGORY_ITEM_ORDER_CACHE.computeIfAbsent(
            groupKey,
            QuickSort::buildItemOrderMap
        );

        ItemStack normalizedStack = normalizeForLookup(stack);
        Integer exactIndex = exactOrder.get(new ItemKey(normalizedStack));
        if (exactIndex != null) {
            return exactIndex;
        }

        return itemOrder.getOrDefault(getItemId(normalizedStack), Integer.MAX_VALUE);
    }

    private static Map<ItemKey, Integer> buildExactOrderMap(RegistryKey<ItemGroup> groupKey) {
        ItemGroup group = Registries.ITEM_GROUP.get(groupKey.getValue());
        Map<ItemKey, Integer> orderMap = new HashMap<>();
        if (group == null) {
            return orderMap;
        }

        int index = 0;
        for (ItemStack displayStack : group.getDisplayStacks()) {
            ItemStack normalizedStack = normalizeForLookup(displayStack);
            orderMap.putIfAbsent(new ItemKey(normalizedStack), index++);
        }
        return orderMap;
    }

    private static Map<String, Integer> buildItemOrderMap(RegistryKey<ItemGroup> groupKey) {
        ItemGroup group = Registries.ITEM_GROUP.get(groupKey.getValue());
        Map<String, Integer> orderMap = new HashMap<>();
        if (group == null) {
            return orderMap;
        }

        int index = 0;
        for (ItemStack displayStack : group.getDisplayStacks()) {
            ItemStack normalizedStack = normalizeForLookup(displayStack);
            orderMap.putIfAbsent(getItemId(normalizedStack), index++);
        }
        return orderMap;
    }

    private static ItemStack normalizeForLookup(ItemStack stack) {
        ItemStack normalized = stack.copy();
        normalized.setCount(1);
        return normalized;
    }

    private static String getItemId(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private static void ensureItemGroupDisplayContext(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return;
        }

        boolean changed = ItemGroups.updateDisplayContext(
            client.world.getEnabledFeatures(),
            client.player.isCreativeLevelTwoOp(),
            client.world.getRegistryManager()
        );
        if (changed) {
            CATEGORY_EXACT_ORDER_CACHE.clear();
            CATEGORY_ITEM_ORDER_CACHE.clear();
        }
    }

    private static boolean isShulkerBox(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        return blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static boolean isBundle(ItemStack stack) {
        return stack.contains(DataComponentTypes.BUNDLE_CONTENTS);
    }

    private static StorageContentsSortKey getShulkerContentsSortKey(ItemStack stack) {
        ContainerComponent container = stack.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        return buildStorageContentsSortKey(container.iterateNonEmpty());
    }

    private static StorageContentsSortKey getBundleContentsSortKey(ItemStack stack) {
        BundleContentsComponent bundleContents = stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
        return buildStorageContentsSortKey(bundleContents.iterate());
    }

    private static StorageContentsSortKey buildStorageContentsSortKey(Iterable<ItemStack> storedStacks) {
        Map<ItemKey, ItemStack> uniqueStacks = new HashMap<>();
        for (ItemStack storedStack : storedStacks) {
            if (storedStack.isEmpty()) {
                continue;
            }

            ItemStack normalizedStack = storedStack.copy();
            normalizedStack.setCount(1);
            uniqueStacks.putIfAbsent(new ItemKey(normalizedStack), normalizedStack);
        }

        List<ItemStack> contentStacks = new ArrayList<>(uniqueStacks.values());
        contentStacks.sort(QuickSort::compareStacks);
        String contentsKey = buildStorageContentsKey(contentStacks);

        // 收纳类物品区内部：空的最前，杂装居中，单一物品的按内部代表物品排序。
        if (contentStacks.isEmpty()) {
            return new StorageContentsSortKey(0, "", ItemStack.EMPTY);
        }
        if (contentStacks.size() > 1) {
            return new StorageContentsSortKey(1, contentsKey, ItemStack.EMPTY);
        }

        return new StorageContentsSortKey(2, contentsKey, contentStacks.get(0));
    }

    private static String buildStorageContentsKey(List<ItemStack> contentStacks) {
        List<String> itemKeys = new ArrayList<>(contentStacks.size());
        for (ItemStack contentStack : contentStacks) {
            itemKeys.add(getItemId(contentStack) + "#" + contentStack.getComponents().hashCode());
        }
        return String.join("|", itemKeys);
    }

    private static boolean isPriorityFrontStack(ItemStack stack) {
        if (!stack.hasEnchantments()) {
            return false;
        }

        String itemId = getItemId(stack);
        if ("minecraft:elytra".equals(itemId)) {
            return true;
        }

        boolean highTierGear = itemId.startsWith("minecraft:diamond_")
            || itemId.startsWith("minecraft:netherite_");
        if (!highTierGear) {
            return false;
        }

        return itemId.endsWith("_helmet")
            || itemId.endsWith("_chestplate")
            || itemId.endsWith("_leggings")
            || itemId.endsWith("_boots")
            || itemId.endsWith("_sword")
            || itemId.endsWith("_axe")
            || itemId.endsWith("_pickaxe")
            || itemId.endsWith("_shovel")
            || itemId.endsWith("_hoe");
    }

    private static boolean stacksEqualExactly(ItemStack current, ItemStack expected) {
        if (current.isEmpty() && expected.isEmpty()) {
            return true;
        }
        if (current.isEmpty() || expected.isEmpty()) {
            return false;
        }
        return current.getCount() == expected.getCount()
            && ItemStack.areItemsAndComponentsEqual(current, expected);
    }

    private static void clickSlot(ScreenHandler handler, int slotId, int button, SlotActionType actionType) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        try {
            if (client.currentScreen instanceof CreativeInventoryScreen creativeScreen
                && handler == creativeScreen.getScreenHandler()) {
                Slot slot = slotId >= 0 && slotId < handler.slots.size()
                    ? handler.getSlot(slotId)
                    : null;
                ((CreativeInventoryScreenInvoker) creativeScreen)
                    .quickcraft$invokeOnMouseClick(slot, slotId, button, actionType);
                return;
            }

            client.interactionManager.clickSlot(
                handler.syncId,
                slotId,
                button,
                actionType,
                client.player
            );
        } catch (Exception ignored) {
        }
    }

    private static Map<Integer, List<Slot>> groupSlotsByY(List<Slot> slots) {
        Map<Integer, List<Slot>> rows = new HashMap<>();
        for (Slot slot : slots) {
            rows.computeIfAbsent(slot.y, ignored -> new ArrayList<>()).add(slot);
        }
        return rows;
    }

    private static List<Slot> sortSlotsForLayout(List<Slot> slots) {
        return slots.stream()
            .sorted(Comparator
                .comparingInt((Slot slot) -> slot.y)
                .thenComparingInt(slot -> slot.x)
                .thenComparingInt(slot -> slot.id))
            .toList();
    }

    /**
     * 整理时锁住的玩家格子只参与区域判定，不参与实际移动。
     */
    private static List<Integer> toUnlockedPlayerSortSlotIds(ScreenHandler handler, List<Slot> slots) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return List.of();
        }

        return sortSlotsForLayout(slots).stream()
            .filter(slot -> !QuickContainerLock.isLockedSlot(handler, slot))
            .filter(slot -> slot.canTakeItems(client.player))
            .map(slot -> getClickSlotId(handler, slot))
            .toList();
    }

    /**
     * 容器整理同样跳过锁空格和锁半组，只把未锁槽位当成可整理目标。
     */
    private static List<Integer> toUnlockedContainerSortSlotIds(ScreenHandler handler, List<Slot> slots) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return List.of();
        }

        return sortSlotsForLayout(slots).stream()
            .filter(slot -> isContainerSortableSlot(handler, slot, client))
            .map(slot -> getClickSlotId(handler, slot))
            .toList();
    }

    private static int getClickSlotId(ScreenHandler handler, Slot slot) {
        int slotIndex = handler.slots.indexOf(slot);
        return slotIndex >= 0 ? slotIndex : slot.id;
    }

    private static boolean isVisibleSlot(Slot slot) {
        return slot.isEnabled() && slot.x >= 0 && slot.y >= 0;
    }

    private static int getMouseX(MinecraftClient client) {
        return (int) (client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth());
    }

    private static int getMouseY(MinecraftClient client) {
        return (int) (client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight());
    }

    private record Bounds(int minX, int minY, int maxX, int maxY) {
        private static Bounds fromSlots(List<Slot> slots, int guiLeft, int guiTop) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;

            for (Slot slot : slots) {
                minX = Math.min(minX, guiLeft + slot.x);
                minY = Math.min(minY, guiTop + slot.y);
                maxX = Math.max(maxX, guiLeft + slot.x + SLOT_SIZE);
                maxY = Math.max(maxY, guiTop + slot.y + SLOT_SIZE);
            }

            return new Bounds(
                minX - BOUNDS_PADDING,
                minY - BOUNDS_PADDING,
                maxX + BOUNDS_PADDING,
                maxY + BOUNDS_PADDING
            );
        }

        private boolean contains(int x, int y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }

        private int area() {
            return Math.max(1, maxX - minX) * Math.max(1, maxY - minY);
        }
    }

    private record SortTarget(String label, List<Integer> slotIds, Bounds bounds, ScreenHandler handler) {
    }

    private record ItemKey(String itemId, int componentsHash) {
        private ItemKey(ItemStack stack) {
            this(Registries.ITEM.getId(stack.getItem()).toString(), stack.getComponents().hashCode());
        }
    }

    private record StorageContentsSortKey(int kindRank, String contentsKey, ItemStack representativeStack) {
    }
}
