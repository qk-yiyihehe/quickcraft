package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.util.data.ItemType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class QuickLitematicaContainerMaterialsAccess {
    private QuickLitematicaContainerMaterialsAccess() {
    }

    static Object createItemKey(ItemStack stack) {
        return new ItemType(stack, true, true);
    }

    static Path getEntryPath(DirectoryEntry entry) {
        return entry.getFullPath();
    }

    static Object getEntryFileName(DirectoryEntry entry) {
        return entry.getFullPath().getFileName();
    }

    static ItemStack itemStackFromNbt(RegistryWrapper.WrapperLookup registryLookup, NbtCompound nbt) {
        return ItemStack.OPTIONAL_CODEC
                .parse(registryLookup.getOps(NbtOps.INSTANCE), nbt)
                .result()
                .orElse(ItemStack.EMPTY);
    }

    static boolean containsItemsList(NbtCompound nbt) {
        return nbt.contains("Items");
    }

    static String readString(NbtCompound nbt, String key) {
        return nbt.getString(key, "");
    }

    static NbtList readCompoundList(NbtCompound nbt, String key) {
        return nbt.getListOrEmpty(key);
    }

    static NbtCompound readCompound(NbtList list, int index) {
        return list.getCompoundOrEmpty(index);
    }

    static Map<BlockPos, NbtCompound> getBlockEntities(LitematicaSchematic schematic, String regionName) {
        Map<BlockPos, ?> map = schematic.getBlockEntityMapForRegion(regionName);
        if (map == null) {
            return null;
        }
        Map<BlockPos, NbtCompound> result = new HashMap<>();
        for (Map.Entry<BlockPos, ?> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), QuickLitematicaDataCompat.toVanillaNbt(entry.getValue()));
            }
        }
        return result;
    }

    static NbtCompound getEntityNbt(EntityInfo info) {
        return QuickLitematicaDataCompat.entityNbt(info);
    }

    static Vec3d getEntityPos(EntityInfo info) {
        return QuickLitematicaDataCompat.entityPos(info);
    }
}
