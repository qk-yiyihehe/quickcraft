package com.yiyihehe.quickcraft.sort;

import com.yiyihehe.quickcraft.QuickCrossBoxSort;
import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import java.util.ArrayDeque;
import java.util.Deque;

/** Owns one sort and its split preparation; the session owns click recovery and empty-box merging. */
public final class QuickCrossBoxSortSessions {
    public static final QuickCrossBoxSortSessions INSTANCE = new QuickCrossBoxSortSessions();
    private QuickCrossBoxSortSession active;
    private QuickCrossBoxSortIntegratedSession remote;
    private Deque<int[]> splits;
    private int settleTicks;
    private boolean onePerBox;
    private java.util.List<Integer> containerSlots;
    private net.minecraft.world.inventory.AbstractContainerMenu splitMenu;
    private net.minecraft.client.player.LocalPlayer owner;
    private net.minecraft.client.multiplayer.ClientLevel world;

    private QuickCrossBoxSortSessions() {}
    public boolean isRunning() { return active != null || remote != null || splits != null; }

    public void start(Minecraft client, boolean onePerBox, java.util.List<Integer> menuSlots, boolean playerInventory) {
        var player = client.player;
        if (isRunning() || player == null || !(QuickClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu() != player.containerMenu) { return; }
        if (!player.containerMenu.getCarried().isEmpty()) { message(client, "cursor_not_empty"); return; }
        this.onePerBox = onePerBox;
        owner = player;
        world = client.level;
        if (client.getSingleplayerServer() != null) {
            remote = QuickCrossBoxSortIntegratedSession.create(client, onePerBox, menuSlots);
            if (remote != null && player.containerMenu == player.inventoryMenu) { QuickClientScreenAccess.setScreen(client, null); }
            return;
        }
        if (!com.yiyihehe.quickcraft.compat.QuickShulkerConnection.canOpen()) { message(client, "quickshulker_unavailable"); return; }
        if (playerInventory) {
            containerSlots = null;
            if (QuickCrossBoxSortInventory.scan(player.getInventory()).boxes().isEmpty()) { message(client, "nothing_to_sort"); return; }
            if (player.containerMenu != player.inventoryMenu) {
                player.closeContainer();
                QuickClientScreenAccess.setScreen(client, new InventoryScreen(player));
            }
            splits = new ArrayDeque<>();
            if (onePerBox) {
                for (int[] split : QuickCrossBoxSortSpares.planSplits()) {
                    splits.add(new int[]{inventoryMenuSlot(split[0]), inventoryMenuSlot(split[1])});
                }
            }
        } else {
            long free = java.util.stream.IntStream.range(0, 36).filter(slot -> !QuickCrossBoxSortProtection.isLocked(slot)
                    && player.getInventory().getItem(slot).isEmpty()).count();
            if (free < 2) { message(client, "container_space"); return; }
            containerSlots = java.util.List.copyOf(menuSlots);
            var menu = player.containerMenu;
            var inventory = new net.minecraft.world.SimpleContainer(menuSlots.stream()
                    .map(slot -> menu.getSlot(slot).getItem().copy()).toArray(ItemStack[]::new));
            var boxes = QuickCrossBoxSortInventory.scan(inventory).boxes();
            if (boxes.isEmpty()) { message(client, "nothing_to_sort"); return; }
            int missing = Math.max(0, QuickCrossBoxSortSpares.needed(boxes) - boxes.size()
                    - QuickCrossBoxSortSpares.singles(inventory, slot -> false).size());
            splits = new ArrayDeque<>();
            if (onePerBox) {
                for (var split : QuickCrossBoxSortSpares.splits(inventory, slot -> false, missing, false)) {
                    splits.add(new int[]{menuSlots.get(split.sourceSlot()), menuSlots.get(split.targetSlot())});
                }
            }
        }
        splitMenu = player.containerMenu;
        if (splits.isEmpty()) { splits = null; begin(client); } else { settleTicks = 0; }
    }

    public boolean consumeScreen(Screen screen) {
        return active != null && active.consumeExpectedContainerScreen(screen);
    }

    public void clear() {
        active = null; remote = null; splits = null; settleTicks = 0; owner = null; world = null;
        containerSlots = null; splitMenu = null;
        QuickCrossBoxSortSync.clear();
    }

    public void tick(Minecraft client) {
        if (client.player == null || client.getConnection() == null || isRunning() && (client.player != owner || client.level != world)) { clear(); return; }
        if (active != null) {
            if (!QuickCraftConfigs.isCrossBoxSortEnabled()) { active.cancel(client); }
            if (!active.tick(client)) { active = null; }
        } else if (remote != null) {
            if (!remote.tick(client)) { remote = null; }
        } else if (splits != null) {
            tickSplits(client);
        }
    }

    private void tickSplits(Minecraft client) {
        var player = client.player;
        var menu = splitMenu;
        if (!QuickCraftConfigs.isCrossBoxSortEnabled() || !(QuickClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu() != menu || player.containerMenu != menu || !menu.getCarried().isEmpty()
                || client.gameMode == null) {
            splits = null; message(client, "safe_abort"); return;
        }
        if (settleTicks > 0) { settleTicks--; return; }
        if (splits.isEmpty()) { splits = null; begin(client); return; }
        int[] split = splits.removeFirst();
        ItemStack source = menu.getSlot(split[0]).getItem();
        ItemStack target = menu.getSlot(split[1]).getItem();
        if (com.yiyihehe.quickcraft.QuickContainerLock.isLockedSlot(menu, split[0])
                || com.yiyihehe.quickcraft.QuickContainerLock.isLockedSlot(menu, split[1]) || !target.isEmpty()
                || !source.has(DataComponents.CONTAINER) || !QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(source)) {
            splits = null; message(client, "inventory_changed"); return;
        }
        client.gameMode.handleContainerInput(menu.containerId, split[0], 0, ContainerInput.PICKUP, player);
        client.gameMode.handleContainerInput(menu.containerId, split[1], 1, ContainerInput.PICKUP, player);
        client.gameMode.handleContainerInput(menu.containerId, split[0], 0, ContainerInput.PICKUP, player);
        settleTicks = 4;
    }

    private void begin(Minecraft client) {
        active = QuickCrossBoxSortSession.create(client, onePerBox, containerSlots);
        if (active != null) { active.enterBackgroundMode(client); }
    }

    public static int inventoryMenuSlot(int slot) { return slot < 9 ? slot + 36 : slot; }

    private static void message(Minecraft client, String key) {
        QuickCrossBoxSort.message(Component.translatable("quickcraft.message.cross_box_sort." + key));
    }
}
