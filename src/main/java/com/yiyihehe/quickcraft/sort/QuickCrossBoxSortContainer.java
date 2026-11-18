package com.yiyihehe.quickcraft.sort;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import java.util.List;

/** Quick Shulker 只读取玩家库存；容器盒子逐个借入背包，关闭后放回同一来源槽。 */
final class QuickCrossBoxSortContainer {
    private final ScreenHandler originalMenu;
    private final List<Integer> slots;
    private final int temporarySlot;
    private final HitResult target;
    private final Block block;
    private HandledScreen<?> screen;
    private ScreenHandler menu;
    private int borrowed = -1;
    private int phase;
    private int deadline;
    private int clickedAt;
    private long openRevision;
    private QuickCrossBoxSortStackKey boxKey;

    private QuickCrossBoxSortContainer(MinecraftClient client, HandledScreen<?> screen, List<Integer> slots, int temporarySlot) {
        this.screen = screen;
        menu = originalMenu = screen.getScreenHandler();
        this.slots = List.copyOf(slots);
        this.temporarySlot = temporarySlot;
        target = client.crosshairTarget instanceof BlockHitResult hit ? hit.withBlockPos(hit.getBlockPos().toImmutable()) : client.crosshairTarget;
        block = target instanceof BlockHitResult hit ? client.world.getBlockState(hit.getBlockPos()).getBlock() : null;
    }

    static QuickCrossBoxSortContainer create(MinecraftClient client, HandledScreen<?> screen, List<Integer> slots, int temporarySlot) {
        if (!(client.crosshairTarget instanceof BlockHitResult) && !(client.crosshairTarget instanceof EntityHitResult)) { return null; }
        return new QuickCrossBoxSortContainer(client, screen, slots, temporarySlot);
    }

    boolean isBase(ScreenHandler handler) { return handler == menu; }
    boolean hasBorrowedBox() { return borrowed >= 0; }
    int temporarySlot() { return temporarySlot; }
    int slot(int index) { return slots.get(index); }
    ItemStack stack(int index) { return menu.getSlot(slot(index)).getStack(); }
    SimpleInventory snapshot() {
        return new SimpleInventory(slots.stream().map(id -> menu.getSlot(id).getStack().copy()).toArray(ItemStack[]::new));
    }

    boolean consume(Screen candidate) {
        if (phase != 3 || !(candidate instanceof HandledScreen<?> opened)) { return false; }
        var handler = opened.getScreenHandler();
        if (handler.getType() != originalMenu.getType() || handler.slots.size() != originalMenu.slots.size()) { return false; }
        menu = handler;
        screen = opened;
        QuickContainerLock.bindSameContainerScreen(handler, originalMenu);
        return true;
    }

    // -1 失败，0 等待，1 可继续；等待只由外层每 tick 推进，不进行阻塞或重发。
    int prepareOpen(MinecraftClient client, int index, int age) {
        var player = client.player;
        if (borrowed < 0) {
            if (!isBase(player.currentScreenHandler) || !menu.getCursorStack().isEmpty()
                    || !player.getInventory().getStack(temporarySlot).isEmpty()) { return -1; }
            ItemStack box = stack(index);
            if (box.getCount() != 1 || !QuickCrossBoxSortInventory.isVanillaShulkerBox(box)
                    || QuickCrossBoxSortInventory.isNamedOrSpecial(box)) { return -1; }
            int destination = QuickCrossBoxSortProtection.menuSlot(menu, temporarySlot);
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
                && boxKey.matches(player.getInventory().getStack(temporarySlot)) && menu.getCursorStack().isEmpty()) {
            player.closeHandledScreen();
            phase = 2;
        }
        return phase == 2 && player.currentScreenHandler == player.playerScreenHandler ? 1 : 0;
    }

    int finishClose(MinecraftClient client, int age) {
        var player = client.player;
        if (borrowed < 0) { return isBase(player.currentScreenHandler) ? 1 : -1; }
        if (!targetExists(client)) { return -1; }
        if (phase == 1 && age >= clickedAt + 4 && stack(borrowed).isEmpty()
                && boxKey.matches(player.getInventory().getStack(temporarySlot)) && menu.getCursorStack().isEmpty()) { phase = 2; }
        if (phase == 2) {
            if (isBase(player.currentScreenHandler)) { phase = 4; }
            else if (player.currentScreenHandler == player.playerScreenHandler) {
                openRevision = QuickCrossBoxSortSync.revision();
                phase = 3;
                deadline = age + 100;
                if (target instanceof BlockHitResult hit) { client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit); }
                else if (target instanceof EntityHitResult hit) { client.interactionManager.interactEntity(player, hit.getEntity(), Hand.MAIN_HAND); }
            }
        }
        if (phase == 3 && isBase(player.currentScreenHandler) && QuickCrossBoxSortSync.contentAdvancedFor(openRevision, menu.syncId)) { phase = 4; }
        if (phase == 4) {
            ItemStack box = player.getInventory().getStack(temporarySlot);
            int source = QuickCrossBoxSortProtection.menuSlot(menu, temporarySlot);
            if (!stack(borrowed).isEmpty() || box.getCount() != 1 || !QuickCrossBoxSortInventory.isVanillaShulkerBox(box)
                    || !menu.getCursorStack().isEmpty() || !movable(client, source, slot(borrowed), box)) { return -1; }
            boxKey = new QuickCrossBoxSortStackKey(box);
            click(client, source);
            click(client, slot(borrowed));
            phase = 5;
            clickedAt = age;
            deadline = age + 100;
        }
        if (phase == 5 && age >= clickedAt + 4 && isBase(player.currentScreenHandler)
                && player.getInventory().getStack(temporarySlot).isEmpty() && boxKey.matches(stack(borrowed)) && menu.getCursorStack().isEmpty()) {
            borrowed = -1;
            phase = 0;
            return 1;
        }
        return age >= deadline ? -1 : 0;
    }

    void reveal(MinecraftClient client) {
        if (isBase(client.player.currentScreenHandler)) { client.setScreen(screen); }
    }

    private boolean targetExists(MinecraftClient client) {
        return target instanceof BlockHitResult hit ? client.world.getBlockState(hit.getBlockPos()).getBlock() == block
                : target instanceof EntityHitResult entityHit && !entityHit.getEntity().isRemoved();
    }

    private boolean movable(MinecraftClient client, int source, int destination, ItemStack stack) {
        return source >= 0 && destination >= 0 && !QuickCrossBoxSortProtection.isLocked(temporarySlot)
                && !QuickContainerLock.isLockedSlot(menu, source) && !QuickContainerLock.isLockedSlot(menu, destination)
                && menu.getSlot(source).canTakeItems(client.player) && menu.getSlot(destination).canInsert(stack);
    }

    private void click(MinecraftClient client, int slot) {
        client.interactionManager.clickSlot(menu.syncId, slot, 0, SlotActionType.PICKUP, client.player);
    }
}
