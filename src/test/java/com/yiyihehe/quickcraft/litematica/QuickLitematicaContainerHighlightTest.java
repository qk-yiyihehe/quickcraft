package com.yiyihehe.quickcraft.litematica;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.inventory.Inventory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaContainerHighlightTest {

    @Test
    void scanKeepsGoodContainersAndPrunesInactiveFailures() throws Exception {
        var client = org.mockito.Mockito.mock(net.minecraft.client.MinecraftClient.class);
        var world = org.mockito.Mockito.mock(net.minecraft.client.world.ClientWorld.class);
        client.world = world;
        var registries = DynamicRegistryManager.of(Registries.REGISTRIES);
        org.mockito.Mockito.when(world.getRegistryManager()).thenReturn(registries);
        var schematic = org.mockito.Mockito.mock(fi.dy.masa.litematica.schematic.LitematicaSchematic.class);
        var blocks = org.mockito.Mockito.mock(fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer.class);
        org.mockito.Mockito.when(blocks.get(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(Blocks.CHEST.getDefaultState());
        org.mockito.Mockito.when(schematic.getSubRegionContainer("test")).thenReturn(blocks);
        org.mockito.Mockito.when(schematic.getAreaSize("test")).thenReturn(new BlockPos(2, 1, 1));
        var placement = org.mockito.Mockito.mock(fi.dy.masa.litematica.schematic.placement.SchematicPlacement.class);
        var region = org.mockito.Mockito.mock(fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.class);
        org.mockito.Mockito.when(placement.isRenderingEnabled()).thenReturn(true);
        org.mockito.Mockito.when(placement.getSchematic()).thenReturn(schematic);
        org.mockito.Mockito.when(placement.getAllSubRegionsPlacements()).thenReturn(java.util.List.of(region));
        org.mockito.Mockito.when(placement.getOrigin()).thenReturn(BlockPos.ORIGIN);
        org.mockito.Mockito.when(placement.getMirror()).thenReturn(net.minecraft.util.BlockMirror.NONE);
        org.mockito.Mockito.when(placement.getRotation()).thenReturn(net.minecraft.util.BlockRotation.NONE);
        org.mockito.Mockito.when(region.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(region.isRenderingEnabled()).thenReturn(true);
        org.mockito.Mockito.when(region.getName()).thenReturn("test");
        org.mockito.Mockito.when(region.getPos()).thenReturn(BlockPos.ORIGIN);
        org.mockito.Mockito.when(region.getMirror()).thenReturn(net.minecraft.util.BlockMirror.NONE);
        org.mockito.Mockito.when(region.getRotation()).thenReturn(net.minecraft.util.BlockRotation.NONE);
        var manager = org.mockito.Mockito.mock(fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager.class);
        org.mockito.Mockito.when(manager.getAllSchematicsPlacements()).thenReturn(java.util.List.of(placement));
        var entries = new java.util.LinkedHashMap<BlockPos, NbtCompound>();
        var broken = chestWithStone();
        broken.putBoolean("Broken", true);
        entries.put(BlockPos.ORIGIN, broken);
        entries.put(new BlockPos(1, 0, 0), chestWithStone());
        var failed = QuickLitematicaContainerHighlight.class.getDeclaredField("FAILED_BLOCK_ENTITIES");
        failed.setAccessible(true);
        var failures = (java.util.Set<?>) failed.get(null);
        failures.clear();
        var method = QuickLitematicaContainerHighlight.class.getDeclaredMethod("collectContainerPositions", net.minecraft.client.MinecraftClient.class);
        method.setAccessible(true);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var data = org.mockito.Mockito.mockStatic(fi.dy.masa.litematica.data.DataManager.class);
             var access = org.mockito.Mockito.mockStatic(QuickLitematicaContainerHighlightAccess.class);
             var loading = org.mockito.Mockito.mockStatic(BlockEntity.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            data.when(fi.dy.masa.litematica.data.DataManager::getSchematicPlacementManager).thenReturn(manager);
            access.when(() -> QuickLitematicaContainerHighlightAccess.getBlockEntities(schematic, "test")).thenReturn(entries);
            loading.when(() -> BlockEntity.createFromNbt(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(call -> {
                        NbtCompound nbt = call.getArgument(2);
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
            entries.remove(BlockPos.ORIGIN);
            assertThat((java.util.List<?>) method.invoke(null, client)).hasSize(1);
            assertThat(failures).isEmpty();
            entries.put(BlockPos.ORIGIN, chestWithStone());
            assertThat((java.util.List<?>) method.invoke(null, client)).hasSize(2);
        } finally {
            failures.clear();
        }
    }

    private static NbtCompound chestWithStone() {
        var chest = new NbtCompound();
        chest.putString("id", "minecraft:chest");
        var stack = new NbtCompound();
        stack.putByte("Slot", (byte) 0);
        stack.putString("id", "minecraft:stone");
        stack.putInt("count", 1);
        var items = new net.minecraft.nbt.NbtList();
        items.add(stack);
        chest.put("Items", items);
        return chest;
    }
    @Test
    void unresolvedNbtIsNotTreatedAsNonInventoryType() {
        DynamicRegistryManager registries = DynamicRegistryManager.of(Registries.REGISTRIES);
        NbtCompound unresolved = new NbtCompound();
        unresolved.putString("id", "minecraft:not_a_registered_block_entity");
        BlockEntity failed = BlockEntity.createFromNbt(
                BlockPos.ORIGIN, Blocks.CHEST.getDefaultState(), unresolved, registries);
        assertThat(failed).isNull();
        assertThat(QuickLitematicaContainerHighlight.isConfirmedNonInventory(failed)).isFalse();

        NbtCompound chestNbt = new NbtCompound();
        chestNbt.putString("id", "minecraft:chest");
        BlockEntity chest = BlockEntity.createFromNbt(
                BlockPos.ORIGIN, Blocks.CHEST.getDefaultState(), chestNbt, registries);
        assertThat(chest).isInstanceOf(Inventory.class);
        assertThat(QuickLitematicaContainerHighlight.isConfirmedNonInventory(chest)).isFalse();

        NbtCompound signNbt = new NbtCompound();
        signNbt.putString("id", "minecraft:sign");
        BlockEntity sign = BlockEntity.createFromNbt(
                BlockPos.ORIGIN, Blocks.OAK_SIGN.getDefaultState(), signNbt, registries);
        assertThat(sign).isNotNull();
        assertThat(QuickLitematicaContainerHighlight.isConfirmedNonInventory(sign)).isTrue();
    }
}
