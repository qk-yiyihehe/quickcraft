package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEasyPlaceInteractionsTest {
    @Test
    void chestOpensWhenVanillaUseAndScreenInteractionsAreAllowed() throws Exception {
        Fixture fixture = new Fixture(Blocks.CHEST);

        try (var configs = mockStatic(QuickCraftConfigs.class);
             var freeCamera = mockStatic(QuickFreeCameraInteractions.class);
             var worldUtils = mockStatic(WorldUtils.class);
             var rayTrace = mockStatic(RayTraceUtils.class)) {
            configs.when(QuickCraftConfigs::areEasyPlaceVanillaInteractionsAllowed).thenReturn(true);
            configs.when(QuickCraftConfigs::areEasyPlaceInteractionScreensAllowed).thenReturn(true);
            freeCamera.when(() -> QuickFreeCameraInteractions.getEasyPlaceTraceEntity(
                    fixture.client, fixture.player)).thenReturn(fixture.player);
            worldUtils.when(() -> WorldUtils.getValidBlockRange(fixture.client)).thenReturn(16.0);
            rayTrace.when(() -> RayTraceUtils.getGenericTrace(
                    any(), any(), anyDouble(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(null);

            assertThat(QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(fixture.client)).isTrue();
        }
    }

    @Test
    void chestStaysRestrictedWhenVanillaUseIsDisabled() throws Exception {
        Fixture fixture = new Fixture(Blocks.CHEST);

        try (var configs = mockStatic(QuickCraftConfigs.class)) {
            configs.when(QuickCraftConfigs::areEasyPlaceVanillaInteractionsAllowed).thenReturn(false);

            assertThat(QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(fixture.client)).isFalse();
        }
    }

    @Test
    void ordinaryEasyPlaceTargetContinuesEasyPlace() throws Exception {
        Fixture fixture = new Fixture(Blocks.STONE_BRICKS);

        try (var configs = mockStatic(QuickCraftConfigs.class);
             var freeCamera = mockStatic(QuickFreeCameraInteractions.class);
             var worldUtils = mockStatic(WorldUtils.class);
             var rayTrace = mockStatic(RayTraceUtils.class)) {
            configs.when(QuickCraftConfigs::areEasyPlaceVanillaInteractionsAllowed).thenReturn(true);
            configs.when(QuickCraftConfigs::areEasyPlaceInteractionScreensAllowed).thenReturn(true);
            freeCamera.when(() -> QuickFreeCameraInteractions.getEasyPlaceTraceEntity(
                    fixture.client, fixture.player)).thenReturn(fixture.player);
            worldUtils.when(() -> WorldUtils.getValidBlockRange(fixture.client)).thenReturn(16.0);
            rayTrace.when(() -> RayTraceUtils.getGenericTrace(
                    any(), any(), anyDouble(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(null);

            assertThat(QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(fixture.client)).isFalse();
        }
    }

    private static final class Fixture {
        private final MinecraftClient client = mock(MinecraftClient.class);
        private final ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        private final ClientWorld world = mock(ClientWorld.class);

        private Fixture(net.minecraft.block.Block block) throws Exception {
            BlockPos pos = new BlockPos(1, 64, 1);
            setField(client, "player", player);
            setField(client, "world", world);
            setField(client, "crosshairTarget", new BlockHitResult(
                    Vec3d.ofCenter(pos), Direction.NORTH, pos, false));
            when(world.getBlockState(pos)).thenReturn(block.getDefaultState());
            when(player.getMainHandStack()).thenReturn(new ItemStack(Items.AIR));
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
