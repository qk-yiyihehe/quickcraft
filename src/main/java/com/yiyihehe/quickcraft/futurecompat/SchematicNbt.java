package com.yiyihehe.quickcraft.futurecompat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

/** .litematic 结构遍历与 palette 条目属性读写的共享工具（扫描与改写用同一套，保证语义一致）。 */
final class SchematicNbt {
    static final String REGIONS_KEY = "Regions";
    static final String PALETTE_KEY = "BlockStatePalette";
    static final String TILE_ENTITIES_KEY = "TileEntities";
    static final String ENTITIES_KEY = "Entities";
    static final String NAME_KEY = "Name";
    static final String PROPERTIES_KEY = "Properties";
    static final String ITEM_ID_KEY = "id";
    static final String COUNT_KEY = "count";
    static final String LEGACY_COUNT_KEY = "Count";

    private SchematicNbt() {
    }

    static void forEachRegion(NbtCompound root, Consumer<NbtCompound> visitor) {
        if (root.get(REGIONS_KEY) instanceof NbtCompound regions) {
            for (String regionName : regions.getKeys()) {
                if (regions.get(regionName) instanceof NbtCompound region) {
                    visitor.accept(region);
                }
            }
        }
    }

    static void forEachPaletteEntry(NbtCompound region, Consumer<NbtElement> visitor) {
        if (region.get(PALETTE_KEY) instanceof NbtList palette) {
            for (int i = 0; i < palette.size(); i++) {
                visitor.accept(palette.get(i));
            }
        }
    }

    static void forEachItemList(NbtCompound region, String listKey, Consumer<NbtElement> visitor) {
        if (region.get(listKey) instanceof NbtList list) {
            visitor.accept(list);
        }
    }

    /** 递归访问 ItemStack 形状（同时含 "id" 与 "count"/"Count" 的复合标签），覆盖嵌套容器。 */
    static void forEachItemStack(NbtElement element, Consumer<NbtCompound> visitor) {
        if (element instanceof NbtCompound compound) {
            if (compound.get(ITEM_ID_KEY) instanceof NbtString
                    && (compound.get(COUNT_KEY) != null || compound.get(LEGACY_COUNT_KEY) != null)) {
                visitor.accept(compound);
            }
            for (String key : compound.getKeys()) {
                forEachItemStack(compound.get(key), visitor);
            }
        } else if (element instanceof NbtList list) {
            for (int i = 0; i < list.size(); i++) {
                forEachItemStack(list.get(i), visitor);
            }
        }
    }

    static Map<String, String> readProperties(NbtCompound paletteEntry) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (paletteEntry.get(PROPERTIES_KEY) instanceof NbtCompound compound) {
            for (String key : compound.getKeys()) {
                compound.getString(key).ifPresent(value -> properties.put(key, value));
            }
        }
        return properties;
    }

    static void writeProperties(NbtCompound paletteEntry, Map<String, String> properties) {
        if (properties.isEmpty()) {
            paletteEntry.remove(PROPERTIES_KEY);
            return;
        }
        NbtCompound compound = new NbtCompound();
        properties.forEach(compound::putString);
        paletteEntry.put(PROPERTIES_KEY, compound);
    }
}
