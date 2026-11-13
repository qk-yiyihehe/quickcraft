package com.yiyihehe.quickcraft.litematica;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaContainerHighlightTest {

    @Test
    void scanKeepsGoodContainersAndPrunesInactiveFailures() throws Exception {
        var client = org.mockito.Mockito.mock(net.minecraft.client.Minecraft.class);
        var world = org.mockito.Mockito.mock(net.minecraft.client.multiplayer.ClientLevel.class);
        client.level = world;
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        org.mockito.Mockito.when(world.registryAccess()).thenReturn(registries);
        var schematic = org.mockito.Mockito.mock(fi.dy.masa.litematica.schematic.LitematicaSchematic.class);
        var blocks = org.mockito.Mockito.mock(fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer.class);
        org.mockito.Mockito.when(blocks.get(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(Blocks.CHEST.defaultBlockState());
        org.mockito.Mockito.when(schematic.getSubRegionContainer("test")).thenReturn(blocks);
        org.mockito.Mockito.when(schematic.getAreaSize("test")).thenReturn(new BlockPos(2, 1, 1));
        var placement = org.mockito.Mockito.mock(fi.dy.masa.litematica.schematic.placement.SchematicPlacement.class);
        var region = org.mockito.Mockito.mock(fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.class);
        org.mockito.Mockito.when(placement.isRenderingEnabled()).thenReturn(true);
        org.mockito.Mockito.when(placement.getSchematic()).thenReturn(schematic);
        org.mockito.Mockito.when(placement.getAllSubRegionsPlacements()).thenReturn(java.util.List.of(region));
        org.mockito.Mockito.when(placement.getOrigin()).thenReturn(BlockPos.ZERO);
        org.mockito.Mockito.when(placement.getMirror()).thenReturn(net.minecraft.world.level.block.Mirror.NONE);
        org.mockito.Mockito.when(placement.getRotation()).thenReturn(net.minecraft.world.level.block.Rotation.NONE);
        org.mockito.Mockito.when(region.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(region.isRenderingEnabled()).thenReturn(true);
        org.mockito.Mockito.when(region.getName()).thenReturn("test");
        org.mockito.Mockito.when(region.getPos()).thenReturn(BlockPos.ZERO);
        org.mockito.Mockito.when(region.getMirror()).thenReturn(net.minecraft.world.level.block.Mirror.NONE);
        org.mockito.Mockito.when(region.getRotation()).thenReturn(net.minecraft.world.level.block.Rotation.NONE);
        var manager = org.mockito.Mockito.mock(fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager.class);
        org.mockito.Mockito.when(manager.getAllSchematicsPlacements()).thenReturn(java.util.List.of(placement));
        var entries = new java.util.LinkedHashMap<BlockPos, net.minecraft.nbt.CompoundTag>();
        var broken = chestWithStone();
        broken.putBoolean("Broken", true);
        entries.put(BlockPos.ZERO, broken);
        entries.put(new BlockPos(1, 0, 0), chestWithStone());
        var failed = QuickLitematicaContainerHighlight.class.getDeclaredField("FAILED_BLOCK_ENTITIES");
        failed.setAccessible(true);
        var failures = (java.util.Set<?>) failed.get(null);
        failures.clear();
        var method = QuickLitematicaContainerHighlight.class.getDeclaredMethod("collectContainerPositions", net.minecraft.client.Minecraft.class);
        method.setAccessible(true);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var data = org.mockito.Mockito.mockStatic(fi.dy.masa.litematica.data.DataManager.class);
             var access = org.mockito.Mockito.mockStatic(QuickLitematicaDataCompat.class);
             var loading = org.mockito.Mockito.mockStatic(BlockEntity.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            data.when(fi.dy.masa.litematica.data.DataManager::getSchematicPlacementManager).thenReturn(manager);
            org.mockito.Mockito.doReturn(entries).when(schematic).getBlockEntityMapForRegion("test");
            access.when(() -> QuickLitematicaDataCompat.toVanillaNbt(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));
            loading.when(() -> BlockEntity.loadStatic(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(call -> {
                        net.minecraft.nbt.CompoundTag nbt = call.getArgument(2);
                        if (nbt.contains("Broken")) {
                            attempts.incrementAndGet();
                            return null;
                        }
                        return call.callRealMethod();
                    });
            assertThat((java.util.List<?>) method.invoke(null, client)).hasSize(1);
            assertThat((java.util.List<?>) method.invoke(null, client)).hasSize(1);
            assertThat(attempts).hasValue(1);
            assertThat(failures).hasSize(1);
            entries.remove(BlockPos.ZERO);
            assertThat((java.util.List<?>) method.invoke(null, client)).hasSize(1);
            assertThat(failures).isEmpty();
            entries.put(BlockPos.ZERO, chestWithStone());
            assertThat((java.util.List<?>) method.invoke(null, client)).hasSize(2);
        } finally {
            failures.clear();
        }
    }

    private static net.minecraft.nbt.CompoundTag chestWithStone() {
        var chest = new net.minecraft.nbt.CompoundTag();
        chest.putString("id", "minecraft:chest");
        var stack = new net.minecraft.nbt.CompoundTag();
        stack.putByte("Slot", (byte) 0);
        stack.putString("id", "minecraft:stone");
        stack.putInt("count", 1);
        var items = new net.minecraft.nbt.ListTag();
        items.add(stack);
        chest.put("Items", items);
        return chest;
    }
    @Test
    void unresolvedNbtIsNotTreatedAsNonInventoryType() {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        CompoundTag unresolved = new CompoundTag();
        unresolved.putString("id", "minecraft:not_a_registered_block_entity");
        BlockEntity failed = BlockEntity.loadStatic(
                BlockPos.ZERO, Blocks.CHEST.defaultBlockState(), unresolved, registries);
        assertThat(failed).isNull();
        assertThat(QuickLitematicaContainerHighlight.isConfirmedNonInventory(failed)).isFalse();

        CompoundTag chestNbt = new CompoundTag();
        chestNbt.putString("id", "minecraft:chest");
        BlockEntity chest = BlockEntity.loadStatic(
                BlockPos.ZERO, Blocks.CHEST.defaultBlockState(), chestNbt, registries);
        assertThat(chest).isInstanceOf(Container.class);
        assertThat(QuickLitematicaContainerHighlight.isConfirmedNonInventory(chest)).isFalse();

        CompoundTag signNbt = new CompoundTag();
        signNbt.putString("id", "minecraft:sign");
        BlockEntity sign = BlockEntity.loadStatic(
                BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState(), signNbt, registries);
        assertThat(sign).isNotNull();
        assertThat(QuickLitematicaContainerHighlight.isConfirmedNonInventory(sign)).isTrue();
    }
}
