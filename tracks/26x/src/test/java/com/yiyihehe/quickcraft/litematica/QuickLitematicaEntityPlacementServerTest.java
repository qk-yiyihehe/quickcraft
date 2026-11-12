package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEntityPlacementServerTest {
    @Test void spectatorIsDeniedWithoutPaymentOrSpawn() { request(true, true, false, false); }
    @Test void adventureIsDeniedWithoutPaymentOrSpawn() { request(false, false, false, false); }
    @Test void survivalPaysAndSpawns() { request(false, true, false, false); }
    @Test void creativeBypassKeepsPaymentAndSpawns() { request(false, true, true, true); }
    @Test void creativeWithoutBypassStillPays() { request(false, true, true, false); }

    private void request(boolean spectator, boolean mayBuild, boolean creative, boolean bypass) {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel world = mock(ServerLevel.class);
        Inventory inventory = mock(Inventory.class);
        Abilities abilities = new Abilities();
        abilities.mayBuild = mayBuild;
        ItemStack payment = new ItemStack(Items.ARMOR_STAND);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getAbilities()).thenReturn(abilities);
        when(player.isSpectator()).thenReturn(spectator);
        when(player.isCreative()).thenReturn(creative);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEyePosition()).thenReturn(new Vec3(0.5, 1, 0.5));
        when(player.blockInteractionRange()).thenReturn(5.0);
        when(world.dimension()).thenReturn(Level.OVERWORLD);
        when(world.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
        when(world.hasChunkAt(any(BlockPos.class))).thenReturn(true);
        WorldBorder border = mock(WorldBorder.class);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.isWithinBounds(any(AABB.class))).thenReturn(true);


        // 只替换世界/实体工厂边界；权限、材料计算、扣料和结果分支由 handleRequest 执行。
        when(player.level()).thenReturn(world);
        when(world.registryAccess()).thenReturn(net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
        when(world.mayInteract(eq(player), any())).thenReturn(true);
        when(inventory.getNonEquipmentItems()).thenReturn(net.minecraft.core.NonNullList.of(ItemStack.EMPTY, payment));
        when(world.tryAddFreshEntityWithPassengers(any())).thenReturn(true);
        try (var construction = mockConstruction(ArmorStand.class, (entity, context) -> {
                 when(entity.position()).thenReturn(Vec3.ZERO);
                 when(entity.blockPosition()).thenReturn(BlockPos.ZERO);
                 when(entity.getBoundingBox()).thenReturn(new AABB(0, 0, 0, 1, 2, 1));
                 when(entity.getPassengers()).thenReturn(List.of());
                 when(entity.getUUID()).thenReturn(UUID.randomUUID());
             });
             var config = mockStatic(QuickCraftConfigs.class);
             var network = mockStatic(ServerPlayNetworking.class)) {
            config.when(QuickCraftConfigs::isEasyPlaceEntitiesEnabled).thenReturn(true);
            CompoundTag nbt = new CompoundTag();
            nbt.putString("id", "minecraft:armor_stand");
            var payload = new QuickLitematicaEntityPlacementPayloads.RequestPayload(
                    QuickLitematicaEntityPlacementServer.ensureSession(player), 1L,
                    Identifier.fromNamespaceAndPath("minecraft", "overworld"), new Vec3(0.5, 0, 0.5),
                    Identifier.fromNamespaceAndPath("minecraft", "armor_stand"), "r", 0, 0, 0, Vec3.ZERO, bypass, nbt);
            try {
                QuickLitematicaEntityPlacementServer.handleRequest(player, payload);
                boolean allowed = !spectator && mayBuild;
                network.verify(() -> ServerPlayNetworking.send(eq(player), argThat(
                        value -> value instanceof QuickLitematicaEntityPlacementPayloads.ResultPayload result
                                && result.status().equals(allowed ? "SUCCESS" : "PERMISSION_DENIED"))));
                assertThat(payment.getCount()).isEqualTo(allowed && !(creative && bypass) ? 0 : 1);
                verify(world, times(allowed ? 1 : 0)).tryAddFreshEntityWithPassengers(any());
                assertThat(construction.constructed()).hasSize(allowed ? 1 : 0);
            } finally {
                QuickLitematicaEntityPlacementServer.clear(player);
            }
        }
    }
}

