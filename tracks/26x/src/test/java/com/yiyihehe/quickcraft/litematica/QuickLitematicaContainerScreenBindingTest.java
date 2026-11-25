package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.malilib.util.WorldUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class QuickLitematicaContainerScreenBindingTest {
    @BeforeAll
    static void initializeMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void barrelGhostsRequireAnActualBarrelEvenForOtherThreeRowMenus() {
        var barrel = Blocks.BARREL.defaultBlockState();
        var expected = new QuickLitematicaContainerVerifier.ExpectedContainer(
                BlockPos.ZERO,
                barrel,
                null,
                new SimpleContainer(27),
                java.util.Set.of()
        );
        var handler = mock(ChestMenu.class);
        when(handler.getRowCount()).thenReturn(3);

        assertThat(
                QuickLitematicaContainerScreenBinding.isSupportedHandlerForExpectedContainer(handler, expected, barrel)
        ).isTrue();
        assertThat(
                QuickLitematicaContainerScreenBinding.isSupportedHandlerForExpectedContainer(
                        handler,
                        expected,
                        Blocks.CHEST.defaultBlockState()
                )
        ).isFalse();
        assertThat(
                QuickLitematicaContainerScreenBinding.isSupportedHandlerForExpectedContainer(
                        handler,
                        expected,
                        Blocks.ENDER_CHEST.defaultBlockState()
                )
        ).isFalse();
    }

    @Test
    void staleScreenAndTemporaryScreenTransitionsPreserveNewBindingAndPendingTarget() throws Exception {
        var client = mock(Minecraft.class);
        client.hitResult = null;
        var handler = mock(HopperMenu.class);
        AbstractContainerScreen<?> first = mock(AbstractContainerScreen.class);
        AbstractContainerScreen<?> second = mock(AbstractContainerScreen.class);
        AbstractContainerScreen<?> third = mock(AbstractContainerScreen.class);
        when(first.getMenu()).thenReturn(handler);
        when(second.getMenu()).thenReturn(handler);
        when(third.getMenu()).thenReturn(handler);
        var currentScreen = new AtomicReference<Screen>(first);

        QuickLitematicaContainerScreenBinding.clearCurrentHandledScreenBinding();
        try (var minecraft = mockStatic(Minecraft.class); var screens = mockStatic(QuickClientScreenAccess.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            screens.when(() -> QuickClientScreenAccess.currentScreen(client)).thenAnswer(
                    invocation -> currentScreen.get()
            );

            QuickLitematicaContainerScreenBinding.bindCurrentScreen(first);
            assertThat(staticField("currentHandledScreen")).isSameAs(first);

            currentScreen.set(second);
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(second);
            // 旧屏幕的迟到回调不能覆盖新屏幕绑定。
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(first);
            assertThat(staticField("currentHandledScreen")).isSameAs(second);

            setStaticField("pendingContainerPos", BlockPos.ZERO);
            setStaticField("currentScreenContainerPos", new BlockPos(1, 2, 3));
            setStaticField("currentScreenContainerInventory", mock(net.minecraft.world.Container.class));
            setStaticField(
                    "currentScreenSlotOverlays",
                    java.util.List.of(mock(QuickLitematicaContainerVerifier.SlotOverlay.class))
            );
            // 聊天等临时子屏不是关屏，不应丢弃新绑定或下一次打开容器的目标。
            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(mock(Screen.class));
            assertThat(staticField("currentHandledScreen")).isSameAs(second);
            assertThat(staticField("pendingContainerPos")).isEqualTo(BlockPos.ZERO);

            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(null);
            QuickLitematicaContainerScreenBinding.reconcileCurrentScreen(null);
            assertThat(staticField("currentHandledScreen")).isNull();
            assertThat(staticField("currentScreenContainerPos")).isNull();
            assertThat(staticField("currentScreenContainerInventory")).isNull();
            assertThat(staticField("currentScreenSlotOverlays")).isEqualTo(java.util.List.of());
            assertThat(staticField("pendingContainerPos")).isEqualTo(BlockPos.ZERO);

            currentScreen.set(third);
            QuickLitematicaContainerScreenBinding.bindCurrentScreen(third);
            assertThat(staticField("currentHandledScreen")).isSameAs(third);
            assertThat(staticField("currentScreenContainerPos")).isEqualTo(BlockPos.ZERO);
            assertThat(staticField("pendingContainerPos")).isNull();
        } finally {
            QuickLitematicaContainerScreenBinding.clearCurrentHandledScreenBinding();
        }
    }

    @Test
    void cancelledPendingDoesNotBindMinecartScreenToTheOldChest() throws Exception {
        BlockPos chestPos = new BlockPos(3, 64, 3);
        ClientLevel world = mock(ClientLevel.class);
        LocalPlayer player = mock(LocalPlayer.class);
        Minecraft client = mock(Minecraft.class);
        client.level = world;
        client.player = player;
        client.hitResult = null;
        BlockEntity chest = mock(
                BlockEntity.class,
                org.mockito.Mockito.withSettings().extraInterfaces(Container.class)
        );
        when(world.getBlockEntity(chestPos)).thenReturn(chest);
        ChestMenu minecartHandler = ChestMenu.threeRows(3, new Inventory(player, new EntityEquipment()));
        AbstractContainerScreen<?> minecartScreen = mock(AbstractContainerScreen.class);
        when(minecartScreen.getMenu()).thenReturn(minecartHandler);
        var config = QuickCraftConfigs.ProjectionTools.SHOW_LITEMATICA_CONTAINER_VERIFIER;
        boolean previous = config.getBooleanValue();
        config.setBooleanValue(true);
        QuickLitematicaContainerScreenBinding.clearCurrentHandledScreenBinding();
        var currentScreen = new AtomicReference<Screen>(null);
        try (var minecraft = mockStatic(Minecraft.class);
                var screens = mockStatic(QuickClientScreenAccess.class);
                var worldUtils = mockStatic(WorldUtils.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            screens.when(() -> QuickClientScreenAccess.currentScreen(client)).thenAnswer(
                    invocation -> currentScreen.get()
            );
            worldUtils.when(() -> WorldUtils.getBestWorld(client)).thenReturn(world);
            QuickLitematicaContainerScreenBinding.rememberContainerUse(
                    client,
                    new BlockHitResult(new Vec3(3.5, 64.5, 3.5), Direction.NORTH, chestPos, false)
            );
            assertThat(staticField("pendingContainerPos")).isEqualTo(chestPos);

            QuickLitematicaContainerVerifier.clearPendingContainerUses();
            assertThat(staticField("pendingContainerPos")).isNull();

            currentScreen.set(minecartScreen);
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
