package com.yiyihehe.quickcraft;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.extension.ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickShulkerSplitSafetyTest {
    @Test
    void copySplitMovesExactTwoWithoutTargetRightClickTakeover() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 64, 0, true);
        int moved = invokeMoveItemsToTargetSlot(fixture, 0, 1, 2);
        assertThat(moved).isEqualTo(2);
        assertThat(fixture.handler.getSlot(1).getItem().getCount()).isEqualTo(2);
        assertThat(fixture.handler.getSlot(0).getItem().getCount()).isEqualTo(62);
        assertThat(fixture.handler.getCarried().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void scrollOneIntoExistingOneUsesSafeAmount() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 64, 1, true);
        boolean moved = invokeMoveOneSourceItemToTargetSlots(fixture, 0, List.of(1));
        assertThat(moved).isTrue();
        assertThat(fixture.handler.getSlot(1).getItem().getCount()).isEqualTo(2);
        assertThat(fixture.handler.getSlot(0).getItem().getCount()).isEqualTo(63);
        assertThat(fixture.handler.getCarried().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void ordinaryItemStillSplitsNormally() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.STONE, 64, 0, true);
        int moved = invokeMoveItemsToTargetSlot(fixture, 0, 1, 2);
        assertThat(moved).isEqualTo(2);
        assertThat(fixture.handler.getSlot(1).getItem().getCount()).isEqualTo(2);
        assertThat(fixture.handler.getSlot(0).getItem().getCount()).isEqualTo(62);
        assertThat(fixture.handler.getCarried().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void noSafeBufferDoesNotPickUpOrTouchTarget() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 64, 1, false);
        int moved = invokeMoveItemsToTargetSlot(fixture, 0, 1, 2);
        assertThat(moved).isZero();
        assertThat(fixture.handler.getSlot(0).getItem().getCount()).isEqualTo(64);
        assertThat(fixture.handler.getSlot(1).getItem().getCount()).isEqualTo(1);
        assertThat(fixture.handler.getCarried().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void trimReturnsExtraToPlayerWithoutTargetTakeover() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 4, 0, true);
        Inventory playerInventory = newPlayerInventory(fixture.player);
        fixture.handler.addPlayerSlot(playerInventory, 0);
        fixture.handler.getSlot(0).setByPlayer(new ItemStack(Items.ENDER_CHEST, 4));
        invokeTrim(fixture, 0, new ItemStack(Items.ENDER_CHEST, 2));
        assertThat(fixture.handler.getSlot(0).getItem().getCount()).isEqualTo(2);
        assertThat(playerInventory.getItem(0).getCount()).isEqualTo(2);
        assertThat(fixture.handler.getCarried().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void scrollSkipsFullAndIncompatibleSlotsBeforeUsingEmptyTarget() throws Exception {
        for (var blocked : List.of(new ItemStack(Items.ENDER_CHEST, 64), new ItemStack(Items.STONE))) {
            Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 64, 1, true);
            fixture.handler.getSlot(1).set(blocked);
            assertThat(invokeMoveOneSourceItemToTargetSlots(fixture, 0, List.of(1, 2))).isTrue();
            assertThat(fixture.handler.getSlot(0).getItem().getCount()).isEqualTo(63);
            assertThat(fixture.handler.getSlot(2).getItem().getCount()).isEqualTo(1);
            assertThat(fixture.handler.getCarried().isEmpty()).isTrue();
            assertThat(fixture.takeovers).hasValue(0);
        }
    }

    @Test
    void trimRestoresSplitBufferToOriginalContainerSlot() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 64, 0, true);
        Inventory inventory = newPlayerInventory(fixture.player);
        fixture.handler.addPlayerSlot(inventory, 0);
        invokeTrim(fixture, 0, new ItemStack(Items.ENDER_CHEST, 20));
        assertThat(fixture.handler.getSlot(0).getItem().getCount()).isEqualTo(20);
        assertThat(inventory.getItem(0).getCount()).isEqualTo(44);
        assertThat(fixture.handler.getSlot(1).hasItem()).isFalse();
        assertThat(fixture.handler.getCarried().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void trimDistributesExtraAcrossPlayerSlots() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 8, 0, true);
        Inventory inventory = newPlayerInventory(fixture.player);
        inventory.setItem(0, new ItemStack(Items.ENDER_CHEST, 60));
        inventory.setItem(1, new ItemStack(Items.ENDER_CHEST, 62));
        fixture.handler.addPlayerSlot(inventory, 0);
        fixture.handler.addPlayerSlot(inventory, 1);
        invokeTrim(fixture, 0, new ItemStack(Items.ENDER_CHEST, 2));
        assertThat(fixture.handler.getSlot(0).getItem().getCount()).isEqualTo(2);
        assertThat(inventory.getItem(0).getCount()).isEqualTo(64);
        assertThat(inventory.getItem(1).getCount()).isEqualTo(64);
        assertThat(fixture.handler.getCarried().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void lockedBufferDoesNotParticipateInSplit() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 64, 1, true);
        try (var locks = mockStatic(QuickContainerLock.class)) {
            locks.when(() -> QuickContainerLock.isLockedSlot(fixture.handler, fixture.handler.getSlot(2))).thenReturn(
                    true
            );
            assertThat(invokeMoveItemsToTargetSlot(fixture, 0, 1, 2)).isZero();
            assertThat(fixture.handler.getSlot(0).getItem().getCount()).isEqualTo(64);
            assertThat(fixture.handler.getSlot(1).getItem().getCount()).isEqualTo(1);
            assertThat(fixture.handler.getSlot(2).hasItem()).isFalse();
            assertThat(fixture.takeovers).hasValue(0);
        }
    }

    @Test
    void exactHalvingDoesNotRequireAnUnusedBuffer() throws Exception {
        for (int count : List.of(2, 64)) {
            Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, count, 0, false);
            assertThat(invokeMoveItemsToTargetSlot(fixture, 0, 1, count - 1)).isEqualTo(count - 1);
            assertThat(fixture.handler.getSlot(0).getItem().getCount()).isEqualTo(1);
            assertThat(fixture.handler.getCarried().isEmpty()).isTrue();
            assertThat(fixture.takeovers).hasValue(0);
        }
    }

    private static Fixture fixture(QuickShulkerKind kind, int sourceCount, int targetCount, boolean withBuffer) {
        Minecraft client = mock(Minecraft.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        LocalPlayer player = mock(LocalPlayer.class);
        Inventory playerInventory = newPlayerInventory(player);
        when(player.getInventory()).thenReturn(playerInventory);
        setField(client, "player", player);
        setField(client, "gameMode", gameMode);
        TestMenu handler = new TestMenu();
        SimpleContainer source = new SimpleContainer(1);
        SimpleContainer target = new SimpleContainer(1);
        source.setItem(0, new ItemStack(kind.item, sourceCount));
        if (targetCount > 0) {
            target.setItem(0, new ItemStack(kind.item, targetCount));
        }
        handler.addTestSlot(source, 0);
        handler.addTestSlot(target, 0);
        if (withBuffer) {
            handler.addTestSlot(new SimpleContainer(1), 0);
        }
        AtomicInteger takeovers = new AtomicInteger();
        installClickHandler(handler, gameMode, takeovers);
        return new Fixture(client, player, handler, takeovers);
    }

    private static int invokeMoveItemsToTargetSlot(Fixture fixture, int source, int target, int needed)
            throws Exception {
        Method method = QuickContainerCopy.class.getDeclaredMethod(
                "moveItemsToTargetSlot",
                AbstractContainerMenu.class,
                int.class,
                int.class,
                int.class,
                Minecraft.class
        );
        method.setAccessible(true);
        try (var minecraft = mockStatic(Minecraft.class); var fabric = mockStatic(FabricLoader.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client);
            FabricLoader loader = mock(FabricLoader.class);
            when(loader.isModLoaded("quickshulker")).thenReturn(true);
            fabric.when(FabricLoader::getInstance).thenReturn(loader);
            return (Integer) method.invoke(
                    new QuickContainerCopy(),
                    fixture.handler,
                    source,
                    target,
                    needed,
                    fixture.client
            );
        }
    }

    private static boolean invokeMoveOneSourceItemToTargetSlots(Fixture fixture, int source, List<Integer> targetIds)
            throws Exception {
        Method method = QuickTransfer.class.getDeclaredMethod(
                "moveOneSourceItemToTargetSlots",
                AbstractContainerScreen.class,
                int.class,
                ItemStack.class,
                List.class
        );
        method.setAccessible(true);
        AbstractContainerScreen<?> screen = mock(AbstractContainerScreen.class);
        when(screen.getMenu()).thenReturn(fixture.handler);
        try (var minecraft = mockStatic(Minecraft.class); var fabric = mockStatic(FabricLoader.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client);
            FabricLoader loader = mock(FabricLoader.class);
            when(loader.isModLoaded("quickshulker")).thenReturn(true);
            fabric.when(FabricLoader::getInstance).thenReturn(loader);
            return (Boolean) method.invoke(
                    null,
                    screen,
                    source,
                    fixture.handler.getSlot(source).getItem().copy(),
                    targetIds
            );
        }
    }

    private static void invokeTrim(Fixture fixture, int slotId, ItemStack template) throws Exception {
        Method method = QuickContainerCopy.class.getDeclaredMethod(
                "trimMatchingContainerSlot",
                AbstractContainerMenu.class,
                int.class,
                ItemStack.class,
                Minecraft.class
        );
        method.setAccessible(true);
        try (var minecraft = mockStatic(Minecraft.class); var fabric = mockStatic(FabricLoader.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client);
            FabricLoader loader = mock(FabricLoader.class);
            when(loader.isModLoaded("quickshulker")).thenReturn(true);
            fabric.when(FabricLoader::getInstance).thenReturn(loader);
            assertThat(
                    (Boolean) method.invoke(new QuickContainerCopy(), fixture.handler, slotId, template, fixture.client)
            ).isTrue();
        }
    }

    private static void installClickHandler(TestMenu handler, MultiPlayerGameMode gameMode, AtomicInteger takeovers) {
        doAnswer(invocation -> {
            int slotId = invocation.getArgument(1);
            int button = invocation.getArgument(2);
            Slot slot = handler.getSlot(slotId);
            ItemStack cursor = handler.getCarried();
            if (button == 0) {
                simulateLeftClick(handler, slot, cursor);
            } else if (button == 1) {
                simulateRightClick(handler, slot, cursor, takeovers);
            }
            return null;
        }).when(gameMode).handleContainerInput(
                anyInt(),
                anyInt(),
                anyInt(),
                any(ContainerInput.class),
                any(Player.class)
        );
    }

    private static void simulateLeftClick(TestMenu handler, Slot slot, ItemStack cursor) {
        if (cursor.isEmpty()) {
            handler.setCarried(slot.getItem().copy());
            slot.setByPlayer(ItemStack.EMPTY);
        } else if (!slot.hasItem()) {
            slot.setByPlayer(cursor.copy());
            handler.setCarried(ItemStack.EMPTY);
        } else if (ItemStack.isSameItemSameComponents(slot.getItem(), cursor)) {
            int max = Math.min(cursor.getMaxStackSize(), slot.getMaxStackSize(cursor));
            int moved = Math.min(cursor.getCount(), Math.max(0, max - slot.getItem().getCount()));
            slot.setByPlayer(slot.getItem().copyWithCount(slot.getItem().getCount() + moved));
            handler.setCarried(cursor.copyWithCount(cursor.getCount() - moved));
        } else {
            ItemStack old = slot.getItem().copy();
            slot.setByPlayer(cursor.copy());
            handler.setCarried(old);
        }
    }

    private static void simulateRightClick(TestMenu handler, Slot slot, ItemStack cursor, AtomicInteger takeovers) {
        if (cursor.isEmpty()) {
            ItemStack stack = slot.getItem();
            int taken = Math.max(1, (stack.getCount() + 1) / 2);
            handler.setCarried(stack.copyWithCount(taken));
            slot.setByPlayer(stack.copyWithCount(stack.getCount() - taken));
            return;
        }

        if (cursor.is(Items.ENDER_CHEST) && slot.hasItem() && slot.getItem().is(Items.ENDER_CHEST)) {
            // QuickShulker 的真实回调会把目标槽物品装进玩家末影箱，光标不变。
            takeovers.incrementAndGet();
            slot.setByPlayer(ItemStack.EMPTY);
            return;
        }

        if (!slot.hasItem()) {
            slot.setByPlayer(cursor.copyWithCount(1));
            handler.setCarried(cursor.copyWithCount(cursor.getCount() - 1));
        } else if (ItemStack.isSameItemSameComponents(slot.getItem(), cursor) && slot.getItem().getCount() < slot
                .getMaxStackSize(cursor)) {
            slot.setByPlayer(slot.getItem().copyWithCount(slot.getItem().getCount() + 1));
            handler.setCarried(cursor.copyWithCount(cursor.getCount() - 1));
        }
    }

    private static Inventory newPlayerInventory(Player player) {
        try {
            for (Constructor<?> constructor : Inventory.class.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 2 && parameters[0].isAssignableFrom(Player.class)) {
                    Object equipment = parameters[1].getConstructor().newInstance();
                    return (Inventory) constructor.newInstance(player, equipment);
                }
                if (parameters.length == 1 && parameters[0].isAssignableFrom(Player.class)) {
                    return (Inventory) constructor.newInstance(player);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("Player inventory constructor not found");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private enum QuickShulkerKind {
        ENDER_CHEST(Items.ENDER_CHEST), STONE(Items.STONE);

        private final net.minecraft.world.item.Item item;

        QuickShulkerKind(net.minecraft.world.item.Item item) {
            this.item = item;
        }
    }

    private record Fixture(Minecraft client, LocalPlayer player, TestMenu handler, AtomicInteger takeovers) {
    }

    private static final class TestMenu extends AbstractContainerMenu {
        private TestMenu() {
            super(null, 1);
        }

        private void addTestSlot(SimpleContainer inventory, int index) {
            addSlot(new Slot(inventory, index, 0, 0));
        }

        private void addPlayerSlot(Inventory inventory, int index) {
            addSlot(new Slot(inventory, index, 0, 0));
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
