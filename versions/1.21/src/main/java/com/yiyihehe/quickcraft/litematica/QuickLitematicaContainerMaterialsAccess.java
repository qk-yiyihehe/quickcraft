package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.util.Constants;
import fi.dy.masa.malilib.util.ItemType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Path;
import java.util.Map;

final class QuickLitematicaContainerMaterialsAccess {
    private QuickLitematicaContainerMaterialsAccess() {
    }

    static Object createItemKey(ItemStack stack) {
        return new ItemType(stack, true, true);
    }

    static Path getEntryPath(DirectoryEntry entry) {
        return entry.getFullPath().toPath();
    }

    static Object getEntryFileName(DirectoryEntry entry) {
        return entry.getFullPath().getName();
    }

    static ItemStack itemStackFromNbt(RegistryWrapper.WrapperLookup registryLookup, NbtCompound nbt) {
        return ItemStack.fromNbt(registryLookup, nbt).orElse(ItemStack.EMPTY);
    }

    static boolean containsItemsList(NbtCompound nbt) {
        return nbt.contains("Items", Constants.NBT.TAG_LIST);
    }

    static String readString(NbtCompound nbt, String key) {
        return nbt.getString(key);
    }

    static NbtList readCompoundList(NbtCompound nbt, String key) {
        return nbt.getList(key, Constants.NBT.TAG_COMPOUND);
    }

    static NbtCompound readCompound(NbtList list, int index) {
        return list.getCompound(index);
    }

    static Map<BlockPos, NbtCompound> getBlockEntities(LitematicaSchematic schematic, String regionName) {
        return schematic.getBlockEntityMapForRegion(regionName);
    }

    static NbtCompound getEntityNbt(EntityInfo info) {
        return info.nbt;
    }

    static Vec3d getEntityPos(EntityInfo info) {
        return info.posVec;
    }
}
