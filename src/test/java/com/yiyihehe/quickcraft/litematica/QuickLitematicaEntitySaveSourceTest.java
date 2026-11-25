package com.yiyihehe.quickcraft.litematica;

import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEntitySaveSourceTest {
    private static final String REGION = "main";

    @Test
    void temporaryConversionEntityKeepsItsOwnInventory() throws Exception {
        WorldSchematic active = mock(WorldSchematic.class);
        WorldSchematic temporary = mock(WorldSchematic.class);
        Entity entity = mock(Entity.class);
        NbtCompound output = chestMinecart("minecraft:stone");

        SchematicPlacementManager manager = manager();
        try (var handler = mockStatic(SchematicWorldHandler.class);
                var data = mockStatic(DataManager.class);
                var access = mockStatic(QuickLitematicaContainerMaterialsAccess.class, CALLS_REAL_METHODS)) {
            handler.when(SchematicWorldHandler::getSchematicWorld).thenReturn(active);
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
            access.when(() -> QuickLitematicaContainerMaterialsAccess.entityPosition(any())).thenReturn(
                    new Vec3d(0.5, 0.0, 0.5)
            );

            QuickLitematicaGlobalReplacements.restoreSavedEntityItems(entity, output, temporary);

            assertThat(firstItemId(output)).isEqualTo("minecraft:stone");
        }
    }

    @Test
    void activeProjectionEntityUsesTheProjectionInventory() throws Exception {
        WorldSchematic active = mock(WorldSchematic.class);
        Entity entity = mock(Entity.class);
        NbtCompound output = chestMinecart("minecraft:stone");

        SchematicPlacementManager manager = manager();
        try (var handler = mockStatic(SchematicWorldHandler.class);
                var data = mockStatic(DataManager.class);
                var access = mockStatic(QuickLitematicaContainerMaterialsAccess.class, CALLS_REAL_METHODS)) {
            handler.when(SchematicWorldHandler::getSchematicWorld).thenReturn(active);
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
            access.when(() -> QuickLitematicaContainerMaterialsAccess.entityPosition(any())).thenReturn(
                    new Vec3d(0.5, 0.0, 0.5)
            );

            QuickLitematicaGlobalReplacements.restoreSavedEntityItems(entity, output, active);

            assertThat(firstItemId(output)).isEqualTo("minecraft:glass");
        }
    }

    private static SchematicPlacementManager manager() throws Exception {
        SubRegionPlacement sub = mock(SubRegionPlacement.class);
        when(sub.getPos()).thenReturn(BlockPos.ORIGIN);
        when(sub.getRotation()).thenReturn(BlockRotation.NONE);
        when(sub.getMirror()).thenReturn(BlockMirror.NONE);

        LitematicaSchematic schematic = mock(LitematicaSchematic.class);
        when(schematic.getEntityListForRegion(REGION)).thenReturn(
                List.of(entityInfo(chestMinecart("minecraft:glass")))
        );

        SchematicPlacement placement = mock(SchematicPlacement.class);
        when(placement.isEnabled()).thenReturn(true);
        when(placement.ignoreEntities()).thenReturn(false);
        when(placement.getSchematic()).thenReturn(schematic);
        when(placement.getOrigin()).thenReturn(BlockPos.ORIGIN);
        when(placement.getRotation()).thenReturn(BlockRotation.NONE);
        when(placement.getMirror()).thenReturn(BlockMirror.NONE);
        when(placement.getEnabledRelativeSubRegionPlacements()).thenReturn(ImmutableMap.of(REGION, sub));

        SchematicPlacementManager manager = mock(SchematicPlacementManager.class);
        when(manager.getAllSchematicsPlacements()).thenReturn(List.of(placement));
        return manager;
    }

    private static LitematicaSchematic.EntityInfo entityInfo(NbtCompound nbt) throws Exception {
        for (var constructor : LitematicaSchematic.EntityInfo.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 2 || parameters[0] != Vec3d.class) {
                continue;
            }
            Object data = nbt;
            if (parameters[1] != NbtCompound.class) {
                Class<?> converter = Class.forName("fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt");
                data = converter.getMethod("fromVanillaCompound", NbtCompound.class).invoke(null, nbt);
            }
            return (LitematicaSchematic.EntityInfo) constructor.newInstance(new Vec3d(0.5, 0.0, 0.5), data);
        }
        throw new IllegalStateException("Unsupported EntityInfo constructor");
    }

    private static NbtCompound chestMinecart(String itemId) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:chest_minecart");
        NbtList items = new NbtList();
        NbtCompound stack = new NbtCompound();
        stack.putString("id", itemId);
        stack.putInt("count", 1);
        stack.putByte("Slot", (byte) 0);
        items.add(stack);
        nbt.put("Items", items);
        return nbt;
    }

    private static String firstItemId(NbtCompound nbt) {
        return QuickLitematicaContainerMaterialsAccess.readString(
                QuickLitematicaContainerMaterialsAccess.readCompound(
                        QuickLitematicaContainerMaterialsAccess.readCompoundList(nbt, "Items"),
                        0
                ),
                "id"
        );
    }
}
