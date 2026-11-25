package com.yiyihehe.quickcraft;

import net.minecraft.block.Blocks;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BUG-26：只有真实 V3 协议条件满足时才改写命中坐标。 */
@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickFreeCameraPlacementProtocolTest {
    private static final BlockPos POS = new BlockPos(10, 64, 10);
    private static final BlockHitResult HIT = new BlockHitResult(
            new Vec3d(10.5D, 65.0D, 10.5D),
            Direction.UP,
            POS,
            false
    );

    @AfterEach
    void resetFreeCameraState() throws Exception {
        setBoolean("cameraFacingApplied", false);
        setInt("cameraFacingDepth", 0);
        set("activeBlockUseHand", null);
        setInt("easyPlaceActionDepth", 0);
    }

    @Test
    void unsupportedV3KeepsOrdinaryPlacementCoordinates() throws Exception {
        var client = placementClient();
        assertThat(QuickFreeCameraInteractions.encodeFreeCameraPlacementState(client, HIT, false)).isSameAs(HIT);
    }

    @Test
    void v3EncodesOnlyWhitelistedProperties() throws Exception {
        var state = Blocks.OAK_FENCE_GATE.getDefaultState().with(FenceGateBlock.FACING, Direction.NORTH).with(
                FenceGateBlock.OPEN,
                true
        ).with(FenceGateBlock.IN_WALL, false).with(FenceGateBlock.POWERED, false);
        var method = QuickFreeCameraInteractions.class.getDeclaredMethod(
                "encodeV3PlacementState",
                net.minecraft.block.BlockState.class
        );
        method.setAccessible(true);
        // NORTH 方向 2<<1，OPEN=true 的位在 4；IN_WALL/POWERED 不属于 V3 白名单。
        assertThat((Integer) method.invoke(null, state)).isEqualTo(20);
    }

    @Test
    void missingOptionalProtocolProviderKeepsThePublicEntryUnencoded() throws Exception {
        var client = placementClient();
        assertThatThrownBy(() -> Class.forName("fi.dy.masa.tweakeroo.tweaks.PlacementHandler")).isInstanceOf(
                ClassNotFoundException.class
        );
        assertThat(QuickFreeCameraInteractions.encodeFreeCameraPlacementState(client, HIT)).isSameAs(HIT);
    }

    @Test
    void enabledV3UsesThePlacementStateOnceAndHonorsEasyPlaceOwnership() throws Exception {
        var client = placementClient();
        BlockHitResult encoded = QuickFreeCameraInteractions.encodeFreeCameraPlacementState(client, HIT, true);
        assertThat(encoded).isNotSameAs(HIT);
        assertThat(encoded.getPos().x).isEqualTo(18.25D);
        assertThat(QuickFreeCameraInteractions.encodeFreeCameraPlacementState(client, encoded, true)).isSameAs(encoded);
        setInt("easyPlaceActionDepth", 1);
        assertThat(QuickFreeCameraInteractions.encodeFreeCameraPlacementState(client, HIT, true)).isSameAs(HIT);
    }

    private static MinecraftClient placementClient() throws Exception {
        ClientWorld world = mock(ClientWorld.class);
        when(world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.getDefaultState());
        ClientPlayerEntity player = mock(ClientPlayerEntity.class, invocation -> {
            if (net.minecraft.world.World.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
                return world;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        when(player.getStackInHand(Hand.MAIN_HAND)).thenReturn(new ItemStack(Items.FURNACE));
        when(player.getHorizontalFacing()).thenReturn(Direction.NORTH);
        MinecraftClient client = mock(MinecraftClient.class);
        client.player = player;
        client.world = world;
        setBoolean("cameraFacingApplied", true);
        set("activeBlockUseHand", Hand.MAIN_HAND);
        return client;
    }

    private static void set(String name, Object value) throws Exception {
        Field field = QuickFreeCameraInteractions.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void setBoolean(String name, boolean value) throws Exception {
        Field field = QuickFreeCameraInteractions.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(null, value);
    }

    private static void setInt(String name, int value) throws Exception {
        Field field = QuickFreeCameraInteractions.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(null, value);
    }
}
