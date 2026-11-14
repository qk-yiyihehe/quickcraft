package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import sun.misc.Unsafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickContainerSafetyTest {
    @Test
    void sameItemFullAndPartialStackSwapsWithoutHotbarBuffer() throws Exception {
        Minecraft client = mock(Minecraft.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        LocalPlayer player = mock(LocalPlayer.class);
        client.player = player;
        client.gameMode = gameMode;
        setClientScreen(client, null);

        SimpleContainer inventory = new SimpleContainer(2);
        inventory.setItem(0, new ItemStack(Items.DIAMOND, 6));
        inventory.setItem(1, new ItemStack(Items.DIAMOND, 64));
        TestMenu menu = new TestMenu();
        menu.addTestSlot(inventory, 0);
        menu.addTestSlot(inventory, 1);
        installClickHandler(menu, gameMode);

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            Method swap = QuickSort.class.getDeclaredMethod(
                    "swapSlots", AbstractContainerMenu.class, int.class, int.class);
            swap.setAccessible(true);
            boolean swapped = (Boolean) swap.invoke(null, menu, 0, 1);
            assertThat(swapped)
                    .withFailMessage("carried=%s, a=%s, b=%s", menu.getCarried(), menu.getSlot(0).getItem(), menu.getSlot(1).getItem())
                    .isTrue();
        }

        assertThat(menu.getSlot(0).getItem().getCount()).isEqualTo(64);
        assertThat(menu.getSlot(1).getItem().getCount()).isEqualTo(6);
        assertThat(menu.getCarried().isEmpty()).isTrue();
    }

    @Test
    void cursorFillSkipsLockedStackWhenMerging() throws Exception {
        SimpleContainer lockedInventory = new SimpleContainer(1);
        lockedInventory.setItem(0, new ItemStack(Items.STONE, 10));
        SimpleContainer safeInventory = new SimpleContainer(1);
        safeInventory.setItem(0, new ItemStack(Items.STONE, 10));
        TestMenu menu = new TestMenu();
        menu.addTestSlot(lockedInventory, 0);
        menu.addTestSlot(safeInventory, 0);
        menu.setCarried(new ItemStack(Items.STONE, 10));

        runCursorFill(menu, menu.getSlot(0));

        assertThat(lockedInventory.getItem(0).getCount()).isEqualTo(10);
        assertThat(safeInventory.getItem(0).getCount()).isEqualTo(20);
        assertThat(menu.getCarried().isEmpty()).isTrue();
    }

    @Test
    void cursorFillSkipsLockedEmptySlot() throws Exception {
        SimpleContainer lockedInventory = new SimpleContainer(1);
        SimpleContainer safeInventory = new SimpleContainer(1);
        TestMenu menu = new TestMenu();
        menu.addTestSlot(lockedInventory, 0);
        menu.addTestSlot(safeInventory, 0);
        menu.setCarried(new ItemStack(Items.STONE, 10));

        runCursorFill(menu, menu.getSlot(0));

        assertThat(lockedInventory.getItem(0).isEmpty()).isTrue();
        assertThat(safeInventory.getItem(0).getCount()).isEqualTo(10);
        assertThat(menu.getCarried().isEmpty()).isTrue();
    }

    @Test
    void pendingClearDoesNotThrowFromInventoryScreen() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        Minecraft client = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        ClientLevel level = mock(ClientLevel.class);
        ChestBlockEntity target = new ChestBlockEntity(targetPos, Blocks.CHEST.defaultBlockState());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getBlockState(targetPos)).thenReturn(Blocks.CHEST.defaultBlockState());
        when(level.getBlockEntity(targetPos)).thenReturn(target);
        client.player = player;
        client.gameMode = gameMode;
        client.level = level;
        client.hitResult = new BlockHitResult(Vec3.atCenterOf(targetPos), Direction.NORTH, targetPos, false);

        TestMenu inventoryMenu = new TestMenu();
        SimpleContainer crafting = new SimpleContainer(9);
        crafting.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        inventoryMenu.addTestSlot(crafting, 0);
        InventoryScreen screen = mock(InventoryScreen.class);
        doReturn(inventoryMenu).when(screen).getMenu();
        setClientScreen(client, screen);

        QuickClearContainer clear = new QuickClearContainer();
        setPending(clear, true, 1, level, targetPos);
        Method process = QuickClearContainer.class.getDeclaredMethod("processPendingOpen", Minecraft.class);
        process.setAccessible(true);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            process.invoke(clear, client);
        }

        verify(gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat((Boolean) getField(clear, "pendingOpen")).isFalse();
        assertThat(getField(clear, "pendingPos")).isNull();
    }

    @Test
    void pendingClearRejectsScreenForAnotherTarget() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        BlockPos otherPos = new BlockPos(2, 64, 1);
        Minecraft client = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        ClientLevel level = mock(ClientLevel.class);
        ChestBlockEntity target = new ChestBlockEntity(targetPos, Blocks.CHEST.defaultBlockState());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getBlockState(targetPos)).thenReturn(Blocks.CHEST.defaultBlockState());
        when(level.getBlockEntity(targetPos)).thenReturn(target);
        when(level.getBlockState(otherPos)).thenReturn(Blocks.CHEST.defaultBlockState());
        client.player = player;
        client.gameMode = gameMode;
        client.level = level;
        client.hitResult = new BlockHitResult(Vec3.atCenterOf(otherPos), Direction.NORTH, otherPos, false);

        Inventory playerInventory = new Inventory(player, new EntityEquipment());
        ChestMenu menu = ChestMenu.threeRows(1, playerInventory);
        menu.getSlot(0).set(new ItemStack(Items.STONE, 3));
        installClickHandler(menu, gameMode);
        AbstractContainerScreen<?> screen = mock(AbstractContainerScreen.class);
        doReturn(menu).when(screen).getMenu();
        setClientScreen(client, screen);

        QuickClearContainer clear = new QuickClearContainer();
        setPending(clear, true, 1, level, targetPos);
        Method process = QuickClearContainer.class.getDeclaredMethod("processPendingOpen", Minecraft.class);
        process.setAccessible(true);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            process.invoke(clear, client);
        }

        verify(gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat((Boolean) getField(clear, "pendingOpen")).isFalse();
        assertThat(getField(clear, "pendingPos")).isNull();
    }

    @Test
    void pendingClearAcceptsVanillaSingleChestScreen() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        LocalPlayer player = mock(LocalPlayer.class);
        Inventory playerInventory = new Inventory(player, new EntityEquipment());
        ChestBlockEntity target = new ChestBlockEntity(targetPos, Blocks.CHEST.defaultBlockState());
        ChestMenu menu = ChestMenu.threeRows(1, playerInventory);
        assertThat(menu.getContainer()).isNotSameAs(target);
        assertPendingClearRuns(target, Blocks.CHEST.defaultBlockState(), menu, 0);
        assertThat(target.getItem(0).isEmpty()).isTrue();
    }

    @Test
    void pendingClearAcceptsVanillaDoubleChestScreen() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        LocalPlayer player = mock(LocalPlayer.class);
        Inventory playerInventory = new Inventory(player, new EntityEquipment());
        ChestBlockEntity target = new ChestBlockEntity(targetPos, Blocks.CHEST.defaultBlockState());
        ChestMenu menu = ChestMenu.sixRows(1, playerInventory);
        assertThat(menu.getContainer()).isNotSameAs(target);
        assertPendingClearRuns(target,
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT), menu, 53);
    }

    @Test
    void pendingClearAcceptsVanillaShulkerBoxScreen() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        LocalPlayer player = mock(LocalPlayer.class);
        Inventory playerInventory = new Inventory(player, new EntityEquipment());
        ShulkerBoxBlockEntity target = new ShulkerBoxBlockEntity(
                targetPos, Blocks.SHULKER_BOX.defaultBlockState());
        ShulkerBoxMenu menu = new ShulkerBoxMenu(1, playerInventory);
        assertThat(getField(menu, "container")).isNotSameAs(target);
        assertPendingClearRuns(target, Blocks.SHULKER_BOX.defaultBlockState(), menu, 0);
        assertThat(target.getItem(0).isEmpty()).isTrue();
    }

    @Test
    void pendingClearRejectsMismatchedMenuCapacity() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        Minecraft client = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        ClientLevel level = mock(ClientLevel.class);
        ChestBlockEntity target = new ChestBlockEntity(targetPos, Blocks.CHEST.defaultBlockState());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getBlockState(targetPos)).thenReturn(Blocks.CHEST.defaultBlockState());
        when(level.getBlockEntity(targetPos)).thenReturn(target);
        client.player = player;
        client.gameMode = gameMode;
        client.level = level;
        client.hitResult = new BlockHitResult(Vec3.atCenterOf(targetPos), Direction.NORTH, targetPos, false);

        ChestMenu menu = ChestMenu.oneRow(1, new Inventory(player, new EntityEquipment()));
        menu.getSlot(0).set(new ItemStack(Items.STONE, 3));
        installClickHandler(menu, gameMode);
        AbstractContainerScreen<?> screen = mock(AbstractContainerScreen.class);
        doReturn(menu).when(screen).getMenu();
        setClientScreen(client, screen);

        QuickClearContainer clear = new QuickClearContainer();
        setPending(clear, true, 1, level, targetPos);
        Method process = QuickClearContainer.class.getDeclaredMethod("processPendingOpen", Minecraft.class);
        process.setAccessible(true);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            process.invoke(clear, client);
        }

        verify(gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat(menu.getSlot(0).getItem().is(Items.STONE)).isTrue();
        assertThat(getField(clear, "pendingPos")).isNull();
    }

    private static void assertPendingClearRuns(BlockEntity targetEntity,
                                               BlockState targetState,
                                               AbstractContainerMenu menu,
                                               int itemSlot) throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        Minecraft client = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        ClientLevel level = mock(ClientLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getBlockState(targetPos)).thenReturn(targetState);
        when(level.getBlockEntity(targetPos)).thenReturn(targetEntity);
        client.player = player;
        client.gameMode = gameMode;
        client.level = level;
        client.hitResult = new BlockHitResult(Vec3.atCenterOf(targetPos), Direction.NORTH, targetPos, false);

        menu.getSlot(itemSlot).set(new ItemStack(Items.STONE, 3));
        installClickHandler(menu, gameMode);
        AbstractContainerScreen<?> screen = mock(AbstractContainerScreen.class);
        doReturn(menu).when(screen).getMenu();
        setClientScreen(client, screen);

        QuickClearContainer clear = new QuickClearContainer();
        setPending(clear, true, 1, level, targetPos);
        Method process = QuickClearContainer.class.getDeclaredMethod("processPendingOpen", Minecraft.class);
        process.setAccessible(true);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            process.invoke(clear, client);
        }

        verify(gameMode, atLeastOnce()).handleContainerInput(anyInt(), eq(itemSlot), eq(1),
                eq(ContainerInput.THROW), any());
        assertThat(menu.getSlot(itemSlot).getItem().isEmpty()).isTrue();
        assertThat(getField(clear, "pendingPos")).isNull();
    }

    @Test
    void quickFillDoesNotLetFurnaceFallbackReorderPlayerInventory() throws Exception {
        LocalPlayer player = mock(LocalPlayer.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        ClientLevel level = mock(ClientLevel.class);
        RecipeAccess recipeAccess = mock(RecipeAccess.class);
        when(level.recipeAccess()).thenReturn(recipeAccess);
        stubFuelValuesIfPresent(level);
        when(recipeAccess.propertySet(any())).thenReturn(RecipePropertySet.EMPTY);
        when(player.level()).thenReturn(level);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        inventory.getNonEquipmentItems().set(20, new ItemStack(Items.DIRT, 64));
        when(player.getInventory()).thenReturn(inventory);

        Minecraft client = mock(Minecraft.class);
        client.player = player;
        client.gameMode = gameMode;
        client.level = level;
        setClientScreen(client, null);

        FurnaceMenu furnace = new FurnaceMenu(1, inventory, new SimpleContainer(3), new SimpleContainerData(4));
        AbstractContainerScreen<?> screen = mock(AbstractContainerScreen.class);
        doReturn(furnace).when(screen).getMenu();

        QuickFillContainer fill = new QuickFillContainer();
        Method fillContainer = QuickFillContainer.class.getDeclaredMethod("fillContainer", AbstractContainerScreen.class);
        fillContainer.setAccessible(true);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            fillContainer.invoke(fill, screen);
        }

        verify(gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat(inventory.getNonEquipmentItems().get(20).getCount()).isEqualTo(64);
    }

    @Test
    void quickFillPartiallyFillsWhenSourceStackExceedsContainerCapacity() throws Exception {
        LocalPlayer player = mock(LocalPlayer.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        Inventory playerInventory = new Inventory(player, new EntityEquipment());
        playerInventory.getNonEquipmentItems().set(0, new ItemStack(Items.STONE, 64));
        when(player.getInventory()).thenReturn(playerInventory);

        SimpleContainer container = new SimpleContainer(27);
        for (int i = 0; i < 26; i++) {
            container.setItem(i, new ItemStack(Items.STONE, 64));
        }
        container.setItem(26, new ItemStack(Items.STONE, 32));
        ChestMenu menu = ChestMenu.threeRows(1, playerInventory, container);
        installClickHandler(menu, gameMode);
        Minecraft client = mock(Minecraft.class);
        client.player = player;
        client.gameMode = gameMode;
        setClientScreen(client, null);
        AbstractContainerScreen<?> screen = mock(AbstractContainerScreen.class);
        doReturn(menu).when(screen).getMenu();

        QuickFillContainer fill = new QuickFillContainer();
        Method fillContainer = QuickFillContainer.class.getDeclaredMethod("fillContainer", AbstractContainerScreen.class);
        fillContainer.setAccessible(true);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            fillContainer.invoke(fill, screen);
        }

        assertThat(container.getItem(26).getCount()).isEqualTo(64);
        assertThat(playerInventory.getNonEquipmentItems().get(0).getCount()).isEqualTo(32);
        verify(gameMode, atLeastOnce()).handleContainerInput(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void stoppingContinuousTaskDoesNotRecoverFromKnownSourceShulker() throws Exception {
        ShulkerStashFixture fixture = shulkerStashFixture();
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Component.literal("材料不足"));

        assertThat(fixture.targetMenu().getSlot(0).getItem().is(Items.SHULKER_BOX)).isTrue();
        assertThat(fixture.sourceMenu().getSlot(0).getItem().is(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();

        AbstractContainerScreen<?> targetScreen = mock(AbstractContainerScreen.class);
        doReturn(fixture.targetMenu()).when(targetScreen).getMenu();
        setClientScreen(fixture.client(), targetScreen);
        fixture.client().hitResult = new BlockHitResult(
                Vec3.atCenterOf(fixture.targetPos()), Direction.NORTH, fixture.targetPos(), false);
        installClickHandler(fixture.targetMenu(), fixture.gameMode());
        Method processPending = QuickContainerCopy.class.getDeclaredMethod(
                "processPendingStashRecovery", Minecraft.class);
        processPending.setAccessible(true);
        processPending.invoke(copy, fixture.client());

        assertThat(fixture.targetMenu().getSlot(0).getItem().isEmpty()).isTrue();
        assertThat(fixture.sourceMenu().getSlot(0).getItem().is(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNull();
    }

    @Test
    void backgroundStashRecoveryRejectsReplacedContainerType() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        StashFixture fixture = stashFixture(targetPos, new BlockPos(2, 64, 1));
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Component.literal("材料不足"));
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();

        // 原小箱被拆掉，同坐标换成木桶；两者都是三行菜单，保存的 type 不足以识别。
        when(fixture.client().level.getBlockState(targetPos)).thenReturn(Blocks.BARREL.defaultBlockState());
        ChestMenu barrelMenu = ChestMenu.threeRows(9, fixture.playerInventory());
        barrelMenu.getSlot(0).set(new ItemStack(Items.SHULKER_BOX));
        MultiPlayerGameMode gameMode = (MultiPlayerGameMode) getField(fixture.client(), "gameMode");
        installClickHandler(barrelMenu, gameMode);
        AbstractContainerScreen<?> barrelScreen = mock(AbstractContainerScreen.class);
        doReturn(barrelMenu).when(barrelScreen).getMenu();
        setClientScreen(fixture.client(), barrelScreen);
        fixture.client().hitResult = new BlockHitResult(
                Vec3.atCenterOf(targetPos), Direction.NORTH, targetPos, false);

        Method processPending = QuickContainerCopy.class.getDeclaredMethod(
                "processPendingStashRecovery", Minecraft.class);
        processPending.setAccessible(true);
        processPending.invoke(copy, fixture.client());

        verify(gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat(barrelMenu.getSlot(0).getItem().is(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();
    }

    @Test
    void backgroundStashRecoveryRestoresForMatchingTargetType() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        StashFixture fixture = stashFixture(targetPos, new BlockPos(2, 64, 1));
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Component.literal("材料不足"));
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();

        // 重新打开原目标：独立的客户端菜单，不是 pending 创建时的那一份。
        ChestMenu reopenedMenu = ChestMenu.threeRows(7, fixture.playerInventory());
        reopenedMenu.getSlot(0).set(new ItemStack(Items.SHULKER_BOX));
        MultiPlayerGameMode gameMode = (MultiPlayerGameMode) getField(fixture.client(), "gameMode");
        installClickHandler(reopenedMenu, gameMode);
        AbstractContainerScreen<?> reopenedScreen = mock(AbstractContainerScreen.class);
        doReturn(reopenedMenu).when(reopenedScreen).getMenu();
        setClientScreen(fixture.client(), reopenedScreen);
        fixture.client().hitResult = new BlockHitResult(
                Vec3.atCenterOf(targetPos), Direction.NORTH, targetPos, false);

        Method processPending = QuickContainerCopy.class.getDeclaredMethod(
                "processPendingStashRecovery", Minecraft.class);
        processPending.setAccessible(true);
        processPending.invoke(copy, fixture.client());

        assertThat(reopenedMenu.getSlot(0).getItem().isEmpty()).isTrue();
        assertThat(fixture.playerInventory().getNonEquipmentItems().stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.is(Items.SHULKER_BOX))).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNull();
    }

    @Test
    void stoppingContinuousTaskDoesNotRestoreFromReplacedContainerType() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        StashFixture fixture = stashFixture(targetPos, targetPos);
        when(fixture.client().level.getBlockState(targetPos)).thenReturn(Blocks.BARREL.defaultBlockState());
        ChestMenu barrelMenu = ChestMenu.threeRows(9, fixture.playerInventory());
        barrelMenu.getSlot(0).set(new ItemStack(Items.SHULKER_BOX));
        MultiPlayerGameMode gameMode = (MultiPlayerGameMode) getField(fixture.client(), "gameMode");
        installClickHandler(barrelMenu, gameMode);
        AbstractContainerScreen<?> barrelScreen = mock(AbstractContainerScreen.class);
        doReturn(barrelMenu).when(barrelScreen).getMenu();
        setClientScreen(fixture.client(), barrelScreen);

        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Component.literal("材料不足"));

        verify(gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat(barrelMenu.getSlot(0).getItem().is(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();
    }

    @Test
    void pendingStashBlocksSameTargetRefillBeforeClearingOrOverflowDrop() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        StashFixture fixture = stashFixture(targetPos, new BlockPos(2, 64, 1));
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Component.literal("材料不足"));
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();

        for (int slot = 0; slot < 36; slot++) {
            fixture.playerInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.DIRT, 64));
        }
        installContinuousTask(targetPos, "SMALL_CHEST", fixture.client().level);

        try (var minecraft = mockStatic(Minecraft.class);
             var configs = mockStatic(QuickCraftConfigs.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(fixture.client());
            configs.when(QuickCraftConfigs::isContainerFillOverflowDropEnabled).thenReturn(true);
            Method fillTarget = QuickContainerCopy.class.getDeclaredMethod(
                    "fillContinuousTarget", Minecraft.class);
            fillTarget.setAccessible(true);
            fillTarget.invoke(copy, fixture.client());
        }

        assertThat(fixture.menu().getSlot(0).getItem().is(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();
        MultiPlayerGameMode gameMode = (MultiPlayerGameMode) getField(fixture.client(), "gameMode");
        verify(gameMode, atLeastOnce()).handleContainerInput(anyInt(), anyInt(), anyInt(),
                eq(ContainerInput.PICKUP), any());
        verify(gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(),
                eq(ContainerInput.THROW), any());
        verify(gameMode, never()).handleContainerInput(anyInt(), anyInt(), anyInt(),
                eq(ContainerInput.QUICK_MOVE), any());
    }

    @Test
    void pendingStashBlocksUncertainSamePositionTypeChange() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        GuardEnv env = guardEnv(6);
        when(env.level().getBlockState(targetPos)).thenReturn(
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT));
        when(env.level().getBlockEntity(targetPos))
                .thenReturn(new ChestBlockEntity(targetPos, Blocks.CHEST.defaultBlockState()));
        env.menu().getSlot(0).set(new ItemStack(Items.SHULKER_BOX));
        installPendingStash(targetPos, "SMALL_CHEST", new ItemStack(Items.SHULKER_BOX), env.level());
        installContinuousTask(targetPos, "LARGE_CHEST", env.level());

        invokeFillContinuousTask(env);

        assertThat(env.menu().getSlot(0).getItem().is(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();
        verify(env.gameMode(), never()).handleContainerInput(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void pendingStashDoesNotBlockUnrelatedContainerFill() throws Exception {
        BlockPos stashedPos = new BlockPos(1, 64, 1);
        BlockPos targetPos = new BlockPos(5, 64, 5);
        GuardEnv env = guardEnv(3);
        when(env.level().getBlockState(stashedPos)).thenReturn(Blocks.CHEST.defaultBlockState());
        when(env.level().getBlockState(targetPos)).thenReturn(Blocks.CHEST.defaultBlockState());
        when(env.level().getBlockEntity(targetPos))
                .thenReturn(new ChestBlockEntity(targetPos, Blocks.CHEST.defaultBlockState()));
        env.menu().getSlot(0).set(new ItemStack(Items.DIRT, 3));
        installPendingStash(stashedPos, "SMALL_CHEST", new ItemStack(Items.SHULKER_BOX), env.level());
        installContinuousTask(targetPos, "SMALL_CHEST", env.level());

        invokeFillContinuousTask(env);

        assertThat(env.menu().getSlot(0).getItem().isEmpty()).isTrue();
        assertThat(env.playerInventory().getNonEquipmentItems().stream()
                .anyMatch(stack -> stack.is(Items.DIRT))).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();
        verify(env.gameMode(), atLeastOnce()).handleContainerInput(anyInt(), anyInt(), anyInt(),
                eq(ContainerInput.QUICK_MOVE), any());
    }

    @Test
    void stoppingContinuousTaskRestoresStashFromVanillaTargetMenu() throws Exception {
        StashFixture fixture = stashFixture(new BlockPos(1, 64, 1), new BlockPos(1, 64, 1));
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), true, null);

        assertThat(fixture.menu().getSlot(0).getItem().isEmpty()).isTrue();
        assertThat(fixture.playerInventory().getNonEquipmentItems().stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.is(Items.SHULKER_BOX))).isTrue();
        assertThat(fixture.clientSideEntity().getItem(0).isEmpty()).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "continuousTask")).isNull();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNull();
    }

    @Test
    void stoppingContinuousTaskKeepsPendingWhenTargetCannotBeConfirmed() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        BlockPos otherPos = new BlockPos(2, 64, 1);
        StashFixture fixture = stashFixture(targetPos, otherPos);
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Component.literal("材料不足"));

        assertThat(fixture.menu().getSlot(0).getItem().is(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "continuousTask")).isNull();
        Object pending = getStaticField(QuickContainerCopy.class, "pendingStashRecovery");
        assertThat(pending).isNotNull();
        Object stash = getField(pending, "stash");
        ItemStack stashed = (ItemStack) getField(stash, "stack");
        assertThat(stashed.is(Items.SHULKER_BOX)).isTrue();
        assertThat(stashed.getCount()).isEqualTo(1);

        fixture.client().hitResult = new BlockHitResult(
                Vec3.atCenterOf(targetPos), Direction.NORTH, targetPos, false);
        AbstractContainerScreen<?> targetScreen = mock(AbstractContainerScreen.class);
        doReturn(fixture.menu()).when(targetScreen).getMenu();
        setClientScreen(fixture.client(), targetScreen);
        Method processPending = QuickContainerCopy.class.getDeclaredMethod(
                "processPendingStashRecovery", Minecraft.class);
        processPending.setAccessible(true);
        processPending.invoke(copy, fixture.client());

        assertThat(fixture.menu().getSlot(0).getItem().isEmpty()).isTrue();
        assertThat(fixture.playerInventory().getNonEquipmentItems().stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.is(Items.SHULKER_BOX))).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNull();
    }

    private static ShulkerStashFixture shulkerStashFixture() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        LocalPlayer player = mock(LocalPlayer.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        ClientLevel level = mock(ClientLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getBlockState(targetPos)).thenReturn(Blocks.SHULKER_BOX.defaultBlockState());
        Inventory playerInventory = new Inventory(player, new EntityEquipment());
        when(player.getInventory()).thenReturn(playerInventory);
        when(player.getAbilities()).thenReturn(new Abilities());
        ShulkerBoxBlockEntity clientCopy = new ShulkerBoxBlockEntity(
                targetPos, Blocks.SHULKER_BOX.defaultBlockState());
        when(level.getBlockEntity(targetPos)).thenReturn(clientCopy);

        ShulkerBoxMenu targetMenu = new ShulkerBoxMenu(7, playerInventory);
        targetMenu.getSlot(0).set(new ItemStack(Items.SHULKER_BOX));
        ShulkerBoxMenu sourceMenu = new ShulkerBoxMenu(8, playerInventory);
        sourceMenu.getSlot(0).set(new ItemStack(Items.SHULKER_BOX));
        installClickHandler(targetMenu, gameMode);
        installClickHandler(sourceMenu, gameMode);
        setField(player, "containerMenu", sourceMenu);

        Minecraft client = mock(Minecraft.class);
        client.player = player;
        client.gameMode = gameMode;
        client.level = level;
        client.hitResult = new BlockHitResult(
                Vec3.atCenterOf(targetPos), Direction.NORTH, targetPos, false);
        AbstractContainerScreen<?> sourceScreen = mock(AbstractContainerScreen.class);
        doReturn(sourceMenu).when(sourceScreen).getMenu();
        setClientScreen(client, sourceScreen);

        Object task = continuousTask(blockTarget(targetPos, "SHULKER_BOX"),
                emptyTemplate("SHULKER_BOX"), level);
        setField(task, "temporaryStash", temporaryStash(0, 0, new ItemStack(Items.SHULKER_BOX)));
        setStage(task, "EXTRACT_SOURCE");
        setField(task, "previousSyncId", 7);
        setField(task, "worldRef", new WeakReference<>(level));
        setStaticField(QuickContainerCopy.class, "continuousTask", task);
        setStaticField(QuickContainerCopy.class, "pendingStashRecovery", null);
        setStaticField(QuickContainerCopy.class, "lastPendingStashScreen", null);
        return new ShulkerStashFixture(client, clientCopy, targetMenu, sourceMenu, targetPos, gameMode);
    }

    private static StashFixture stashFixture(BlockPos targetPos, BlockPos crosshairPos) throws Exception {
        LocalPlayer player = mock(LocalPlayer.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        ClientLevel level = mock(ClientLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getBlockState(targetPos)).thenReturn(Blocks.CHEST.defaultBlockState());
        when(level.getBlockState(crosshairPos)).thenReturn(Blocks.CHEST.defaultBlockState());
        Inventory playerInventory = new Inventory(player, new EntityEquipment());
        when(player.getInventory()).thenReturn(playerInventory);
        when(player.getAbilities()).thenReturn(new Abilities());
        ChestBlockEntity clientCopy = new ChestBlockEntity(targetPos, Blocks.CHEST.defaultBlockState());
        when(level.getBlockEntity(targetPos)).thenReturn(clientCopy);

        ChestMenu menu = ChestMenu.threeRows(1, playerInventory);
        menu.getSlot(0).set(new ItemStack(Items.SHULKER_BOX));
        installClickHandler(menu, gameMode);
        setField(player, "containerMenu", menu);

        Minecraft client = mock(Minecraft.class);
        client.player = player;
        client.gameMode = gameMode;
        client.level = level;
        client.hitResult = new BlockHitResult(Vec3.atCenterOf(crosshairPos), Direction.NORTH, crosshairPos, false);
        AbstractContainerScreen<?> screen = mock(AbstractContainerScreen.class);
        doReturn(menu).when(screen).getMenu();
        setClientScreen(client, screen);

        Object task = continuousTask(blockTarget(targetPos, "SMALL_CHEST"),
                emptyTemplate("SMALL_CHEST"), level);
        setField(task, "temporaryStash", temporaryStash(0, 0, new ItemStack(Items.SHULKER_BOX)));
        setStage(task, "FILL_TARGET");
        setField(task, "worldRef", new WeakReference<>(level));
        setStaticField(QuickContainerCopy.class, "continuousTask", task);
        setStaticField(QuickContainerCopy.class, "pendingStashRecovery", null);
        setStaticField(QuickContainerCopy.class, "lastPendingStashScreen", null);
        return new StashFixture(client, playerInventory, menu, clientCopy);
    }

    private static GuardEnv guardEnv(int rows) throws Exception {
        LocalPlayer player = mock(LocalPlayer.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        ClientLevel level = mock(ClientLevel.class);
        Inventory playerInventory = new Inventory(player, new EntityEquipment());
        when(player.getInventory()).thenReturn(playerInventory);
        when(player.getAbilities()).thenReturn(new Abilities());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        ChestMenu menu = rows == 6
                ? ChestMenu.sixRows(1, playerInventory)
                : ChestMenu.threeRows(1, playerInventory);
        installClickHandler(menu, gameMode);
        setField(player, "containerMenu", menu);
        Minecraft client = mock(Minecraft.class);
        client.player = player;
        client.gameMode = gameMode;
        client.level = level;
        AbstractContainerScreen<?> screen = mock(AbstractContainerScreen.class);
        doReturn(menu).when(screen).getMenu();
        setClientScreen(client, screen);
        return new GuardEnv(client, playerInventory, gameMode, level, menu);
    }

    private static void invokeFillContinuousTask(GuardEnv env) throws Exception {
        QuickContainerCopy copy = new QuickContainerCopy();
        Method fillTarget = QuickContainerCopy.class.getDeclaredMethod(
                "fillContinuousTarget", Minecraft.class);
        fillTarget.setAccessible(true);
        try (var minecraft = mockStatic(Minecraft.class);
             var configs = mockStatic(QuickCraftConfigs.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(env.client());
            configs.when(QuickCraftConfigs::isContainerFillOverflowDropEnabled).thenReturn(true);
            fillTarget.invoke(copy, env.client());
        }
    }

    private static void installPendingStash(BlockPos pos, String typeName, ItemStack stack, Level level)
            throws Exception {
        setStaticField(QuickContainerCopy.class, "pendingStashRecovery",
                pendingRecovery(blockTarget(pos, typeName), new WeakReference<>(level),
                        temporaryStash(0, 0, stack)));
        setStaticField(QuickContainerCopy.class, "lastPendingStashScreen", null);
    }

    private static void installContinuousTask(BlockPos pos, String typeName, Level level) throws Exception {
        Object task = continuousTask(blockTarget(pos, typeName), emptyTemplate(typeName), level);
        setStage(task, "FILL_TARGET");
        setField(task, "worldRef", new WeakReference<>(level));
        setStaticField(QuickContainerCopy.class, "continuousTask", task);
    }

    private static Class<?> copyNested(String name) throws ClassNotFoundException {
        return Class.forName("com.yiyihehe.quickcraft.QuickContainerCopy$" + name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object supportedType(String typeName) throws Exception {
        Class<?> typeClass = copyNested("SupportedContainerType");
        return Enum.valueOf((Class<? extends Enum>) typeClass, typeName);
    }

    private static Object blockTarget(BlockPos pos, String typeName) throws Exception {
        Class<?> typeClass = copyNested("SupportedContainerType");
        Class<?> targetClass = copyNested("TargetInteraction");
        var constructor = targetClass.getDeclaredConstructor(HitResult.class, typeClass);
        constructor.setAccessible(true);
        return constructor.newInstance(new BlockHitResult(
                Vec3.atCenterOf(pos), Direction.NORTH, pos, false), supportedType(typeName));
    }

    private static Object emptyTemplate(String typeName) throws Exception {
        Class<?> typeClass = copyNested("SupportedContainerType");
        Class<?> recordedClass = copyNested("RecordedContainerTemplate");
        var recordedConstructor = recordedClass.getDeclaredConstructor(typeClass, List.class, List.class);
        recordedConstructor.setAccessible(true);
        Object recorded = recordedConstructor.newInstance(
                supportedType(typeName), List.of(ItemStack.EMPTY), List.of(false));
        Class<?> templateClass = copyNested("ContinuousTemplate");
        var templateConstructor = templateClass.getDeclaredConstructor(
                recordedClass, boolean.class, copyNested("SuccessMessage"));
        templateConstructor.setAccessible(true);
        return templateConstructor.newInstance(recorded, false, successMessage("quickcraft.message.test"));
    }

    private static Object continuousTask(Object target, Object template, Level level) throws Exception {
        Class<?> taskClass = copyNested("ContinuousFillTask");
        var constructor = taskClass.getDeclaredConstructor(
                copyNested("TargetInteraction"), copyNested("ContinuousTemplate"), ResourceKey.class,
                com.yiyihehe.quickcraft.render.QuickContainerFillStatus.Handle.class);
        constructor.setAccessible(true);
        return constructor.newInstance(target, template, Level.OVERWORLD, null);
    }

    private static Object temporaryStash(int templateIndex, int slotId, ItemStack stack) throws Exception {
        Class<?> stashClass = copyNested("TemporaryContainerStash");
        var constructor = stashClass.getDeclaredConstructor(int.class, int.class, ItemStack.class);
        constructor.setAccessible(true);
        return constructor.newInstance(templateIndex, slotId, stack);
    }

    private static Object pendingRecovery(Object target, WeakReference<Level> worldRef, Object stash)
            throws Exception {
        Class<?> pendingClass = copyNested("PendingStashRecovery");
        var constructor = pendingClass.getDeclaredConstructor(
                copyNested("TargetInteraction"), ResourceKey.class, WeakReference.class,
                copyNested("TemporaryContainerStash"));
        constructor.setAccessible(true);
        return constructor.newInstance(target, Level.OVERWORLD, worldRef, stash);
    }

    private static Object successMessage(String key) throws Exception {
        Class<?> messageClass = copyNested("SuccessMessage");
        Method of = messageClass.getDeclaredMethod("of", String.class, Object[].class);
        of.setAccessible(true);
        return of.invoke(null, key, new Object[0]);
    }

    private static void setStage(Object task, String stageName) throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object stage = Enum.valueOf((Class<? extends Enum>) copyNested("ContinuousStage"), stageName);
        setField(task, "stage", stage);
    }

    private static void invokeStop(QuickContainerCopy copy,
                                   Minecraft client,
                                   boolean closeScreen,
                                   Component message) throws Exception {
        Method stop = QuickContainerCopy.class.getDeclaredMethod(
                "stopContinuousTask", Minecraft.class, boolean.class, Component.class);
        stop.setAccessible(true);
        stop.invoke(copy, client, closeScreen, message);
    }

    private record StashFixture(Minecraft client,
                                Inventory playerInventory,
                                ChestMenu menu,
                                ChestBlockEntity clientSideEntity) {
    }

    private record ShulkerStashFixture(Minecraft client,
                                       ShulkerBoxBlockEntity clientSideEntity,
                                       ShulkerBoxMenu targetMenu,
                                       ShulkerBoxMenu sourceMenu,
                                       BlockPos targetPos,
                                       MultiPlayerGameMode gameMode) {
    }

    private record GuardEnv(Minecraft client,
                            Inventory playerInventory,
                            MultiPlayerGameMode gameMode,
                            ClientLevel level,
                            ChestMenu menu) {
    }

    private static void runCursorFill(TestMenu menu, Slot lockedSlot) throws Exception {
        Minecraft client = mock(Minecraft.class);
        MultiPlayerGameMode gameMode = mock(MultiPlayerGameMode.class);
        LocalPlayer player = mock(LocalPlayer.class);
        when(player.getInventory()).thenReturn(new Inventory(player, new EntityEquipment()));
        client.player = player;
        client.gameMode = gameMode;
        setClientScreen(client, null);
        installClickHandler(menu, gameMode);

        try (var minecraft = mockStatic(Minecraft.class);
             var locks = mockStatic(QuickContainerLock.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            locks.when(() -> QuickContainerLock.isLockedSlot(any(AbstractContainerMenu.class), any(Slot.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1) == lockedSlot);
            Method store = QuickSort.class.getDeclaredMethod("storeCursorStackInVisibleSlots", AbstractContainerMenu.class);
            store.setAccessible(true);
            assertThat((Boolean) store.invoke(null, menu)).isTrue();
        }
    }

    private static void installClickHandler(AbstractContainerMenu menu,
                                            MultiPlayerGameMode gameMode) {
        doAnswer(invocation -> {
            if (invocation.getArgument(3) == ContainerInput.PICKUP) {
                simulatePickup(menu, invocation.getArgument(1));
            } else if (invocation.getArgument(3) == ContainerInput.QUICK_MOVE) {
                menu.quickMoveStack(invocation.getArgument(4), invocation.getArgument(1));
            } else if (invocation.getArgument(3) == ContainerInput.THROW) {
                // 原版 THROW 在 button!=0 时整组丢出，测试按这个语义清空槽位。
                menu.getSlot(invocation.getArgument(1)).set(ItemStack.EMPTY);
            }
            return null;
        }).when(gameMode).handleContainerInput(anyInt(), anyInt(), anyInt(),
                any(ContainerInput.class), any(Player.class));
    }

    private static void simulatePickup(AbstractContainerMenu menu, int slotId) {
        Slot slot = menu.getSlot(slotId);
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            menu.setCarried(slot.getItem().copy());
            slot.set(ItemStack.EMPTY);
        } else if (!slot.hasItem()) {
            slot.set(carried.copy());
            menu.setCarried(ItemStack.EMPTY);
        } else if (ItemStack.isSameItemSameComponents(slot.getItem(), carried)) {
            int max = Math.min(carried.getMaxStackSize(), slot.getMaxStackSize(carried));
            int moved = Math.min(carried.getCount(), Math.max(0, max - slot.getItem().getCount()));
            slot.set(slot.getItem().copyWithCount(slot.getItem().getCount() + moved));
            menu.setCarried(carried.copyWithCount(carried.getCount() - moved));
            if (menu.getCarried().isEmpty()) {
                menu.setCarried(ItemStack.EMPTY);
            }
        } else {
            ItemStack slotStack = slot.getItem().copy();
            slot.set(carried.copy());
            menu.setCarried(slotStack);
        }
        slot.setChanged();
    }

    private static void setPending(QuickClearContainer clear,
                                   boolean open,
                                   int ticks,
                                   ClientLevel level,
                                   BlockPos pos) throws Exception {
        setField(clear, "pendingOpen", open);
        setField(clear, "pendingTicks", ticks);
        setField(clear, "pendingLevel", level);
        setField(clear, "pendingPos", pos);
    }

    private static void stubFuelValuesIfPresent(ClientLevel level) throws Exception {
        try {
            Method fuelValues = level.getClass().getMethod("fuelValues");
            Object fuel = mock(fuelValues.getReturnType());
            when(fuelValues.invoke(level)).thenReturn(fuel);
        } catch (NoSuchMethodException ignored) {
        }
    }

    private static void setClientScreen(Minecraft client, Screen screen) throws Exception {
        try {
            Field screenField = findField(Minecraft.class, "screen");
            screenField.set(client, screen);
        } catch (NoSuchFieldException ignored) {
        }
        try {
            Field guiField = findField(Minecraft.class, "gui");
            Object gui = mock(Gui.class, invocation -> {
                if (invocation.getMethod().getName().equals("screen")) {
                    return screen;
                }
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
            setFinalField(client, guiField, gui);
        } catch (NoSuchFieldException ignored) {
        }
    }

    private static void setFinalField(Object target, Field field, Object value) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Object base = Modifier.isStatic(field.getModifiers()) ? null : target;
        unsafe.putObject(base, unsafe.objectFieldOffset(field), value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object getStaticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static final class TestMenu extends AbstractContainerMenu {
        private TestMenu() {
            super(null, 1);
        }

        private void addTestSlot(Container container, int index) {
            addSlot(new Slot(container, index, 0, 0));
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
