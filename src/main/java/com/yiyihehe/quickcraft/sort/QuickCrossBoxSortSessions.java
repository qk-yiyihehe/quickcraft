package com.yiyihehe.quickcraft.sort;

import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.text.Text;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.item.ItemStack;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import java.util.ArrayDeque;
import java.util.Deque;

/** 统一管理一次排序及其拆分准备。排序会话负责点击恢复和空盒合并。 */
public final class QuickCrossBoxSortSessions {
    public static final QuickCrossBoxSortSessions INSTANCE = new QuickCrossBoxSortSessions();
    private QuickCrossBoxSortSession active;
    private QuickCrossBoxSortIntegratedSession remote;
    private Deque<int[]> splits;
    private int settleTicks;
    private boolean onePerBox;
    private java.util.List<Integer> containerSlots;
    private net.minecraft.screen.ScreenHandler splitMenu;
    private Screen splitScreen;
    private net.minecraft.client.network.ClientPlayerEntity owner;
    private net.minecraft.client.world.ClientWorld world;
    private InventoryScreen inventoryView;
    private boolean dispatchingSort;
    private boolean cancelRequested;

    private QuickCrossBoxSortSessions() {}

    public boolean isRunning() {
        return active != null || remote != null || splits != null;
    }

    public void start(
            MinecraftClient client,
            boolean onePerBox,
            java.util.List<Integer> menuSlots,
            boolean playerInventory
    ) {
        var player = client.player;
        if (isRunning() || player == null || !(client.currentScreen instanceof HandledScreen<?> screen) || screen
                .getScreenHandler() != player.currentScreenHandler) {
            return;
        }
        if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
            message(screen, "cursor_not_empty");
            return;
        }
        this.onePerBox = onePerBox;
        owner = player;
        world = client.world;
        inventoryView = screen instanceof InventoryScreen inventory ? inventory : null;
        if (client.getServer() != null) {
            remote = QuickCrossBoxSortIntegratedSession.create(client, screen, onePerBox, menuSlots);
            return;
        }
        if (!com.yiyihehe.quickcraft.compat.QuickShulkerConnection.canOpen()) {
            message(screen, "quickshulker_unavailable");
            return;
        }
        if (playerInventory) {
            containerSlots = null;
            if (QuickCrossBoxSortInventory.scan(player.getInventory()).boxes().isEmpty()) {
                message(screen, "nothing_to_sort");
                return;
            }
            if (player.currentScreenHandler != player.playerScreenHandler) {
                player.closeHandledScreen();
                client.setScreen(new InventoryScreen(player));
            }
            splits = new ArrayDeque<>();
            if (onePerBox) {
                for (int[] split : QuickCrossBoxSortSpares.planSplits()) {
                    splits.add(new int[] { inventoryMenuSlot(split[0]), inventoryMenuSlot(split[1]) });
                }
            }
        } else {
            long free = java.util.stream.IntStream.range(0, 36).filter(
                    slot -> !QuickCrossBoxSortProtection.isLocked(slot) && player.getInventory().getStack(slot)
                            .isEmpty()
            ).count();
            if (free < 2) {
                message(screen, "container_space");
                return;
            }
            containerSlots = java.util.List.copyOf(menuSlots);
            var menu = player.currentScreenHandler;
            var inventory = new net.minecraft.inventory.SimpleInventory(
                    menuSlots.stream().map(slot -> menu.getSlot(slot).getStack().copy()).toArray(ItemStack[]::new)
            );
            var boxes = QuickCrossBoxSortInventory.scan(inventory).boxes();
            if (boxes.isEmpty()) {
                message(screen, "nothing_to_sort");
                return;
            }
            int missing = Math.max(
                    0,
                    QuickCrossBoxSortSpares.needed(boxes) - boxes.size() - QuickCrossBoxSortSpares.singles(
                            inventory,
                            slot -> false
                    ).size()
            );
            splits = new ArrayDeque<>();
            if (onePerBox) {
                for (var split : QuickCrossBoxSortSpares.splits(inventory, slot -> false, missing, false)) {
                    splits.add(new int[] { menuSlots.get(split.sourceSlot()), menuSlots.get(split.targetSlot()) });
                }
            }
        }
        splitMenu = player.currentScreenHandler;
        splitScreen = client.currentScreen;
        if (splits.isEmpty()) {
            splits = null;
            begin(client);
        } else {
            settleTicks = 0;
        }
    }

    public boolean consumeScreen(Screen screen) {
        return active != null && active.consumeExpectedContainerScreen(screen);
    }

    public boolean blocksInventoryInput(Screen screen) {
        return inventoryView != null && screen == inventoryView && isRunning();
    }

    public boolean blocksInventoryClicks() {
        MinecraftClient client = MinecraftClient.getInstance();
        return !dispatchingSort && blocksInventoryInput(client.currentScreen);
    }

    public void requestCancel() {
        cancelRequested = active != null || splits != null;
    }

    public void clear() {
        active = null;
        remote = null;
        splits = null;
        settleTicks = 0;
        owner = null;
        world = null;
        containerSlots = null;
        splitMenu = null;
        splitScreen = null;
        inventoryView = null;
        cancelRequested = false;
        QuickCrossBoxSortSync.clear();
    }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null || isRunning() && (client.player != owner
                || client.world != world)) {
            clear();
            return;
        }
        dispatchingSort = true;
        try {
            if (active != null) {
                if (cancelRequested || !QuickCraftConfigs.isCrossBoxSortEnabled()) {
                    active.cancel(client);
                }
                if (!active.tick(client)) {
                    active = null;
                }
            } else if (remote != null) {
                if (!remote.tick(client)) {
                    remote = null;
                }
            } else if (splits != null) {
                if (cancelRequested) {
                    splits = null;
                    message(splitScreen, "safe_abort");
                } else {
                    tickSplits(client);
                }
            }
        } finally {
            dispatchingSort = false;
            if (!isRunning()) {
                cancelRequested = false;
                inventoryView = null;
                splitScreen = null;
            }
        }
    }

    private void tickSplits(MinecraftClient client) {
        var player = client.player;
        var menu = splitMenu;
        if (!QuickCraftConfigs.isCrossBoxSortEnabled()
                || !(client.currentScreen instanceof HandledScreen<?> screen)
                || screen.getScreenHandler() != menu
                || player.currentScreenHandler != menu
                || !menu.getCursorStack().isEmpty()
                || client.interactionManager == null) {
            splits = null;
            message(splitScreen, "safe_abort");
            return;
        }
        if (settleTicks > 0) {
            settleTicks--;
            return;
        }
        if (splits.isEmpty()) {
            splits = null;
            begin(client);
            return;
        }
        int[] split = splits.removeFirst();
        ItemStack source = menu.getSlot(split[0]).getStack();
        ItemStack target = menu.getSlot(split[1]).getStack();
        if (com.yiyihehe.quickcraft.QuickContainerLock.isLockedSlot(menu, split[0])
                || com.yiyihehe.quickcraft.QuickContainerLock.isLockedSlot(menu, split[1])
                || !target.isEmpty()
                || !source.contains(DataComponentTypes.CONTAINER)
                || !QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(source)) {
            splits = null;
            message(splitScreen, "inventory_changed");
            return;
        }
        client.interactionManager.clickSlot(menu.syncId, split[0], 0, SlotActionType.PICKUP, player);
        client.interactionManager.clickSlot(menu.syncId, split[1], 1, SlotActionType.PICKUP, player);
        client.interactionManager.clickSlot(menu.syncId, split[0], 0, SlotActionType.PICKUP, player);
        settleTicks = 4;
    }

    private void begin(MinecraftClient client) {
        active = QuickCrossBoxSortSession.create(client, onePerBox, containerSlots);
        if (active != null) {
            active.enterBackgroundMode(client, inventoryView);
        }
    }

    public static int inventoryMenuSlot(int slot) {
        return slot < 9 ? slot + 36 : slot;
    }

    private static void message(Screen screen, String key) {
        QuickCrossBoxSort.message(screen, Text.translatable("quickcraft.message.cross_box_sort." + key));
    }
}
