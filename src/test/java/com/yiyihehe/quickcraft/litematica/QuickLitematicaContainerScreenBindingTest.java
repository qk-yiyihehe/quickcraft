package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import fi.dy.masa.malilib.util.WorldUtils;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class QuickLitematicaContainerScreenBindingTest {
    @BeforeAll
    static void initializeMinecraftRegistries() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void barrelGhostsRequireAnActualBarrelEvenForOtherThreeRowMenus() {
        var barrel = Blocks.BARREL.getDefaultState();
        var expected = new QuickLitematicaContainerVerifier.ExpectedContainer(
                BlockPos.ORIGIN, barrel, null, new SimpleInventory(27), java.util.Set.of());
        var handler = mock(GenericContainerScreenHandler.class);
        when(handler.getRows()).thenReturn(3);

        assertThat(QuickLitematicaContainerScreenBinding.isSupportedHandlerForExpectedContainer(handler, expected, barrel)).isTrue();
        assertThat(QuickLitematicaContainerScreenBinding.isSupportedHandlerForExpectedContainer(
                handler, expected, Blocks.CHEST.getDefaultState())).isFalse();
        assertThat(QuickLitematicaContainerScreenBinding.isSupportedHandlerForExpectedContainer(
                handler, expected, Blocks.ENDER_CHEST.getDefaultState())).isFalse();
    }

    @Test
    void staleScreenAndTemporaryScreenTransitionsPreserveNewBindingAndPendingTarget() throws Exception {
        var client = mock(MinecraftClient.class);
        client.crosshairTarget = null;
        var handler = mock(GenericContainerScreenHandler.class);
        HandledScreen<?> first = mock(HandledScreen.class);
        HandledScreen<?> second = mock(HandledScreen.class);
        HandledScreen<?> third = mock(HandledScreen.class);
        when(first.getScreenHandler()).thenReturn(handler);
        when(second.getScreenHandler()).thenReturn(handler);
        when(third.getScreenHandler()).thenReturn(handler);

        QuickLitematicaContainerScreenBinding.clearCurrentHandledScreenBinding();
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);

            client.currentScreen = first;
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(first);
            assertThat(staticField("currentHandledScreen")).isSameAs(first);

            client.currentScreen = second;
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(second);
            // 旧屏幕的迟到回调不能覆盖新屏幕绑定。
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(first);
            assertThat(staticField("currentHandledScreen")).isSameAs(second);

            setStaticField("pendingContainerPos", BlockPos.ORIGIN);
            setStaticField("currentScreenContainerPos", new BlockPos(1, 2, 3));
            setStaticField("currentScreenContainerInventory", mock(net.minecraft.inventory.Inventory.class));
            setStaticField("currentScreenSlotOverlays", java.util.List.of(mock(QuickLitematicaContainerVerifier.SlotOverlay.class)));
            // 聊天等临时子屏不是关屏，不应丢弃新绑定或下一次打开容器的目标。
            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(mock(Screen.class));
            assertThat(staticField("currentHandledScreen")).isSameAs(second);
            assertThat(staticField("pendingContainerPos")).isEqualTo(BlockPos.ORIGIN);

            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(null);
            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(null);
            assertThat(staticField("currentHandledScreen")).isNull();
            assertThat(staticField("currentScreenContainerPos")).isNull();
            assertThat(staticField("currentScreenContainerInventory")).isNull();
            assertThat(staticField("currentScreenSlotOverlays")).isEqualTo(java.util.List.of());
            assertThat(staticField("pendingContainerPos")).isEqualTo(BlockPos.ORIGIN);

            client.currentScreen = third;
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(third);
            assertThat(staticField("currentHandledScreen")).isSameAs(third);
            assertThat(staticField("currentScreenContainerPos")).isEqualTo(BlockPos.ORIGIN);
            assertThat(staticField("pendingContainerPos")).isNull();
        } finally {
            QuickLitematicaContainerScreenBinding.clearCurrentHandledScreenBinding();
        }
    }

    @Test
    void cancelledPendingDoesNotBindMinecartScreenToTheOldChest() throws Exception {
        BlockPos chestPos = new BlockPos(3, 64, 3);
        ClientWorld world = mock(ClientWorld.class);
        ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        MinecraftClient client = mock(MinecraftClient.class);
        client.world = world;
        client.player = player;
        client.crosshairTarget = null;
        BlockEntity chest = mock(BlockEntity.class, withSettings().extraInterfaces(Inventory.class));
        when(world.getBlockEntity(chestPos)).thenReturn(chest);
        GenericContainerScreenHandler minecartHandler = mock(GenericContainerScreenHandler.class);
        when(minecartHandler.getRows()).thenReturn(3);
        HandledScreen<?> minecartScreen = mock(HandledScreen.class);
        when(minecartScreen.getScreenHandler()).thenReturn(minecartHandler);

        var config = com.yiyihehe.quickcraft.config.QuickCraftConfigs.ProjectionTools.SHOW_LITEMATICA_CONTAINER_VERIFIER;
        boolean previous = config.getBooleanValue();
        config.setBooleanValue(true);
        QuickLitematicaContainerScreenBinding.clearCurrentHandledScreenBinding();
        try (var minecraft = mockStatic(MinecraftClient.class);
             var worldUtils = mockStatic(WorldUtils.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            worldUtils.when(() -> WorldUtils.getBestWorld(client)).thenReturn(world);
            QuickLitematicaContainerScreenBinding.rememberContainerUse(client,
                    new BlockHitResult(Vec3d.ofCenter(chestPos), Direction.NORTH, chestPos, false));
            assertThat(staticField("pendingContainerPos")).isEqualTo(chestPos);

            QuickLitematicaContainerVerifier.clearPendingContainerUses();
            assertThat(staticField("pendingContainerPos")).isNull();

            client.currentScreen = minecartScreen;
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(minecartScreen);
            assertThat(staticField("currentScreenContainerPos")).isNull();
        } finally {
            QuickLitematicaContainerScreenBinding.clearCurrentHandledScreenBinding();
            config.setBooleanValue(previous);
        }
    }

    private static Object staticField(String name) throws Exception {
        Field field = QuickLitematicaContainerScreenBinding.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field field = QuickLitematicaContainerScreenBinding.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
