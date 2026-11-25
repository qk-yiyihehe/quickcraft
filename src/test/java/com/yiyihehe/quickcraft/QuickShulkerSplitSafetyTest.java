package com.yiyihehe.quickcraft;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.junit.jupiter.api.Test;

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
        assertThat(fixture.handler.getSlot(1).getStack().getCount()).isEqualTo(2);
        assertThat(fixture.handler.getSlot(0).getStack().getCount()).isEqualTo(62);
        assertThat(fixture.handler.getCursorStack().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void scrollOneIntoExistingOneUsesSafeAmount() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 64, 1, true);
        boolean moved = invokeMoveOneSourceItemToTargetSlots(fixture, 0, List.of(1));
        assertThat(moved).isTrue();
        assertThat(fixture.handler.getSlot(1).getStack().getCount()).isEqualTo(2);
        assertThat(fixture.handler.getSlot(0).getStack().getCount()).isEqualTo(63);
        assertThat(fixture.handler.getCursorStack().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void ordinaryItemStillSplitsNormally() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.STONE, 64, 0, true);
        int moved = invokeMoveItemsToTargetSlot(fixture, 0, 1, 2);
        assertThat(moved).isEqualTo(2);
        assertThat(fixture.handler.getSlot(1).getStack().getCount()).isEqualTo(2);
        assertThat(fixture.handler.getSlot(0).getStack().getCount()).isEqualTo(62);
        assertThat(fixture.handler.getCursorStack().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void noSafeBufferDoesNotPickUpOrTouchTarget() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 64, 1, false);
        int moved = invokeMoveItemsToTargetSlot(fixture, 0, 1, 2);
        assertThat(moved).isZero();
        assertThat(fixture.handler.getSlot(0).getStack().getCount()).isEqualTo(64);
        assertThat(fixture.handler.getSlot(1).getStack().getCount()).isEqualTo(1);
        assertThat(fixture.handler.getCursorStack().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void trimReturnsExtraToPlayerWithoutTargetTakeover() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 4, 0, true);
        fixture.handler.getSlot(0).setStack(new ItemStack(Items.ENDER_CHEST, 4));
        PlayerInventory playerInventory = newPlayerInventory(fixture.player);
        fixture.handler.addPlayerSlot(playerInventory, 0);
        invokeTrim(fixture, 0, new ItemStack(Items.ENDER_CHEST, 2));
        assertThat(fixture.handler.getSlot(0).getStack().getCount()).isEqualTo(2);
        assertThat(playerInventory.getStack(0).getCount()).isEqualTo(2);
        assertThat(fixture.handler.getCursorStack().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void scrollSkipsFullAndIncompatibleSlotsBeforeUsingEmptyTarget() throws Exception {
        for (var blocked : List.of(new ItemStack(Items.ENDER_CHEST, 64), new ItemStack(Items.STONE))) {
            Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 64, 1, true);
            fixture.handler.getSlot(1).setStack(blocked);
            assertThat(invokeMoveOneSourceItemToTargetSlots(fixture, 0, List.of(1, 2))).isTrue();
            assertThat(fixture.handler.getSlot(0).getStack().getCount()).isEqualTo(63);
            assertThat(fixture.handler.getSlot(2).getStack().getCount()).isEqualTo(1);
            assertThat(fixture.handler.getCursorStack().isEmpty()).isTrue();
            assertThat(fixture.takeovers).hasValue(0);
        }
    }

    @Test
    void trimRestoresSplitBufferToOriginalContainerSlot() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 64, 0, true);
        PlayerInventory inventory = newPlayerInventory(fixture.player);
        fixture.handler.addPlayerSlot(inventory, 0);
        invokeTrim(fixture, 0, new ItemStack(Items.ENDER_CHEST, 20));
        assertThat(fixture.handler.getSlot(0).getStack().getCount()).isEqualTo(20);
        assertThat(inventory.getStack(0).getCount()).isEqualTo(44);
        assertThat(fixture.handler.getSlot(1).hasStack()).isFalse();
        assertThat(fixture.handler.getCursorStack().isEmpty()).isTrue();
        assertThat(fixture.takeovers).hasValue(0);
    }

    @Test
    void trimDistributesExtraAcrossPlayerSlots() throws Exception {
        Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, 8, 0, true);
        PlayerInventory inventory = newPlayerInventory(fixture.player);
        inventory.setStack(0, new ItemStack(Items.ENDER_CHEST, 60));
        inventory.setStack(1, new ItemStack(Items.ENDER_CHEST, 62));
        fixture.handler.addPlayerSlot(inventory, 0);
        fixture.handler.addPlayerSlot(inventory, 1);
        invokeTrim(fixture, 0, new ItemStack(Items.ENDER_CHEST, 2));
        assertThat(fixture.handler.getSlot(0).getStack().getCount()).isEqualTo(2);
        assertThat(inventory.getStack(0).getCount()).isEqualTo(64);
        assertThat(inventory.getStack(1).getCount()).isEqualTo(64);
        assertThat(fixture.handler.getCursorStack().isEmpty()).isTrue();
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
            assertThat(fixture.handler.getSlot(0).getStack().getCount()).isEqualTo(64);
            assertThat(fixture.handler.getSlot(1).getStack().getCount()).isEqualTo(1);
            assertThat(fixture.handler.getSlot(2).hasStack()).isFalse();
            assertThat(fixture.takeovers).hasValue(0);
        }
    }

    @Test
    void exactHalvingDoesNotRequireAnUnusedBuffer() throws Exception {
        for (int count : List.of(2, 64)) {
            Fixture fixture = fixture(QuickShulkerKind.ENDER_CHEST, count, 0, false);
            assertThat(invokeMoveItemsToTargetSlot(fixture, 0, 1, count - 1)).isEqualTo(count - 1);
            assertThat(fixture.handler.getSlot(0).getStack().getCount()).isEqualTo(1);
            assertThat(fixture.handler.getCursorStack().isEmpty()).isTrue();
            assertThat(fixture.takeovers).hasValue(0);
        }
    }

    private static Fixture fixture(QuickShulkerKind kind, int sourceCount, int targetCount, boolean withBuffer) {
        MinecraftClient client = mock(MinecraftClient.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        PlayerInventory playerInventory = newPlayerInventory(player);
        when(player.getInventory()).thenReturn(playerInventory);
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);
        TestScreenHandler handler = new TestScreenHandler();
        SimpleInventory source = new SimpleInventory(1);
        SimpleInventory target = new SimpleInventory(1);
        source.setStack(0, new ItemStack(kind.item, sourceCount));
        if (targetCount > 0) {
            target.setStack(0, new ItemStack(kind.item, targetCount));
        }
        handler.addTestSlot(source, 0);
        handler.addTestSlot(target, 0);
        if (withBuffer) {
            handler.addTestSlot(new SimpleInventory(1), 0);
        }
        AtomicInteger takeovers = new AtomicInteger();
        installClickHandler(handler, interactionManager, takeovers);
        return new Fixture(client, player, handler, takeovers);
    }

    private static int invokeMoveItemsToTargetSlot(Fixture fixture, int source, int target, int needed)
            throws Exception {
        Method method = QuickContainerCopy.class.getDeclaredMethod(
                "moveItemsToTargetSlot",
                ScreenHandler.class,
                int.class,
                int.class,
                int.class,
                MinecraftClient.class
        );
        method.setAccessible(true);
        try (var minecraft = mockStatic(MinecraftClient.class); var fabric = mockStatic(FabricLoader.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(fixture.client);
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
                HandledScreen.class,
                int.class,
                ItemStack.class,
                List.class
        );
        method.setAccessible(true);
        HandledScreen<?> screen = mock(HandledScreen.class);
        when(screen.getScreenHandler()).thenReturn(fixture.handler);
        try (var minecraft = mockStatic(MinecraftClient.class); var fabric = mockStatic(FabricLoader.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(fixture.client);
            FabricLoader loader = mock(FabricLoader.class);
            when(loader.isModLoaded("quickshulker")).thenReturn(true);
            fabric.when(FabricLoader::getInstance).thenReturn(loader);
            return (Boolean) method.invoke(
                    null,
                    screen,
                    source,
                    fixture.handler.getSlot(source).getStack().copy(),
                    targetIds
            );
        }
    }

    private static void invokeTrim(Fixture fixture, int slotId, ItemStack template) throws Exception {
        Method method = QuickContainerCopy.class.getDeclaredMethod(
                "trimMatchingContainerSlot",
                ScreenHandler.class,
                int.class,
                ItemStack.class,
                MinecraftClient.class
        );
        method.setAccessible(true);
        try (var minecraft = mockStatic(MinecraftClient.class); var fabric = mockStatic(FabricLoader.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(fixture.client);
            FabricLoader loader = mock(FabricLoader.class);
            when(loader.isModLoaded("quickshulker")).thenReturn(true);
            fabric.when(FabricLoader::getInstance).thenReturn(loader);
            assertThat(
                    (Boolean) method.invoke(new QuickContainerCopy(), fixture.handler, slotId, template, fixture.client)
            ).isTrue();
        }
    }

    private static void installClickHandler(
            TestScreenHandler handler,
            ClientPlayerInteractionManager interactionManager,
            AtomicInteger takeovers
    ) {
        doAnswer(invocation -> {
            int slotId = invocation.getArgument(1);
            int button = invocation.getArgument(2);
            Slot slot = handler.getSlot(slotId);
            ItemStack cursor = handler.getCursorStack();
            if (button == 0) {
                simulateLeftClick(handler, slot, cursor);
            } else if (button == 1) {
                simulateRightClick(handler, slot, cursor, takeovers);
            }
            return null;
        }).when(interactionManager).clickSlot(
                anyInt(),
                anyInt(),
                anyInt(),
                any(SlotActionType.class),
                any(PlayerEntity.class)
        );
    }

    private static void simulateLeftClick(TestScreenHandler handler, Slot slot, ItemStack cursor) {
        if (cursor.isEmpty()) {
            handler.setCursorStack(slot.getStack().copy());
            slot.setStack(ItemStack.EMPTY);
        } else if (!slot.hasStack()) {
            slot.setStack(cursor.copy());
            handler.setCursorStack(ItemStack.EMPTY);
        } else if (ItemStack.areItemsAndComponentsEqual(slot.getStack(), cursor)) {
            int max = Math.min(cursor.getMaxCount(), slot.getMaxItemCount(cursor));
            int moved = Math.min(cursor.getCount(), Math.max(0, max - slot.getStack().getCount()));
            slot.setStack(slot.getStack().copyWithCount(slot.getStack().getCount() + moved));
            handler.setCursorStack(cursor.copyWithCount(cursor.getCount() - moved));
        } else {
            ItemStack old = slot.getStack().copy();
            slot.setStack(cursor.copy());
            handler.setCursorStack(old);
        }
    }

    private static void simulateRightClick(
            TestScreenHandler handler,
            Slot slot,
            ItemStack cursor,
            AtomicInteger takeovers
    ) {
        if (cursor.isEmpty()) {
            ItemStack stack = slot.getStack();
            int taken = Math.max(1, (stack.getCount() + 1) / 2);
            handler.setCursorStack(stack.copyWithCount(taken));
            slot.setStack(stack.copyWithCount(stack.getCount() - taken));
            return;
        }

        if (cursor.isOf(Items.ENDER_CHEST) && slot.hasStack() && slot.getStack().isOf(Items.ENDER_CHEST)) {
            // QuickShulker 的真实回调会把目标槽物品装进玩家末影箱，光标不变。
            takeovers.incrementAndGet();
            slot.setStack(ItemStack.EMPTY);
            return;
        }

        if (!slot.hasStack()) {
            slot.setStack(cursor.copyWithCount(1));
            handler.setCursorStack(cursor.copyWithCount(cursor.getCount() - 1));
        } else if (ItemStack.areItemsAndComponentsEqual(slot.getStack(), cursor) && slot.getStack().getCount() < slot
                .getMaxItemCount(cursor)) {
            slot.setStack(slot.getStack().copyWithCount(slot.getStack().getCount() + 1));
            handler.setCursorStack(cursor.copyWithCount(cursor.getCount() - 1));
        }
    }

    private static PlayerInventory newPlayerInventory(ClientPlayerEntity player) {
        try {
            return PlayerInventory.class.getConstructor(PlayerEntity.class).newInstance(player);
        } catch (NoSuchMethodException ignored) {
            try {
                Class<?> equipmentType = Class.forName("net.minecraft.entity.EntityEquipment");
                Object equipment = equipmentType.getConstructor().newInstance();
                return PlayerInventory.class.getConstructor(PlayerEntity.class, equipmentType).newInstance(
                        player,
                        equipment
                );
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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

        private final net.minecraft.item.Item item;

        QuickShulkerKind(net.minecraft.item.Item item) {
            this.item = item;
        }
    }

    private record Fixture(
            MinecraftClient client,
            ClientPlayerEntity player,
            TestScreenHandler handler,
            AtomicInteger takeovers
    ) {
    }

    private static final class TestScreenHandler extends ScreenHandler {
        private TestScreenHandler() {
            super(null, 1);
        }

        private void addTestSlot(SimpleInventory inventory, int index) {
            addSlot(new Slot(inventory, index, 0, 0));
        }

        private void addPlayerSlot(PlayerInventory inventory, int index) {
            addSlot(new Slot(inventory, index, 0, 0));
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return true;
        }
    }
}
