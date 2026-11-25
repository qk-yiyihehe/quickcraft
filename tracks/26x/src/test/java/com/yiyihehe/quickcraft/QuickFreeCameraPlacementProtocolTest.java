package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
            new Vec3(10.5D, 65.0D, 10.5D),
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
        BlockState state = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH)
                .setValue(FenceGateBlock.OPEN, true).setValue(FenceGateBlock.IN_WALL, false).setValue(
                        FenceGateBlock.POWERED,
                        false
                );
        var method = QuickFreeCameraInteractions.class.getDeclaredMethod("encodeV3PlacementState", BlockState.class);
        method.setAccessible(true);
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
        assertThat(encoded.getLocation().x).isEqualTo(18.25D);
        assertThat(QuickFreeCameraInteractions.encodeFreeCameraPlacementState(client, encoded, true)).isSameAs(encoded);
        setInt("easyPlaceActionDepth", 1);
        assertThat(QuickFreeCameraInteractions.encodeFreeCameraPlacementState(client, HIT, true)).isSameAs(HIT);
    }

    private static Minecraft placementClient() throws Exception {
        ClientLevel world = mock(ClientLevel.class);
        when(world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        LocalPlayer player = mock(LocalPlayer.class, invocation -> {
            if (net.minecraft.world.level.Level.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
                return world;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(new ItemStack(Items.FURNACE));
        when(player.getDirection()).thenReturn(Direction.NORTH);
        Minecraft client = mock(Minecraft.class);
        client.player = player;
        client.level = world;
        setBoolean("cameraFacingApplied", true);
        set("activeBlockUseHand", InteractionHand.MAIN_HAND);
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
