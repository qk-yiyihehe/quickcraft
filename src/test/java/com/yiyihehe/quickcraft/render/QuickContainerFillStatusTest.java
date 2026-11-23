package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BarrelBlock;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.math.Box;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.extension.ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickContainerFillStatusTest {
    private static final BlockPos LEFT = new BlockPos(0, 64, 0);
    private static final BlockPos RIGHT = new BlockPos(1, 64, 0);

    private final boolean previous = QuickCraftConfigs.ContainerTools.SHOW_CONTAINER_FILL_STATUS_OUTLINES.getBooleanValue();

    @AfterEach
    void restoreConfig() {
        QuickContainerFillStatus.MARKERS.clear();
        QuickCraftConfigs.ContainerTools.SHOW_CONTAINER_FILL_STATUS_OUTLINES.setBooleanValue(previous);
    }

    @Test
    void barrelOutlineExpandsOnlyAfterSixRowMenuAndIsSharedByBothHalves() {
        enableStatus();
        ClientWorld world = mock(ClientWorld.class);
        when(world.getBlockState(LEFT)).thenReturn(Blocks.BARREL.getDefaultState().with(BarrelBlock.FACING, Direction.WEST));
        when(world.getBlockState(RIGHT)).thenReturn(Blocks.BARREL.getDefaultState().with(BarrelBlock.FACING, Direction.EAST));
        MinecraftClient client = client(world);
        var handler = mock(GenericContainerScreenHandler.class);
        var handle = QuickContainerFillStatus.begin(client, hit(RIGHT));

        when(handler.getRows()).thenReturn(3);
        QuickContainerFillStatus.confirmLargeBarrel(client, handle, handler);
        assertThat(QuickContainerFillStatus.MARKERS.keySet()).containsExactly(target(world, hit(RIGHT)));
        assertThat(QuickContainerFillStatus.markerAt(world, hit(LEFT))).isNull();

        when(handler.getRows()).thenReturn(6);
        QuickContainerFillStatus.confirmLargeBarrel(client, handle, handler);
        QuickContainerFillStatus.finish(client, handle, 0);
        assertThat(QuickContainerFillStatus.MARKERS.keySet())
                .extracting(target -> target.box(world))
                .containsExactly(new Box(0, 64, 0, 2, 65, 1));
        assertThat(QuickContainerFillStatus.markerAt(world, hit(LEFT)))
                .isSameAs(QuickContainerFillStatus.markerAt(world, hit(RIGHT)));
        assertThat(QuickContainerFillStatus.markerAt(world, hit(LEFT)).status)
                .isEqualTo(QuickContainerFillStatus.Status.COMPLETE);
    }

    @Test
    void stoppedHalfSplitEndsTheMarkerCreatedAtBegin() {
        enableStatus();
        ClientWorld world = pairWorld();
        MinecraftClient client = client(world);
        BlockHitResult hit = hit(RIGHT);
        QuickContainerFillStatus.Handle handle = QuickContainerFillStatus.begin(client, hit);
        QuickContainerFillStatus.Target target = target(world, hit);
        when(world.getBlockState(LEFT)).thenReturn(Blocks.AIR.getDefaultState());
        when(world.getBlockState(RIGHT)).thenReturn(Blocks.CHEST.getDefaultState());
        assertThat(target(world, hit)).isNotEqualTo(target);

        QuickContainerFillStatus.stop(client, handle, true);

        assertThat(QuickContainerFillStatus.MARKERS.get(target).status).isEqualTo(QuickContainerFillStatus.Status.FAILED);
    }

    @Test
    void oldHandleCannotEndAReenteredMarker() {
        enableStatus();
        MinecraftClient client = client(pairWorld());
        BlockHitResult hit = hit(LEFT);
        QuickContainerFillStatus.Handle first = QuickContainerFillStatus.begin(client, hit);
        QuickContainerFillStatus.Handle second = QuickContainerFillStatus.begin(client, hit);

        QuickContainerFillStatus.finish(client, first, 0);
        assertThat(QuickContainerFillStatus.MARKERS.get(target(client.world, hit)).status)
                .isEqualTo(QuickContainerFillStatus.Status.FILLING);

        QuickContainerFillStatus.finish(client, second, 0);
        assertThat(QuickContainerFillStatus.MARKERS.get(target(client.world, hit)).status)
                .isEqualTo(QuickContainerFillStatus.Status.COMPLETE);
    }

    @Test
    void worldSwitchClearsMarkersAndOldHandleIsNoOp() throws Exception {
        enableStatus();
        MinecraftClient client = client(pairWorld());
        BlockHitResult hit = hit(LEFT);
        QuickContainerFillStatus.Handle old = QuickContainerFillStatus.begin(client, hit);
        client.world = mock(ClientWorld.class);
        when(client.world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.getDefaultState());
        Method tick = QuickContainerFillStatus.class.getDeclaredMethod("onClientTick", MinecraftClient.class);
        tick.setAccessible(true);
        tick.invoke(null, client);

        assertThat(QuickContainerFillStatus.MARKERS).isEmpty();
        QuickContainerFillStatus.finish(client, old, 0);
        assertThat(QuickContainerFillStatus.MARKERS).isEmpty();
    }

    @Test
    void copyTimeoutEndsTheMarkerItCreated() throws Exception {
        enableStatus();
        ClientWorld world = pairWorld();
        MinecraftClient client = client(world);
        BlockHitResult hit = hit(RIGHT);
        QuickContainerFillStatus.Handle handle = QuickContainerFillStatus.begin(client, hit);
        QuickContainerCopy copy = new QuickContainerCopy();
        setStaticField(QuickContainerCopy.class, "pendingAction", pendingAction("APPLY"));
        setStaticField(QuickContainerCopy.class, "pendingContainerType", null);
        setStaticField(QuickContainerCopy.class, "pendingFillTarget", hit);
        setStaticField(QuickContainerCopy.class, "pendingFillStatus", handle);
        setStaticField(QuickContainerCopy.class, "pendingTicks", 21);
        Method process = QuickContainerCopy.class.getDeclaredMethod("processPendingOpen", MinecraftClient.class);
        process.setAccessible(true);
        process.invoke(copy, client);

        assertThat(QuickContainerFillStatus.MARKERS.get(target(world, hit)).status)
                .isEqualTo(QuickContainerFillStatus.Status.FAILED);
        assertThat(QuickContainerFillStatus.MARKERS.get(target(world, hit)).status)
                .isNotEqualTo(QuickContainerFillStatus.Status.FILLING);
    }

    @Test
    void autofillTimeoutEndsTheMarkerItCreated() throws Exception {
        enableStatus();
        ClientWorld world = pairWorld();
        MinecraftClient client = client(world);
        BlockHitResult hit = hit(RIGHT);
        QuickContainerFillStatus.Handle handle = QuickContainerFillStatus.begin(client, hit);
        QuickLitematicaContainerAutofill autofill = new QuickLitematicaContainerAutofill();
        setField(autofill, "pendingContainerPos", RIGHT);
        setField(autofill, "pendingFillTarget", hit);
        setField(autofill, "pendingFillStatus", handle);
        setField(autofill, "pendingTicks", 21);
        Method process = QuickLitematicaContainerAutofill.class.getDeclaredMethod(
                "processPendingOpen", MinecraftClient.class);
        process.setAccessible(true);
        process.invoke(autofill, client);

        assertThat(QuickContainerFillStatus.MARKERS.get(target(world, hit)).status)
                .isEqualTo(QuickContainerFillStatus.Status.FAILED);
    }

    @Test
    void fillingStateDoesNotExpireByItself() throws Exception {
        enableStatus();
        MinecraftClient client = client(pairWorld());
        BlockHitResult hit = hit(LEFT);
        QuickContainerFillStatus.begin(client, hit);
        Method tick = QuickContainerFillStatus.class.getDeclaredMethod("onClientTick", MinecraftClient.class);
        tick.setAccessible(true);
        for (int i = 0; i < 40; i++) {
            tick.invoke(null, client);
        }
        assertThat(QuickContainerFillStatus.MARKERS.get(target(client.world, hit)).status)
                .isEqualTo(QuickContainerFillStatus.Status.FILLING);
    }

    private static void enableStatus() {
        QuickCraftConfigs.ContainerTools.SHOW_CONTAINER_FILL_STATUS_OUTLINES.setBooleanValue(true);
    }

    private static MinecraftClient client(ClientWorld world) {
        MinecraftClient client = mock(MinecraftClient.class);
        client.world = world;
        client.player = mock(ClientPlayerEntity.class);
        return client;
    }

    private static ClientWorld pairWorld() {
        BlockState left = Blocks.CHEST.getDefaultState()
                .with(ChestBlock.CHEST_TYPE, ChestType.LEFT)
                .with(ChestBlock.FACING, Direction.NORTH);
        BlockState right = Blocks.CHEST.getDefaultState()
                .with(ChestBlock.CHEST_TYPE, ChestType.RIGHT)
                .with(ChestBlock.FACING, Direction.NORTH);
        ClientWorld world = mock(ClientWorld.class);
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> {
            BlockPos pos = invocation.getArgument(0);
            return pos.equals(LEFT) ? left : pos.equals(RIGHT) ? right : Blocks.AIR.getDefaultState();
        });
        return world;
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3d.ofCenter(pos), Direction.NORTH, pos, false);
    }

    private static QuickContainerFillStatus.Target target(ClientWorld world, BlockHitResult hit) {
        return QuickContainerFillStatus.Target.from(world, hit);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object pendingAction(String name) throws Exception {
        Class<?> type = Class.forName("com.yiyihehe.quickcraft.QuickContainerCopy$PendingAction");
        return Enum.valueOf((Class<? extends Enum>) type, name);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setStaticField(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
