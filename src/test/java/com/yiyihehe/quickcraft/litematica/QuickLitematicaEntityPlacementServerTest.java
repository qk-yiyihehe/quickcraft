package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEntityPlacementServerTest {
    @Test
    void spectatorIsDeniedWithoutPaymentOrSpawn() {
        request(true, true, false, false);
    }

    @Test
    void adventureIsDeniedWithoutPaymentOrSpawn() {
        request(false, false, false, false);
    }

    @Test
    void survivalPaysAndSpawns() {
        request(false, true, false, false);
    }

    @Test
    void creativeBypassKeepsPaymentAndSpawns() {
        request(false, true, true, true);
    }

    @Test
    void creativeWithoutBypassStillPays() {
        request(false, true, true, false);
    }

    private void request(boolean spectator, boolean mayBuild, boolean creative, boolean bypass) {
        ServerPlayerEntity player = mock(ServerPlayerEntity.class);
        ServerWorld world = mock(ServerWorld.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        PlayerAbilities abilities = new PlayerAbilities();
        abilities.allowModifyWorld = mayBuild;
        ItemStack payment = new ItemStack(Items.ARMOR_STAND);
        when(player.getUuid()).thenReturn(UUID.randomUUID());
        when(player.getAbilities()).thenReturn(abilities);
        when(player.isSpectator()).thenReturn(spectator);
        when(player.isCreative()).thenReturn(creative);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEyePos()).thenReturn(new Vec3d(0.5, 1, 0.5));
        when(player.getBlockInteractionRange()).thenReturn(5.0);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.isChunkLoaded(any(BlockPos.class))).thenReturn(true);
        WorldBorder border = mock(WorldBorder.class);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.contains(any(Box.class))).thenReturn(true);
        Entity entity = mock(Entity.class);
        when(entity.getBlockPos()).thenReturn(BlockPos.ORIGIN);
        when(entity.getBoundingBox()).thenReturn(new Box(0, 0, 0, 1, 2, 1));
        when(entity.getPassengerList()).thenReturn(List.of());
        when(entity.getUuid()).thenReturn(UUID.randomUUID());

        // 只替换世界/实体工厂边界；权限、材料计算、扣料和结果分支由 handleRequest 执行。
        try (var access = mockStatic(QuickLitematicaEntityPlacementAccess.class, CALLS_REAL_METHODS);
                var config = mockStatic(QuickCraftConfigs.class);
                var network = mockStatic(ServerPlayNetworking.class)) {
            access.when(() -> QuickLitematicaEntityPlacementAccess.getPlayerServerWorld(player)).thenReturn(world);
            access.when(() -> QuickLitematicaEntityPlacementAccess.canModifyAt(eq(world), eq(player), any()))
                    .thenReturn(true);
            access.when(() -> QuickLitematicaEntityPlacementAccess.getMainStacks(inventory)).thenReturn(
                    List.of(payment)
            );
            access.when(() -> QuickLitematicaEntityPlacementAccess.createEntity(any(), eq(world))).thenReturn(entity);
            access.when(() -> QuickLitematicaEntityPlacementAccess.getEntityPos(entity)).thenReturn(Vec3d.ZERO);
            access.when(() -> QuickLitematicaEntityPlacementAccess.readEntityData(eq(entity), eq(world), any()))
                    .thenAnswer(call -> null);
            when(world.spawnNewEntityAndPassengers(entity)).thenReturn(true);
            config.when(QuickCraftConfigs::isEasyPlaceEntitiesEnabled).thenReturn(true);
            NbtCompound nbt = new NbtCompound();
            nbt.putString("id", "minecraft:armor_stand");
            var payload = new QuickLitematicaEntityPlacementPayloads.RequestPayload(
                    QuickLitematicaEntityPlacementServer.ensureSession(player),
                    1L,
                    Identifier.of("minecraft", "overworld"),
                    new Vec3d(0.5, 0, 0.5),
                    Identifier.of("minecraft", "armor_stand"),
                    "r",
                    0,
                    0,
                    0,
                    Vec3d.ZERO,
                    bypass,
                    nbt
            );
            try {
                QuickLitematicaEntityPlacementServer.handleRequest(player, payload);
                boolean allowed = !spectator && mayBuild;
                network.verify(
                        () -> ServerPlayNetworking.send(
                                eq(player),
                                argThat(
                                        value -> value instanceof QuickLitematicaEntityPlacementPayloads.ResultPayload result
                                                && result.status().equals(allowed ? "SUCCESS" : "PERMISSION_DENIED")
                                )
                        )
                );
                assertThat(payment.getCount()).isEqualTo(allowed && !(creative && bypass) ? 0 : 1);
                verify(world, times(allowed ? 1 : 0)).spawnNewEntityAndPassengers(entity);
                if (!allowed) {
                    access.verify(() -> QuickLitematicaEntityPlacementAccess.createEntity(any(), any()), never());
                }
            } finally {
                QuickLitematicaEntityPlacementServer.clear(player);
            }
        }
    }
}
