package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.extension.ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickContainerFillStatusTest {
    private static final BlockPos LEFT = new BlockPos(0, 64, 0);
    private static final BlockPos RIGHT = new BlockPos(1, 64, 0);

    private final boolean previous = QuickCraftConfigs.ContainerTools.SHOW_CONTAINER_FILL_STATUS_OUTLINES
            .getBooleanValue();

    @AfterEach
    void restoreConfig() {
        QuickContainerFillStatus.MARKERS.clear();
        QuickCraftConfigs.ContainerTools.SHOW_CONTAINER_FILL_STATUS_OUTLINES.setBooleanValue(previous);
    }

    @Test
    void barrelOutlineExpandsOnlyAfterSixRowMenuAndIsSharedByBothHalves() {
        enableStatus();
        ClientLevel world = mock(ClientLevel.class);
        when(world.getBlockState(LEFT)).thenReturn(
                Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.WEST)
        );
        when(world.getBlockState(RIGHT)).thenReturn(
                Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.EAST)
        );
        Minecraft client = client(world);
        var handler = mock(ChestMenu.class);
        var handle = QuickContainerFillStatus.begin(client, hit(RIGHT));

        when(handler.getRowCount()).thenReturn(3);
        QuickContainerFillStatus.confirmLargeBarrel(client, handle, handler);
        assertThat(QuickContainerFillStatus.MARKERS.keySet()).containsExactly(target(world, hit(RIGHT)));
        assertThat(QuickContainerFillStatus.markerAt(world, hit(LEFT))).isNull();

        when(handler.getRowCount()).thenReturn(6);
        QuickContainerFillStatus.confirmLargeBarrel(client, handle, handler);
        QuickContainerFillStatus.finish(client, handle, 0);
        assertThat(QuickContainerFillStatus.MARKERS.keySet()).extracting(target -> target.box(world)).containsExactly(
                new AABB(0, 64, 0, 2, 65, 1)
        );
        assertThat(QuickContainerFillStatus.markerAt(world, hit(LEFT))).isSameAs(
                QuickContainerFillStatus.markerAt(world, hit(RIGHT))
        );
        assertThat(QuickContainerFillStatus.markerAt(world, hit(LEFT)).status).isEqualTo(
                QuickContainerFillStatus.Status.COMPLETE
        );
    }

    @Test
    void stoppedHalfSplitEndsTheMarkerCreatedAtBegin() {
        enableStatus();
        ClientLevel world = pairWorld();
        Minecraft client = client(world);
        BlockHitResult hit = hit(RIGHT);
        QuickContainerFillStatus.Handle handle = QuickContainerFillStatus.begin(client, hit);
        QuickContainerFillStatus.Target target = target(world, hit);
        when(world.getBlockState(LEFT)).thenReturn(Blocks.AIR.defaultBlockState());
        when(world.getBlockState(RIGHT)).thenReturn(Blocks.CHEST.defaultBlockState());
        assertThat(target(world, hit)).isNotEqualTo(target);

        QuickContainerFillStatus.stop(client, handle, true);

        assertThat(QuickContainerFillStatus.MARKERS.get(target).status).isEqualTo(
                QuickContainerFillStatus.Status.FAILED
        );
    }

    @Test
    void oldHandleCannotEndAReenteredMarker() {
        enableStatus();
        Minecraft client = client(pairWorld());
        BlockHitResult hit = hit(LEFT);
        QuickContainerFillStatus.Handle first = QuickContainerFillStatus.begin(client, hit);
        QuickContainerFillStatus.Handle second = QuickContainerFillStatus.begin(client, hit);

        QuickContainerFillStatus.finish(client, first, 0);
        assertThat(QuickContainerFillStatus.MARKERS.get(target(client.level, hit)).status).isEqualTo(
                QuickContainerFillStatus.Status.FILLING
        );

        QuickContainerFillStatus.finish(client, second, 0);
        assertThat(QuickContainerFillStatus.MARKERS.get(target(client.level, hit)).status).isEqualTo(
                QuickContainerFillStatus.Status.COMPLETE
        );
    }

    @Test
    void worldSwitchClearsMarkersAndOldHandleIsNoOp() throws Exception {
        enableStatus();
        Minecraft client = client(pairWorld());
        BlockHitResult hit = hit(LEFT);
        QuickContainerFillStatus.Handle old = QuickContainerFillStatus.begin(client, hit);
        client.level = mock(ClientLevel.class);
        when(client.level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        Method tick = QuickContainerFillStatus.class.getDeclaredMethod("onClientTick", Minecraft.class);
        tick.setAccessible(true);
        tick.invoke(null, client);

        assertThat(QuickContainerFillStatus.MARKERS).isEmpty();
        QuickContainerFillStatus.finish(client, old, 0);
        assertThat(QuickContainerFillStatus.MARKERS).isEmpty();
    }

    @Test
    void copyTimeoutEndsTheMarkerItCreated() throws Exception {
        enableStatus();
        ClientLevel world = pairWorld();
        Minecraft client = client(world);
        BlockHitResult hit = hit(RIGHT);
        QuickContainerFillStatus.Handle handle = QuickContainerFillStatus.begin(client, hit);
        QuickContainerCopy copy = new QuickContainerCopy();
        setStaticField(QuickContainerCopy.class, "pendingAction", pendingAction("APPLY"));
        setStaticField(QuickContainerCopy.class, "pendingContainerType", null);
        setStaticField(QuickContainerCopy.class, "pendingFillTarget", hit);
        setStaticField(QuickContainerCopy.class, "pendingFillStatus", handle);
        setStaticField(QuickContainerCopy.class, "pendingTicks", 21);
        try (var screenAccess = mockScreenAccess(client)) {
            Method process = QuickContainerCopy.class.getDeclaredMethod("processPendingOpen", Minecraft.class);
            process.setAccessible(true);
            process.invoke(copy, client);
        }

        assertThat(QuickContainerFillStatus.MARKERS.get(target(world, hit)).status).isEqualTo(
                QuickContainerFillStatus.Status.FAILED
        );
        assertThat(QuickContainerFillStatus.MARKERS.get(target(world, hit)).status).isNotEqualTo(
                QuickContainerFillStatus.Status.FILLING
        );
    }

    @Test
    void autofillTimeoutEndsTheMarkerItCreated() throws Exception {
        enableStatus();
        ClientLevel world = pairWorld();
        Minecraft client = client(world);
        BlockHitResult hit = hit(RIGHT);
        QuickContainerFillStatus.Handle handle = QuickContainerFillStatus.begin(client, hit);
        QuickLitematicaContainerAutofill autofill = new QuickLitematicaContainerAutofill();
        setField(autofill, "pendingContainerPos", RIGHT);
        setField(autofill, "pendingFillTarget", hit);
        setField(autofill, "pendingFillStatus", handle);
        setField(autofill, "pendingTicks", 21);
        try (var screenAccess = mockScreenAccess(client)) {
            Method process = QuickLitematicaContainerAutofill.class.getDeclaredMethod(
                    "processPendingOpen",
                    Minecraft.class
            );
            process.setAccessible(true);
            process.invoke(autofill, client);
        }

        assertThat(QuickContainerFillStatus.MARKERS.get(target(world, hit)).status).isEqualTo(
                QuickContainerFillStatus.Status.FAILED
        );
    }

    @Test
    void fillingStateDoesNotExpireByItself() throws Exception {
        enableStatus();
        Minecraft client = client(pairWorld());
        BlockHitResult hit = hit(LEFT);
        QuickContainerFillStatus.begin(client, hit);
        Method tick = QuickContainerFillStatus.class.getDeclaredMethod("onClientTick", Minecraft.class);
        tick.setAccessible(true);
        for (int i = 0; i < 40; i++) {
            tick.invoke(null, client);
        }
        assertThat(QuickContainerFillStatus.MARKERS.get(target(client.level, hit)).status).isEqualTo(
                QuickContainerFillStatus.Status.FILLING
        );
    }

    private static org.mockito.MockedStatic<QuickClientScreenAccess> mockScreenAccess(Minecraft client) {
        var mock = mockStatic(QuickClientScreenAccess.class);
        mock.when(() -> QuickClientScreenAccess.currentScreen(client)).thenReturn(null);
        return mock;
    }

    private static void enableStatus() {
        QuickCraftConfigs.ContainerTools.SHOW_CONTAINER_FILL_STATUS_OUTLINES.setBooleanValue(true);
    }

    private static Minecraft client(ClientLevel world) {
        Minecraft client = mock(Minecraft.class);
        client.level = world;
        client.player = mock(LocalPlayer.class);
        return client;
    }

    private static ClientLevel pairWorld() {
        BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT).setValue(
                ChestBlock.FACING,
                Direction.NORTH
        );
        BlockState right = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT).setValue(
                ChestBlock.FACING,
                Direction.NORTH
        );
        ClientLevel world = mock(ClientLevel.class);
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> {
            BlockPos pos = invocation.getArgument(0);
            return pos.equals(LEFT) ? left : pos.equals(RIGHT) ? right : Blocks.AIR.defaultBlockState();
        });
        return world;
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(
                new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                Direction.NORTH,
                pos,
                false
        );
    }

    private static QuickContainerFillStatus.Target target(ClientLevel world, BlockHitResult hit) {
        return QuickContainerFillStatus.Target.from(world, hit);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object pendingAction(String name) throws Exception {
        Class<?> type = Class.forName("com.yiyihehe.quickcraft.QuickContainerCopy$PendingAction");
        return Enum.valueOf((Class<? extends Enum>) type, name);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setStaticField(Class<?> type, String name, Object value) throws Exception {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
