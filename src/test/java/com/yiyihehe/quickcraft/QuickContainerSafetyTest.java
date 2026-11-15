package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

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
    void sortAbortsAfterFirstClickExceptionWithoutFurtherClicksOrCursorRecovery() throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        ClientPlayerInteractionManager manager = mock(ClientPlayerInteractionManager.class);
        setField(client, "player", player);
        setField(client, "interactionManager", manager);
        TestScreenHandler handler = new TestScreenHandler();
        SimpleInventory inventory = new SimpleInventory(2);
        handler.addTestSlot(inventory, 0);
        handler.addTestSlot(inventory, 1);
        handler.setCursorStack(new ItemStack(Items.STONE, 3));
        net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen = mock(net.minecraft.client.gui.screen.ingame.HandledScreen.class);
        doReturn(handler).when(screen).getScreenHandler();
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated third-party menu failure"))
                .when(manager).clickSlot(anyInt(), anyInt(), anyInt(), any(SlotActionType.class), any(PlayerEntity.class));
        Class<?> targetType = java.util.Arrays.stream(QuickSort.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("SortTarget")).findFirst().orElseThrow();
        var constructor = targetType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object target = constructor.newInstance("container", List.of(0, 1), null, handler);
        Method sort = QuickSort.class.getDeclaredMethod("sortInventory", net.minecraft.client.gui.screen.ingame.HandledScreen.class, targetType);
        sort.setAccessible(true);
        try (var minecraft = mockStatic(MinecraftClient.class);
             var locks = mockStatic(QuickContainerLock.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            sort.invoke(null, screen, target);
        }
        verify(manager).clickSlot(anyInt(), anyInt(), anyInt(), any(SlotActionType.class), any(PlayerEntity.class));
        assertThat(handler.getCursorStack().getCount()).isEqualTo(3);
        assertThat(inventory.getStack(0).isEmpty()).isTrue();
        assertThat(inventory.getStack(1).isEmpty()).isTrue();
        verify(player).sendMessage(Text.translatable("quickcraft.message.sort.incomplete"), true);
    }

    @Test
    void sameItemFullAndPartialStackSwapsWithoutHotbarBuffer() throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        when(player.getInventory()).thenReturn(newPlayerInventory(player));
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);

        SimpleInventory inventory = new SimpleInventory(2);
        inventory.setStack(0, new ItemStack(Items.DIAMOND, 6));
        inventory.setStack(1, new ItemStack(Items.DIAMOND, 64));
        TestScreenHandler handler = new TestScreenHandler();
        handler.addTestSlot(inventory, 0);
        handler.addTestSlot(inventory, 1);
        installClickHandler(handler, interactionManager);

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            Method swap = QuickSort.class.getDeclaredMethod(
                    "swapSlots", ScreenHandler.class, int.class, int.class);
            swap.setAccessible(true);
            Boolean swapped = (Boolean) swap.invoke(null, handler, 0, 1);
            assertThat(swapped)
                    .withFailMessage("cursor=%s, a=%s, b=%s", handler.getCursorStack(), handler.getSlot(0).getStack(), handler.getSlot(1).getStack())
                    .isTrue();
        }

        assertThat(handler.getSlot(0).getStack().getCount()).isEqualTo(64);
        assertThat(handler.getSlot(1).getStack().getCount()).isEqualTo(6);
        assertThat(handler.getCursorStack().isEmpty()).isTrue();
    }

    @Test
    void cursorFillSkipsLockedStackWhenMerging() throws Exception {
        SimpleInventory lockedInventory = new SimpleInventory(1);
        lockedInventory.setStack(0, new ItemStack(Items.STONE, 10));
        SimpleInventory safeInventory = new SimpleInventory(1);
        safeInventory.setStack(0, new ItemStack(Items.STONE, 10));
        TestScreenHandler handler = new TestScreenHandler();
        handler.addTestSlot(lockedInventory, 0);
        handler.addTestSlot(safeInventory, 0);
        handler.setCursorStack(new ItemStack(Items.STONE, 10));

        runCursorFill(handler, handler.getSlot(0));

        assertThat(lockedInventory.getStack(0).getCount()).isEqualTo(10);
        assertThat(safeInventory.getStack(0).getCount()).isEqualTo(20);
        assertThat(handler.getCursorStack().isEmpty()).isTrue();
    }

    @Test
    void cursorFillSkipsLockedEmptySlot() throws Exception {
        SimpleInventory lockedInventory = new SimpleInventory(1);
        SimpleInventory safeInventory = new SimpleInventory(1);
        TestScreenHandler handler = new TestScreenHandler();
        handler.addTestSlot(lockedInventory, 0);
        handler.addTestSlot(safeInventory, 0);
        handler.setCursorStack(new ItemStack(Items.STONE, 10));

        runCursorFill(handler, handler.getSlot(0));

        assertThat(lockedInventory.getStack(0).isEmpty()).isTrue();
        assertThat(safeInventory.getStack(0).getCount()).isEqualTo(10);
        assertThat(handler.getCursorStack().isEmpty()).isTrue();
    }

    @Test
    void pendingClearDoesNotThrowFromInventoryScreen() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        MinecraftClient client = mock(MinecraftClient.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        ClientWorld world = mock(ClientWorld.class);
        ChestBlockEntity target = new ChestBlockEntity(targetPos, Blocks.CHEST.getDefaultState());
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getBlockState(targetPos)).thenReturn(Blocks.CHEST.getDefaultState());
        when(world.getBlockEntity(targetPos)).thenReturn(target);
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);
        setField(client, "world", world);
        setField(client, "crosshairTarget", new BlockHitResult(
                Vec3d.ofCenter(targetPos), Direction.NORTH, targetPos, false));

        TestScreenHandler inventoryHandler = new TestScreenHandler();
        SimpleInventory crafting = new SimpleInventory(9);
        crafting.setStack(0, new ItemStack(Items.DIAMOND_SWORD));
        inventoryHandler.addTestSlot(crafting, 0);
        InventoryScreen screen = mock(InventoryScreen.class);
        doReturn(inventoryHandler).when(screen).getScreenHandler();
        setField(client, "currentScreen", screen);

        QuickClearContainer clear = new QuickClearContainer();
        setPending(clear, true, 1, world, targetPos);
        Method process = QuickClearContainer.class.getDeclaredMethod("processPendingOpen", MinecraftClient.class);
        process.setAccessible(true);
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            process.invoke(clear, client);
        }

        verify(interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat((Boolean) getField(clear, "pendingOpen")).isFalse();
        assertThat(getField(clear, "pendingPos")).isNull();
    }

    @Test
    void pendingClearRejectsScreenForAnotherTarget() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        BlockPos otherPos = new BlockPos(2, 64, 1);
        MinecraftClient client = mock(MinecraftClient.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        ClientWorld world = mock(ClientWorld.class);
        ChestBlockEntity target = new ChestBlockEntity(targetPos, Blocks.CHEST.getDefaultState());
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getBlockState(targetPos)).thenReturn(Blocks.CHEST.getDefaultState());
        when(world.getBlockEntity(targetPos)).thenReturn(target);
        when(world.getBlockState(otherPos)).thenReturn(Blocks.CHEST.getDefaultState());
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);
        setField(client, "world", world);
        setField(client, "crosshairTarget", new BlockHitResult(
                Vec3d.ofCenter(otherPos), Direction.NORTH, otherPos, false));

        GenericContainerScreenHandler handler = GenericContainerScreenHandler.createGeneric9x3(
                1, newPlayerInventory(player));
        handler.getSlot(0).setStack(new ItemStack(Items.STONE, 3));
        installClickHandler(handler, interactionManager);
        HandledScreen<?> screen = mock(HandledScreen.class);
        doReturn(handler).when(screen).getScreenHandler();
        setField(client, "currentScreen", screen);

        QuickClearContainer clear = new QuickClearContainer();
        setPending(clear, true, 1, world, targetPos);
        Method process = QuickClearContainer.class.getDeclaredMethod("processPendingOpen", MinecraftClient.class);
        process.setAccessible(true);
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            process.invoke(clear, client);
        }

        verify(interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat((Boolean) getField(clear, "pendingOpen")).isFalse();
        assertThat(getField(clear, "pendingPos")).isNull();
    }

    @Test
    void pendingClearAcceptsVanillaSingleChestScreen() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        PlayerInventory playerInventory = newPlayerInventory(player);
        ChestBlockEntity target = new ChestBlockEntity(targetPos, Blocks.CHEST.getDefaultState());
        GenericContainerScreenHandler menu = GenericContainerScreenHandler.createGeneric9x3(1, playerInventory);
        assertThat(menu.getInventory()).isNotSameAs(target);
        assertPendingClearRuns(target, Blocks.CHEST.getDefaultState(), menu, 0);
        assertThat(target.getStack(0).isEmpty()).isTrue();
    }

    @Test
    void pendingClearAcceptsVanillaDoubleChestScreen() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        PlayerInventory playerInventory = newPlayerInventory(player);
        ChestBlockEntity target = new ChestBlockEntity(targetPos, Blocks.CHEST.getDefaultState());
        GenericContainerScreenHandler menu = GenericContainerScreenHandler.createGeneric9x6(1, playerInventory);
        assertThat(menu.getInventory()).isNotSameAs(target);
        assertPendingClearRuns(target,
                Blocks.CHEST.getDefaultState().with(ChestBlock.CHEST_TYPE, ChestType.LEFT), menu, 53);
    }

    @Test
    void pendingClearAcceptsVanillaShulkerBoxScreen() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        PlayerInventory playerInventory = newPlayerInventory(player);
        ShulkerBoxBlockEntity target = new ShulkerBoxBlockEntity(
                targetPos, Blocks.SHULKER_BOX.getDefaultState());
        ShulkerBoxScreenHandler menu = new ShulkerBoxScreenHandler(1, playerInventory);
        assertThat(getField(menu, "inventory")).isNotSameAs(target);
        assertPendingClearRuns(target, Blocks.SHULKER_BOX.getDefaultState(), menu, 0);
        assertThat(target.getStack(0).isEmpty()).isTrue();
    }

    @Test
    void pendingClearRejectsMismatchedMenuCapacity() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        MinecraftClient client = mock(MinecraftClient.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        ClientWorld world = mock(ClientWorld.class);
        ChestBlockEntity target = new ChestBlockEntity(targetPos, Blocks.CHEST.getDefaultState());
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getBlockState(targetPos)).thenReturn(Blocks.CHEST.getDefaultState());
        when(world.getBlockEntity(targetPos)).thenReturn(target);
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);
        setField(client, "world", world);
        setField(client, "crosshairTarget", new BlockHitResult(
                Vec3d.ofCenter(targetPos), Direction.NORTH, targetPos, false));

        GenericContainerScreenHandler menu = GenericContainerScreenHandler.createGeneric9x1(
                1, newPlayerInventory(player));
        menu.getSlot(0).setStack(new ItemStack(Items.STONE, 3));
        installClickHandler(menu, interactionManager);
        HandledScreen<?> screen = mock(HandledScreen.class);
        doReturn(menu).when(screen).getScreenHandler();
        setField(client, "currentScreen", screen);

        QuickClearContainer clear = new QuickClearContainer();
        setPending(clear, true, 1, world, targetPos);
        Method process = QuickClearContainer.class.getDeclaredMethod("processPendingOpen", MinecraftClient.class);
        process.setAccessible(true);
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            process.invoke(clear, client);
        }

        verify(interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat(menu.getSlot(0).getStack().isOf(Items.STONE)).isTrue();
        assertThat(getField(clear, "pendingPos")).isNull();
    }

    private static void assertPendingClearRuns(BlockEntity targetEntity,
                                               BlockState targetState,
                                               ScreenHandler menu,
                                               int itemSlot) throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        MinecraftClient client = mock(MinecraftClient.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        ClientWorld world = mock(ClientWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getBlockState(targetPos)).thenReturn(targetState);
        when(world.getBlockEntity(targetPos)).thenReturn(targetEntity);
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);
        setField(client, "world", world);
        setField(client, "crosshairTarget", new BlockHitResult(
                Vec3d.ofCenter(targetPos), Direction.NORTH, targetPos, false));

        menu.getSlot(itemSlot).setStack(new ItemStack(Items.STONE, 3));
        installClickHandler(menu, interactionManager);
        HandledScreen<?> screen = mock(HandledScreen.class);
        doReturn(menu).when(screen).getScreenHandler();
        setField(client, "currentScreen", screen);

        QuickClearContainer clear = new QuickClearContainer();
        setPending(clear, true, 1, world, targetPos);
        Method process = QuickClearContainer.class.getDeclaredMethod("processPendingOpen", MinecraftClient.class);
        process.setAccessible(true);
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            process.invoke(clear, client);
        }

        verify(interactionManager, atLeastOnce()).clickSlot(anyInt(), eq(itemSlot), eq(1),
                eq(SlotActionType.THROW), any());
        assertThat(menu.getSlot(itemSlot).getStack().isEmpty()).isTrue();
        assertThat(getField(clear, "pendingPos")).isNull();
    }

    @Test
    void quickFillDoesNotLetFurnaceFallbackReorderPlayerInventory() throws Exception {
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        ClientWorld world = mock(ClientWorld.class);
        PlayerInventory playerInventory = newPlayerInventory(player);
        mainStacks(playerInventory).set(20, new ItemStack(Items.DIRT, 64));
        when(player.getInventory()).thenReturn(playerInventory);
        stubPlayerWorld(player, world);
        stubFuelRegistryIfPresent(world);
        RecipeManager recipeManager;
        if (hasRecipePropertySet()) {
            Object propertySet = mock(Class.forName("net.minecraft.recipe.RecipePropertySet"));
            recipeManager = (RecipeManager) mock(RecipeManager.class, invocation -> {
                if (invocation.getMethod().getName().equals("getPropertySet")) {
                    return propertySet;
                }
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
        } else {
            recipeManager = mock(RecipeManager.class);
        }
        when(world.getRecipeManager()).thenReturn(recipeManager);

        MinecraftClient client = mock(MinecraftClient.class);
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);
        if (hasRecipePropertySet()) {
            setField(client, "world", world);
        }

        FurnaceScreenHandler furnace = new FurnaceScreenHandler(
                1, playerInventory, new SimpleInventory(3), new ArrayPropertyDelegate(4));
        HandledScreen<?> screen = mock(HandledScreen.class);
        doReturn(furnace).when(screen).getScreenHandler();

        QuickFillContainer fill = new QuickFillContainer();
        Method fillContainer = QuickFillContainer.class.getDeclaredMethod("fillContainer", HandledScreen.class);
        fillContainer.setAccessible(true);
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            fillContainer.invoke(fill, screen);
        }

        verify(interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat(mainStacks(playerInventory).get(20).getCount()).isEqualTo(64);
    }

    @Test
    void quickFillPartiallyFillsWhenSourceStackExceedsContainerCapacity() throws Exception {
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        PlayerInventory playerInventory = newPlayerInventory(player);
        mainStacks(playerInventory).set(0, new ItemStack(Items.STONE, 64));
        when(player.getInventory()).thenReturn(playerInventory);

        SimpleInventory container = new SimpleInventory(27);
        for (int i = 0; i < 26; i++) {
            container.setStack(i, new ItemStack(Items.STONE, 64));
        }
        container.setStack(26, new ItemStack(Items.STONE, 32));
        GenericContainerScreenHandler handler = GenericContainerScreenHandler.createGeneric9x3(
                1, playerInventory, container);
        installClickHandler(handler, interactionManager);
        MinecraftClient client = mock(MinecraftClient.class);
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);
        HandledScreen<?> screen = mock(HandledScreen.class);
        doReturn(handler).when(screen).getScreenHandler();

        QuickFillContainer fill = new QuickFillContainer();
        Method fillContainer = QuickFillContainer.class.getDeclaredMethod("fillContainer", HandledScreen.class);
        fillContainer.setAccessible(true);
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            fillContainer.invoke(fill, screen);
        }

        assertThat(container.getStack(26).getCount()).isEqualTo(64);
        assertThat(mainStacks(playerInventory).get(0).getCount()).isEqualTo(32);
        verify(interactionManager, atLeastOnce()).clickSlot(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void stoppingContinuousTaskDoesNotRecoverFromKnownSourceShulker() throws Exception {
        ShulkerStashFixture fixture = shulkerStashFixture();
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Text.literal("材料不足"));

        assertThat(fixture.targetMenu().getSlot(0).getStack().isOf(Items.SHULKER_BOX)).isTrue();
        assertThat(fixture.sourceMenu().getSlot(0).getStack().isOf(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();

        HandledScreen<?> targetScreen = mock(HandledScreen.class);
        doReturn(fixture.targetMenu()).when(targetScreen).getScreenHandler();
        setField(fixture.client(), "currentScreen", targetScreen);
        setField(fixture.client(), "crosshairTarget", new BlockHitResult(
                Vec3d.ofCenter(fixture.targetPos()), Direction.NORTH, fixture.targetPos(), false));
        installClickHandler(fixture.targetMenu(),
                (ClientPlayerInteractionManager) getField(fixture.client(), "interactionManager"));
        Method processPending = QuickContainerCopy.class.getDeclaredMethod(
                "processPendingStashRecovery", MinecraftClient.class);
        processPending.setAccessible(true);
        processPending.invoke(copy, fixture.client());

        assertThat(fixture.targetMenu().getSlot(0).getStack().isEmpty()).isTrue();
        assertThat(fixture.sourceMenu().getSlot(0).getStack().isOf(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNull();
    }

    @Test
    void backgroundStashRecoveryRejectsReplacedContainerType() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        StashFixture fixture = stashFixture(targetPos, new BlockPos(2, 64, 1));
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Text.literal("材料不足"));
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();

        // 原小箱被拆掉，同坐标换成木桶；两者都是三行菜单，保存的 type 不足以识别。
        when(fixture.client().world.getBlockState(targetPos)).thenReturn(Blocks.BARREL.getDefaultState());
        GenericContainerScreenHandler barrelMenu = GenericContainerScreenHandler.createGeneric9x3(
                9, fixture.playerInventory());
        barrelMenu.getSlot(0).setStack(new ItemStack(Items.SHULKER_BOX));
        ClientPlayerInteractionManager interactionManager =
                (ClientPlayerInteractionManager) getField(fixture.client(), "interactionManager");
        installClickHandler(barrelMenu, interactionManager);
        HandledScreen<?> barrelScreen = mock(HandledScreen.class);
        doReturn(barrelMenu).when(barrelScreen).getScreenHandler();
        setField(fixture.client(), "currentScreen", barrelScreen);
        setField(fixture.client(), "crosshairTarget", new BlockHitResult(
                Vec3d.ofCenter(targetPos), Direction.NORTH, targetPos, false));

        Method processPending = QuickContainerCopy.class.getDeclaredMethod(
                "processPendingStashRecovery", MinecraftClient.class);
        processPending.setAccessible(true);
        processPending.invoke(copy, fixture.client());

        verify(interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat(barrelMenu.getSlot(0).getStack().isOf(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();
    }

    @Test
    void backgroundStashRecoveryRestoresForMatchingTargetType() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        StashFixture fixture = stashFixture(targetPos, new BlockPos(2, 64, 1));
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Text.literal("材料不足"));
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();

        // 重新打开原目标：独立的客户端菜单，不是 pending 创建时的那一份。
        GenericContainerScreenHandler reopenedMenu = GenericContainerScreenHandler.createGeneric9x3(
                7, fixture.playerInventory());
        reopenedMenu.getSlot(0).setStack(new ItemStack(Items.SHULKER_BOX));
        ClientPlayerInteractionManager interactionManager =
                (ClientPlayerInteractionManager) getField(fixture.client(), "interactionManager");
        installClickHandler(reopenedMenu, interactionManager);
        HandledScreen<?> reopenedScreen = mock(HandledScreen.class);
        doReturn(reopenedMenu).when(reopenedScreen).getScreenHandler();
        setField(fixture.client(), "currentScreen", reopenedScreen);
        setField(fixture.client(), "crosshairTarget", new BlockHitResult(
                Vec3d.ofCenter(targetPos), Direction.NORTH, targetPos, false));

        Method processPending = QuickContainerCopy.class.getDeclaredMethod(
                "processPendingStashRecovery", MinecraftClient.class);
        processPending.setAccessible(true);
        processPending.invoke(copy, fixture.client());

        assertThat(reopenedMenu.getSlot(0).getStack().isEmpty()).isTrue();
        assertThat(mainStacks(fixture.playerInventory()).stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.isOf(Items.SHULKER_BOX))).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNull();
    }

    @Test
    void stoppingContinuousTaskDoesNotRestoreFromReplacedContainerType() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        StashFixture fixture = stashFixture(targetPos, targetPos);
        when(fixture.client().world.getBlockState(targetPos)).thenReturn(Blocks.BARREL.getDefaultState());
        GenericContainerScreenHandler barrelMenu = GenericContainerScreenHandler.createGeneric9x3(
                9, fixture.playerInventory());
        barrelMenu.getSlot(0).setStack(new ItemStack(Items.SHULKER_BOX));
        ClientPlayerInteractionManager interactionManager =
                (ClientPlayerInteractionManager) getField(fixture.client(), "interactionManager");
        installClickHandler(barrelMenu, interactionManager);
        HandledScreen<?> barrelScreen = mock(HandledScreen.class);
        doReturn(barrelMenu).when(barrelScreen).getScreenHandler();
        setField(fixture.client(), "currentScreen", barrelScreen);

        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Text.literal("材料不足"));

        verify(interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(), any(), any());
        assertThat(barrelMenu.getSlot(0).getStack().isOf(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();
    }

    @Test
    void pendingStashBlocksSameTargetRefillBeforeClearingOrOverflowDrop() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        StashFixture fixture = stashFixture(targetPos, new BlockPos(2, 64, 1));
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Text.literal("材料不足"));
        Object pending = getStaticField(QuickContainerCopy.class, "pendingStashRecovery");
        assertThat(pending).isNotNull();

        for (int slot = 0; slot < 36; slot++) {
            mainStacks(fixture.playerInventory()).set(slot, new ItemStack(Items.DIRT, 64));
        }
        installContinuousTask(targetPos, "SMALL_CHEST", fixture.client().world);

        try (var minecraft = mockStatic(MinecraftClient.class);
             var configs = mockStatic(QuickCraftConfigs.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(fixture.client());
            configs.when(QuickCraftConfigs::isContainerFillOverflowDropEnabled).thenReturn(true);
            Method fillTarget = QuickContainerCopy.class.getDeclaredMethod(
                    "fillContinuousTarget", MinecraftClient.class);
            fillTarget.setAccessible(true);
            fillTarget.invoke(copy, fixture.client());
        }

        assertThat(fixture.menu().getSlot(0).getStack().isOf(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();
        ClientPlayerInteractionManager interactionManager =
                (ClientPlayerInteractionManager) getField(fixture.client(), "interactionManager");
        verify(interactionManager, atLeastOnce()).clickSlot(anyInt(), anyInt(), anyInt(),
                eq(SlotActionType.PICKUP), any());
        verify(interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(),
                eq(SlotActionType.THROW), any());
        verify(interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(),
                eq(SlotActionType.QUICK_MOVE), any());
    }

    @Test
    void pendingStashBlocksUncertainSamePositionTypeChange() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        GuardEnv env = guardEnv(6);
        when(env.world().getBlockState(targetPos)).thenReturn(
                Blocks.CHEST.getDefaultState().with(ChestBlock.CHEST_TYPE, ChestType.LEFT));
        when(env.world().getBlockEntity(targetPos))
                .thenReturn(new ChestBlockEntity(targetPos, Blocks.CHEST.getDefaultState()));
        env.menu().getSlot(0).setStack(new ItemStack(Items.SHULKER_BOX));
        installPendingStash(targetPos, "SMALL_CHEST", new ItemStack(Items.SHULKER_BOX), env.world());
        installContinuousTask(targetPos, "LARGE_CHEST", env.world());

        invokeFillContinuousTask(env);

        assertThat(env.menu().getSlot(0).getStack().isOf(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();
        verify(env.interactionManager(), never()).clickSlot(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void pendingStashDoesNotBlockUnrelatedContainerFill() throws Exception {
        BlockPos stashedPos = new BlockPos(1, 64, 1);
        BlockPos targetPos = new BlockPos(5, 64, 5);
        GuardEnv env = guardEnv(3);
        when(env.world().getBlockState(stashedPos)).thenReturn(Blocks.CHEST.getDefaultState());
        when(env.world().getBlockState(targetPos)).thenReturn(Blocks.CHEST.getDefaultState());
        when(env.world().getBlockEntity(targetPos))
                .thenReturn(new ChestBlockEntity(targetPos, Blocks.CHEST.getDefaultState()));
        env.menu().getSlot(0).setStack(new ItemStack(Items.DIRT, 3));
        installPendingStash(stashedPos, "SMALL_CHEST", new ItemStack(Items.SHULKER_BOX), env.world());
        installContinuousTask(targetPos, "SMALL_CHEST", env.world());

        invokeFillContinuousTask(env);

        assertThat(env.menu().getSlot(0).getStack().isEmpty()).isTrue();
        assertThat(mainStacks(env.playerInventory()).stream()
                .anyMatch(stack -> stack.isOf(Items.DIRT))).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNotNull();
        verify(env.interactionManager(), atLeastOnce()).clickSlot(anyInt(), anyInt(), anyInt(),
                eq(SlotActionType.QUICK_MOVE), any());
    }

    @Test
    void stoppingContinuousTaskRestoresStashFromVanillaTargetMenu() throws Exception {
        StashFixture fixture = stashFixture(new BlockPos(1, 64, 1), new BlockPos(1, 64, 1));
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), true, null);

        assertThat(fixture.menu().getSlot(0).getStack().isEmpty()).isTrue();
        assertThat(mainStacks(fixture.playerInventory()).stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.isOf(Items.SHULKER_BOX))).isTrue();
        assertThat(fixture.clientSideEntity().getStack(0).isEmpty()).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "continuousTask")).isNull();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNull();
    }

    @Test
    void stoppingContinuousTaskKeepsPendingWhenTargetCannotBeConfirmed() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        BlockPos otherPos = new BlockPos(2, 64, 1);
        StashFixture fixture = stashFixture(targetPos, otherPos);
        QuickContainerCopy copy = new QuickContainerCopy();
        invokeStop(copy, fixture.client(), false, Text.literal("材料不足"));

        assertThat(fixture.menu().getSlot(0).getStack().isOf(Items.SHULKER_BOX)).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "continuousTask")).isNull();
        Object pending = getStaticField(QuickContainerCopy.class, "pendingStashRecovery");
        assertThat(pending).isNotNull();
        Object stash = getField(pending, "stash");
        ItemStack stashed = (ItemStack) getField(stash, "stack");
        assertThat(stashed.isOf(Items.SHULKER_BOX)).isTrue();
        assertThat(stashed.getCount()).isEqualTo(1);

        setField(fixture.client(), "crosshairTarget", new BlockHitResult(
                Vec3d.ofCenter(targetPos), Direction.NORTH, targetPos, false));
        HandledScreen<?> targetScreen = mock(HandledScreen.class);
        doReturn(fixture.menu()).when(targetScreen).getScreenHandler();
        setField(fixture.client(), "currentScreen", targetScreen);
        Method processPending = QuickContainerCopy.class.getDeclaredMethod(
                "processPendingStashRecovery", MinecraftClient.class);
        processPending.setAccessible(true);
        processPending.invoke(copy, fixture.client());

        assertThat(fixture.menu().getSlot(0).getStack().isEmpty()).isTrue();
        assertThat(mainStacks(fixture.playerInventory()).stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.isOf(Items.SHULKER_BOX))).isTrue();
        assertThat(getStaticField(QuickContainerCopy.class, "pendingStashRecovery")).isNull();
    }

    private static ShulkerStashFixture shulkerStashFixture() throws Exception {
        BlockPos targetPos = new BlockPos(1, 64, 1);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        ClientWorld world = mock(ClientWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getBlockState(targetPos)).thenReturn(Blocks.SHULKER_BOX.getDefaultState());
        PlayerInventory playerInventory = newPlayerInventory(player);
        when(player.getInventory()).thenReturn(playerInventory);
        when(player.getAbilities()).thenReturn(new PlayerAbilities());
        ShulkerBoxBlockEntity clientCopy = new ShulkerBoxBlockEntity(
                targetPos, Blocks.SHULKER_BOX.getDefaultState());
        when(world.getBlockEntity(targetPos)).thenReturn(clientCopy);

        ShulkerBoxScreenHandler targetMenu = new ShulkerBoxScreenHandler(7, playerInventory);
        targetMenu.getSlot(0).setStack(new ItemStack(Items.SHULKER_BOX));
        ShulkerBoxScreenHandler sourceMenu = new ShulkerBoxScreenHandler(8, playerInventory);
        sourceMenu.getSlot(0).setStack(new ItemStack(Items.SHULKER_BOX));
        installClickHandler(targetMenu, interactionManager);
        installClickHandler(sourceMenu, interactionManager);
        setField(player, "currentScreenHandler", sourceMenu);

        MinecraftClient client = mock(MinecraftClient.class);
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);
        setField(client, "world", world);
        HandledScreen<?> sourceScreen = mock(HandledScreen.class);
        doReturn(sourceMenu).when(sourceScreen).getScreenHandler();
        setField(client, "currentScreen", sourceScreen);
        setField(client, "crosshairTarget", new BlockHitResult(
                Vec3d.ofCenter(targetPos), Direction.NORTH, targetPos, false));

        Object task = continuousTask(blockTarget(targetPos, "SHULKER_BOX"),
                emptyTemplate("SHULKER_BOX"), world);
        setField(task, "temporaryStash", temporaryStash(0, 0, new ItemStack(Items.SHULKER_BOX)));
        setStage(task, "EXTRACT_SOURCE");
        setField(task, "previousSyncId", 7);
        setField(task, "worldRef", new WeakReference<>(world));
        setStaticField(QuickContainerCopy.class, "continuousTask", task);
        setStaticField(QuickContainerCopy.class, "pendingStashRecovery", null);
        setStaticField(QuickContainerCopy.class, "lastPendingStashScreen", null);
        return new ShulkerStashFixture(client, clientCopy, targetMenu, sourceMenu, targetPos);
    }

    private static StashFixture stashFixture(BlockPos targetPos, BlockPos crosshairPos) throws Exception {
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        ClientWorld world = mock(ClientWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getBlockState(targetPos)).thenReturn(Blocks.CHEST.getDefaultState());
        when(world.getBlockState(crosshairPos)).thenReturn(Blocks.CHEST.getDefaultState());
        PlayerInventory playerInventory = newPlayerInventory(player);
        when(player.getInventory()).thenReturn(playerInventory);
        when(player.getAbilities()).thenReturn(new PlayerAbilities());
        ChestBlockEntity clientCopy = new ChestBlockEntity(targetPos, Blocks.CHEST.getDefaultState());
        when(world.getBlockEntity(targetPos)).thenReturn(clientCopy);

        GenericContainerScreenHandler menu = GenericContainerScreenHandler.createGeneric9x3(1, playerInventory);
        menu.getSlot(0).setStack(new ItemStack(Items.SHULKER_BOX));
        installClickHandler(menu, interactionManager);
        setField(player, "currentScreenHandler", menu);

        MinecraftClient client = mock(MinecraftClient.class);
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);
        setField(client, "world", world);
        HandledScreen<?> screen = mock(HandledScreen.class);
        doReturn(menu).when(screen).getScreenHandler();
        setField(client, "currentScreen", screen);
        setField(client, "crosshairTarget", new BlockHitResult(
                Vec3d.ofCenter(crosshairPos), Direction.NORTH, crosshairPos, false));

        Object task = continuousTask(blockTarget(targetPos, "SMALL_CHEST"),
                emptyTemplate("SMALL_CHEST"), world);
        setField(task, "temporaryStash", temporaryStash(0, 0, new ItemStack(Items.SHULKER_BOX)));
        setStage(task, "FILL_TARGET");
        setField(task, "worldRef", new WeakReference<>(world));
        setStaticField(QuickContainerCopy.class, "continuousTask", task);
        setStaticField(QuickContainerCopy.class, "pendingStashRecovery", null);
        setStaticField(QuickContainerCopy.class, "lastPendingStashScreen", null);
        return new StashFixture(client, playerInventory, menu, clientCopy);
    }

    private static GuardEnv guardEnv(int rows) throws Exception {
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        ClientWorld world = mock(ClientWorld.class);
        PlayerInventory playerInventory = newPlayerInventory(player);
        when(player.getInventory()).thenReturn(playerInventory);
        when(player.getAbilities()).thenReturn(new PlayerAbilities());
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        GenericContainerScreenHandler menu = rows == 6
                ? GenericContainerScreenHandler.createGeneric9x6(1, playerInventory)
                : GenericContainerScreenHandler.createGeneric9x3(1, playerInventory);
        installClickHandler(menu, interactionManager);
        setField(player, "currentScreenHandler", menu);
        MinecraftClient client = mock(MinecraftClient.class);
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);
        setField(client, "world", world);
        HandledScreen<?> screen = mock(HandledScreen.class);
        doReturn(menu).when(screen).getScreenHandler();
        setField(client, "currentScreen", screen);
        return new GuardEnv(client, playerInventory, interactionManager, world, menu);
    }

    private static void invokeFillContinuousTask(GuardEnv env) throws Exception {
        QuickContainerCopy copy = new QuickContainerCopy();
        Method fillTarget = QuickContainerCopy.class.getDeclaredMethod(
                "fillContinuousTarget", MinecraftClient.class);
        fillTarget.setAccessible(true);
        try (var minecraft = mockStatic(MinecraftClient.class);
             var configs = mockStatic(QuickCraftConfigs.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(env.client());
            configs.when(QuickCraftConfigs::isContainerFillOverflowDropEnabled).thenReturn(true);
            fillTarget.invoke(copy, env.client());
        }
    }

    private static void installPendingStash(BlockPos pos, String typeName, ItemStack stack, World world)
            throws Exception {
        setStaticField(QuickContainerCopy.class, "pendingStashRecovery",
                pendingRecovery(blockTarget(pos, typeName), new WeakReference<>(world),
                        temporaryStash(0, 0, stack)));
        setStaticField(QuickContainerCopy.class, "lastPendingStashScreen", null);
    }

    private static void installContinuousTask(BlockPos pos, String typeName, World world) throws Exception {
        Object task = continuousTask(blockTarget(pos, typeName), emptyTemplate(typeName), world);
        setStage(task, "FILL_TARGET");
        setField(task, "worldRef", new WeakReference<>(world));
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
                Vec3d.ofCenter(pos), Direction.NORTH, pos, false), supportedType(typeName));
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

    private static Object continuousTask(Object target, Object template, World world) throws Exception {
        Class<?> taskClass = copyNested("ContinuousFillTask");
        var constructor = taskClass.getDeclaredConstructor(
                copyNested("TargetInteraction"), copyNested("ContinuousTemplate"), RegistryKey.class,
                com.yiyihehe.quickcraft.render.QuickContainerFillStatus.Handle.class);
        constructor.setAccessible(true);
        return constructor.newInstance(target, template, World.OVERWORLD, null);
    }

    private static Object temporaryStash(int templateIndex, int slotId, ItemStack stack) throws Exception {
        Class<?> stashClass = copyNested("TemporaryContainerStash");
        var constructor = stashClass.getDeclaredConstructor(int.class, int.class, ItemStack.class);
        constructor.setAccessible(true);
        return constructor.newInstance(templateIndex, slotId, stack);
    }

    private static Object pendingRecovery(Object target, WeakReference<World> worldRef, Object stash)
            throws Exception {
        Class<?> pendingClass = copyNested("PendingStashRecovery");
        var constructor = pendingClass.getDeclaredConstructor(
                copyNested("TargetInteraction"), WeakReference.class,
                copyNested("TemporaryContainerStash"));
        constructor.setAccessible(true);
        return constructor.newInstance(target, worldRef, stash);
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
                                   MinecraftClient client,
                                   boolean closeScreen,
                                   Text message) throws Exception {
        Method stop = QuickContainerCopy.class.getDeclaredMethod(
                "stopContinuousTask", MinecraftClient.class, boolean.class, Text.class);
        stop.setAccessible(true);
        stop.invoke(copy, client, closeScreen, message);
    }

    private record StashFixture(MinecraftClient client,
                                PlayerInventory playerInventory,
                                GenericContainerScreenHandler menu,
                                ChestBlockEntity clientSideEntity) {
    }

    private record ShulkerStashFixture(MinecraftClient client,
                                       ShulkerBoxBlockEntity clientSideEntity,
                                       ShulkerBoxScreenHandler targetMenu,
                                       ShulkerBoxScreenHandler sourceMenu,
                                       BlockPos targetPos) {
    }

    private record GuardEnv(MinecraftClient client,
                            PlayerInventory playerInventory,
                            ClientPlayerInteractionManager interactionManager,
                            ClientWorld world,
                            GenericContainerScreenHandler menu) {
    }

    private static void runCursorFill(TestScreenHandler handler, Slot lockedSlot) throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        ClientPlayerInteractionManager interactionManager = mock(ClientPlayerInteractionManager.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        when(player.getInventory()).thenReturn(newPlayerInventory(player));
        setField(client, "player", player);
        setField(client, "interactionManager", interactionManager);
        installClickHandler(handler, interactionManager);

        try (var minecraft = mockStatic(MinecraftClient.class);
             var locks = mockStatic(QuickContainerLock.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            locks.when(() -> QuickContainerLock.isLockedSlot(any(ScreenHandler.class), any(Slot.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1) == lockedSlot);
            Method store = QuickSort.class.getDeclaredMethod("storeCursorStackInVisibleSlots", ScreenHandler.class);
            store.setAccessible(true);
            assertThat((Boolean) store.invoke(null, handler)).isTrue();
        }
    }

    private static void installClickHandler(ScreenHandler handler,
                                            ClientPlayerInteractionManager interactionManager) {
        doAnswer(invocation -> {
            if (invocation.getArgument(3) == SlotActionType.PICKUP) {
                simulatePickup(handler, invocation.getArgument(1));
            } else if (invocation.getArgument(3) == SlotActionType.QUICK_MOVE) {
                handler.quickMove(invocation.getArgument(4), invocation.getArgument(1));
            } else if (invocation.getArgument(3) == SlotActionType.THROW) {
                // 原版 THROW 在 button!=0 时整组丢出，测试按这个语义清空槽位。
                handler.getSlot(invocation.getArgument(1)).setStack(ItemStack.EMPTY);
            }
            return null;
        }).when(interactionManager).clickSlot(anyInt(), anyInt(), anyInt(),
                any(SlotActionType.class), any(PlayerEntity.class));
    }

    private static void simulatePickup(ScreenHandler handler, int slotId) {
        Slot slot = handler.getSlot(slotId);
        ItemStack cursor = handler.getCursorStack();
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
            if (handler.getCursorStack().isEmpty()) {
                handler.setCursorStack(ItemStack.EMPTY);
            }
        } else {
            ItemStack slotStack = slot.getStack().copy();
            slot.setStack(cursor.copy());
            handler.setCursorStack(slotStack);
        }
        slot.markDirty();
    }

    private static void setPending(QuickClearContainer clear,
                                   boolean open,
                                   int ticks,
                                   ClientWorld world,
                                   BlockPos pos) throws Exception {
        setField(clear, "pendingOpen", open);
        setField(clear, "pendingTicks", ticks);
        setField(clear, "pendingWorld", world);
        setField(clear, "pendingPos", pos);
    }

    private static void stubPlayerWorld(ClientPlayerEntity player, ClientWorld world) throws Exception {
        for (String methodName : List.of("getWorld", "getEntityWorld")) {
            try {
                Method method = player.getClass().getMethod(methodName);
                when(method.invoke(player)).thenReturn(world);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new AssertionError("ClientPlayerEntity world accessor not found");
    }

    private static void stubFuelRegistryIfPresent(ClientWorld world) throws Exception {
        try {
            Method getFuelRegistry = world.getClass().getMethod("getFuelRegistry");
            Object fuelRegistry = mock(getFuelRegistry.getReturnType());
            when(getFuelRegistry.invoke(world)).thenReturn(fuelRegistry);
        } catch (NoSuchMethodException ignored) {
        }
    }

    private static boolean hasRecipePropertySet() {
        try {
            Class.forName("net.minecraft.recipe.RecipeManager").getMethod(
                    "getPropertySet", Class.forName("net.minecraft.registry.RegistryKey"));
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> mainStacks(PlayerInventory inventory) {
        try {
            Field field = findField(PlayerInventory.class, "main");
            field.setAccessible(true);
            return (List<ItemStack>) field.get(inventory);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static PlayerInventory newPlayerInventory(ClientPlayerEntity player) {
        try {
            return (PlayerInventory) PlayerInventory.class
                    .getConstructor(PlayerEntity.class)
                    .newInstance(player);
        } catch (NoSuchMethodException ignored) {
            try {
                Class<?> equipmentType = Class.forName("net.minecraft.entity.EntityEquipment");
                Object equipment = equipmentType.getConstructor().newInstance();
                return (PlayerInventory) PlayerInventory.class
                        .getConstructor(PlayerEntity.class, equipmentType)
                        .newInstance(player, equipment);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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

    private static final class TestScreenHandler extends ScreenHandler {
        private TestScreenHandler() {
            super(null, 1);
        }

        private void addTestSlot(SimpleInventory inventory, int index) {
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
