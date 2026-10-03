package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
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

    static QuickLitematicaContainerMaterials.ItemKey createItemKey(ItemStack stack) {
        return new NativeItemKey(stack);
    }

    private static final class NativeItemKey extends QuickLitematicaContainerMaterials.ItemKey {
        private final ItemType type;

        private NativeItemKey(ItemStack stack) {
            this.type = new ItemType(stack, true, true);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NativeItemKey key && this.type.equals(key.type);
        }

        @Override
        public int hashCode() {
            return this.type.hashCode();
        }

        @Override
        public String toString() {
            return this.type.toString();
        }
    }

    static Path getEntryPath(DirectoryEntry entry) {
        return entry.getFullPath();
    }

    static String getEntryFileName(DirectoryEntry entry) {
        return entry.getFullPath().getFileName().toString();
    }

    static ItemStack itemStackFromNbt(RegistryWrapper.WrapperLookup registryLookup, NbtCompound nbt) {
        return ItemStack.fromNbt(registryLookup, nbt).orElse(ItemStack.EMPTY);
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
        return schematic.getBlockEntityMapForRegion(regionName);
    }

    static NbtCompound getEntityNbt(EntityInfo info) {
        return info.nbt;
    }

    static Vec3d getEntityPos(EntityInfo info) {
        return info.posVec;
    }
}
