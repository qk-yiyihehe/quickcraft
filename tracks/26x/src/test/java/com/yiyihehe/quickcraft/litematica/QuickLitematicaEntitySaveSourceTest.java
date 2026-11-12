package com.yiyihehe.quickcraft.litematica;

import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEntitySaveSourceTest {
    private static final String REGION = "main";

    @Test
    void temporaryConversionEntityKeepsItsOwnInventory() {
        WorldSchematic active = mock(WorldSchematic.class);
        WorldSchematic temporary = mock(WorldSchematic.class);
        Entity entity = mock(Entity.class);
        when(entity.position()).thenReturn(new Vec3(0.5, 0.0, 0.5));
        CompoundTag output = chestMinecart("minecraft:stone");

        SchematicPlacementManager manager = manager();
        try (var handler = mockStatic(SchematicWorldHandler.class);
             var data = mockStatic(DataManager.class)) {
            handler.when(SchematicWorldHandler::getSchematicWorld).thenReturn(active);
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);

            QuickLitematicaGlobalReplacements.restoreSavedEntityItems(entity, output, temporary);

            assertThat(firstItemId(output)).isEqualTo("minecraft:stone");
        }
    }

    @Test
    void activeProjectionEntityUsesTheProjectionInventory() {
        WorldSchematic active = mock(WorldSchematic.class);
        Entity entity = mock(Entity.class);
        when(entity.position()).thenReturn(new Vec3(0.5, 0.0, 0.5));
        CompoundTag output = chestMinecart("minecraft:stone");

        SchematicPlacementManager manager = manager();
        try (var handler = mockStatic(SchematicWorldHandler.class);
             var data = mockStatic(DataManager.class)) {
            handler.when(SchematicWorldHandler::getSchematicWorld).thenReturn(active);
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);

            QuickLitematicaGlobalReplacements.restoreSavedEntityItems(entity, output, active);

            assertThat(firstItemId(output)).isEqualTo("minecraft:glass");
        }
    }

    private static SchematicPlacementManager manager() {
        SubRegionPlacement sub = mock(SubRegionPlacement.class);
        when(sub.getPos()).thenReturn(BlockPos.ZERO);
        when(sub.getRotation()).thenReturn(Rotation.NONE);
        when(sub.getMirror()).thenReturn(Mirror.NONE);

        LitematicaSchematic schematic = mock(LitematicaSchematic.class);
        when(schematic.getEntityListForRegion(REGION)).thenReturn(List.of(
                new LitematicaSchematic.EntityInfo(new Vec3(0.5, 0.0, 0.5),
                        DataConverterNbt.fromVanillaCompound(chestMinecart("minecraft:glass")))));

        SchematicPlacement placement = mock(SchematicPlacement.class);
        when(placement.isEnabled()).thenReturn(true);
        when(placement.ignoreEntities()).thenReturn(false);
        when(placement.getSchematic()).thenReturn(schematic);
        when(placement.getOrigin()).thenReturn(BlockPos.ZERO);
        when(placement.getRotation()).thenReturn(Rotation.NONE);
        when(placement.getMirror()).thenReturn(Mirror.NONE);
        when(placement.getEnabledRelativeSubRegionPlacements()).thenReturn(ImmutableMap.of(REGION, sub));

        SchematicPlacementManager manager = mock(SchematicPlacementManager.class);
        when(manager.getAllSchematicsPlacements()).thenReturn(List.of(placement));
        return manager;
    }

    private static CompoundTag chestMinecart(String itemId) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:chest_minecart");
        ListTag items = new ListTag();
        CompoundTag stack = new CompoundTag();
        stack.putString("id", itemId);
        stack.putInt("count", 1);
        stack.putByte("Slot", (byte) 0);
        items.add(stack);
        nbt.put("Items", items);
        return nbt;
    }

    private static String firstItemId(CompoundTag nbt) {
        return nbt.getListOrEmpty("Items").getCompoundOrEmpty(0).getStringOr("id", "");
    }
}
