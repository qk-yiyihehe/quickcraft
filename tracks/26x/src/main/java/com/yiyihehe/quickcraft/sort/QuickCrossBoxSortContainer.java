package com.yiyihehe.quickcraft.sort;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.world.level.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import java.util.List;

/** Quick Shulker 只读取玩家库存；容器盒子逐个借入背包，关闭后放回同一来源槽。 */
final class QuickCrossBoxSortContainer {
    private final AbstractContainerMenu originalMenu;
    private final List<Integer> slots;
    private final int temporarySlot;
    private final HitResult target;
    private final Block block;
    private AbstractContainerScreen<?> screen;
    private AbstractContainerMenu menu;
    private int borrowed = -1;
    private int phase;
    private int deadline;
    private int clickedAt;
    private long openRevision;
    private QuickCrossBoxSortStackKey boxKey;

    private QuickCrossBoxSortContainer(Minecraft client, AbstractContainerScreen<?> screen, List<Integer> slots, int temporarySlot) {
        this.screen = screen;
        menu = originalMenu = screen.getMenu();
        this.slots = List.copyOf(slots);
        this.temporarySlot = temporarySlot;
        target = client.hitResult;
        block = target instanceof BlockHitResult hit ? client.level.getBlockState(hit.getBlockPos()).getBlock() : null;
    }

    static QuickCrossBoxSortContainer create(Minecraft client, AbstractContainerScreen<?> screen, List<Integer> slots, int temporarySlot) {
        if (!(client.hitResult instanceof BlockHitResult) && !(client.hitResult instanceof EntityHitResult)) { return null; }
        return new QuickCrossBoxSortContainer(client, screen, slots, temporarySlot);
    }

    boolean isBase(AbstractContainerMenu handler) { return handler == menu; }
    boolean hasBorrowedBox() { return borrowed >= 0; }
    int temporarySlot() { return temporarySlot; }
    int slot(int index) { return slots.get(index); }
    ItemStack stack(int index) { return menu.getSlot(slot(index)).getItem(); }
    SimpleContainer snapshot() {
        return new SimpleContainer(slots.stream().map(id -> menu.getSlot(id).getItem().copy()).toArray(ItemStack[]::new));
    }

    boolean consume(Screen candidate) {
        if (phase != 3 || !(candidate instanceof AbstractContainerScreen<?> opened)) { return false; }
        var handler = opened.getMenu();
        if (handler.getType() != originalMenu.getType() || handler.slots.size() != originalMenu.slots.size()) { return false; }
        menu = handler;
        screen = opened;
        QuickContainerLock.bindSameContainerScreen(handler, originalMenu);
        return true;
    }

    // -1 失败，0 等待，1 可继续；等待只由外层每 tick 推进，不进行阻塞或重发。
    int prepareOpen(Minecraft client, int index, int age) {
        var player = client.player;
        if (borrowed < 0) {
            if (!isBase(player.containerMenu) || !menu.getCarried().isEmpty()
                    || !player.getInventory().getItem(temporarySlot).isEmpty()) { return -1; }
            ItemStack box = stack(index);
            if (box.getCount() != 1 || !QuickCrossBoxSortInventory.isVanillaShulkerBox(box)
                    || QuickCrossBoxSortInventory.isNamedOrSpecial(box)) { return -1; }
            int destination = menu.findSlot(client.player.getInventory(), temporarySlot).orElse(-1);
            if (!movable(client, slot(index), destination, box)) { return -1; }
            borrowed = index;
            boxKey = new QuickCrossBoxSortStackKey(box);
            click(client, slot(index));
            click(client, destination);
            phase = 1;
            clickedAt = age;
            deadline = age + 100;
        }
        if (age >= deadline) { return -1; }
        if (phase == 1 && age >= clickedAt + 4 && stack(borrowed).isEmpty()
                && boxKey.matches(player.getInventory().getItem(temporarySlot)) && menu.getCarried().isEmpty()) {
            player.closeContainer();
            phase = 2;
        }
        return phase == 2 && player.containerMenu == player.inventoryMenu ? 1 : 0;
    }

    int finishClose(Minecraft client, int age) {
        var player = client.player;
        if (borrowed < 0) { return isBase(player.containerMenu) ? 1 : -1; }
        if (!targetExists(client)) { return -1; }
        if (phase == 1 && age >= clickedAt + 4 && stack(borrowed).isEmpty()
                && boxKey.matches(player.getInventory().getItem(temporarySlot)) && menu.getCarried().isEmpty()) { phase = 2; }
        if (phase == 2) {
            if (isBase(player.containerMenu)) { phase = 4; }
            else if (player.containerMenu == player.inventoryMenu) {
                openRevision = QuickCrossBoxSortSync.revision();
                phase = 3;
                deadline = age + 100;
                if (target instanceof BlockHitResult hit) { client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit); }
                else if (target instanceof EntityHitResult hit) { client.gameMode.interact(player, hit.getEntity(), hit, InteractionHand.MAIN_HAND); }
            }
        }
        if (phase == 3 && isBase(player.containerMenu) && QuickCrossBoxSortSync.contentAdvancedFor(openRevision, menu.containerId)) { phase = 4; }
        if (phase == 4) {
            ItemStack box = player.getInventory().getItem(temporarySlot);
            int source = menu.findSlot(client.player.getInventory(), temporarySlot).orElse(-1);
            if (!stack(borrowed).isEmpty() || box.getCount() != 1 || !QuickCrossBoxSortInventory.isVanillaShulkerBox(box)
                    || !menu.getCarried().isEmpty() || !movable(client, source, slot(borrowed), box)) { return -1; }
            boxKey = new QuickCrossBoxSortStackKey(box);
            click(client, source);
            click(client, slot(borrowed));
            phase = 5;
            clickedAt = age;
            deadline = age + 100;
        }
        if (phase == 5 && age >= clickedAt + 4 && isBase(player.containerMenu)
                && player.getInventory().getItem(temporarySlot).isEmpty() && boxKey.matches(stack(borrowed)) && menu.getCarried().isEmpty()) {
            borrowed = -1;
            phase = 0;
            return 1;
        }
        return age >= deadline ? -1 : 0;
    }

    void reveal(Minecraft client) {
        if (isBase(client.player.containerMenu)) { com.yiyihehe.quickcraft.QuickClientScreenAccess.setScreen(client, screen); }
    }

    private boolean targetExists(Minecraft client) {
        return target instanceof BlockHitResult hit ? client.level.getBlockState(hit.getBlockPos()).getBlock() == block
                : target instanceof EntityHitResult entityHit && !entityHit.getEntity().isRemoved();
    }

    private boolean movable(Minecraft client, int source, int destination, ItemStack stack) {
        return source >= 0 && destination >= 0 && !QuickCrossBoxSortProtection.isLocked(temporarySlot)
                && !QuickContainerLock.isLockedSlot(menu, source) && !QuickContainerLock.isLockedSlot(menu, destination)
                && menu.getSlot(source).mayPickup(client.player) && menu.getSlot(destination).mayPlace(stack);
    }

    private void click(Minecraft client, int slot) {
        client.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, client.player);
    }
}
