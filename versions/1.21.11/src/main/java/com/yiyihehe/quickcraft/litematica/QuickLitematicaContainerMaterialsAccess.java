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
        return QuickLitematicaSchematicData.decodeItem(nbt, registryLookup);
    }

    static ItemStack decodeCurrentItem(RegistryWrapper.WrapperLookup registryLookup, NbtCompound nbt) {
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

    static Path schematicPath(LitematicaSchematic schematic) {
        return schematic.getFile() == null ? null : schematic.getFile();
    }
    static LitematicaSchematic copySchematic(LitematicaSchematic schematic) {
        Path path = schematicPath(schematic);
        return new LitematicaSchematic(path == null ? Path.of("memory.litematic") : path,
                schematic.writeToNBT().copy(), fi.dy.masa.litematica.util.FileType.LITEMATICA_SCHEMATIC);
    }
    static byte readByte(NbtCompound nbt, String key) { return nbt.getByte(key, (byte) 0); }
    static NbtCompound parseNbt(String value) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return net.minecraft.nbt.StringNbtReader.readCompound(value);
    }
    static void setBlockEntities(LitematicaSchematic schematic, String region, Map<BlockPos, NbtCompound> values) {
        QuickLitematicaDataCompat.setBlockEntities(schematic, region, values);
    }
    static void setEntityNbt(java.util.List<EntityInfo> entities, int index, NbtCompound nbt) {
        entities.set(index, QuickLitematicaDataCompat.withEntityNbt(entities.get(index), nbt));
    }
    static NbtCompound blockEntityNbt(net.minecraft.block.entity.BlockEntity entity, RegistryWrapper.WrapperLookup lookup) {
        return entity.createNbtWithIdentifyingData(lookup);
    }
    static void readBlockEntity(net.minecraft.block.entity.BlockEntity entity, NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        entity.read(net.minecraft.storage.NbtReadView.create(net.minecraft.util.ErrorReporter.EMPTY, lookup, nbt));
    }
    static Vec3d entityPosition(net.minecraft.entity.Entity entity) { return entity.getEntityPos(); }
}
