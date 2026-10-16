package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只从文件绑定的原理图统计实体本体及展示物，不依赖投影世界或实体库存。 */
public final class QuickLitematicaEntityMaterials {
    private QuickLitematicaEntityMaterials() { }

    static void appendMaterials(CompoundTag nbt, HolderLookup.Provider lookup, List<ItemStack> materials, int depth) {
        // 与实体放置相同的深度上限，防止损坏文件的乘客树耗尽调用栈。
        if (depth > 8) { return; }
        Identifier id = Identifier.tryParse(QuickLitematicaContainerMaterialsAccess.readString(nbt, "id"));
        if (id != null && id.getNamespace().equals("minecraft")) {
            switch (id.getPath()) {
                case "iron_golem" -> {
                    materials.add(new ItemStack(Items.IRON_BLOCK, 4));
                    materials.add(new ItemStack(Items.CARVED_PUMPKIN));
                }
                case "snow_golem" -> {
                    materials.add(new ItemStack(Items.SNOW_BLOCK, 2));
                    materials.add(new ItemStack(Items.CARVED_PUMPKIN));
                }
                case "copper_golem" -> {
                    materials.add(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("copper_block"))));
                    materials.add(new ItemStack(Items.CARVED_PUMPKIN));
                }
                case "wither" -> {
                    materials.add(new ItemStack(Items.SOUL_SAND, 4));
                    materials.add(new ItemStack(Items.WITHER_SKELETON_SKULL, 3));
                }
                default -> {
                    ItemStack base = QuickLitematicaEntityPlacement.getPlaceableMaterial(id, nbt);
                    if (!base.isEmpty()) { materials.add(base); }
                }
            }
            if (lookup != null) {
                if (id.getPath().equals("item_frame") || id.getPath().equals("glow_item_frame")) {
                    appendDisplayedItem(nbt, "Item", lookup, materials);
                } else if (id.getPath().equals("armor_stand")) {
                    if (nbt.get("equipment") instanceof CompoundTag equipment) {
                        for (String slot : List.of("head", "chest", "legs", "feet", "mainhand", "offhand")) {
                            appendDisplayedItem(equipment, slot, lookup, materials);
                        }
                    } else {
                        for (String key : List.of("ArmorItems", "HandItems")) {
                            var equipment = QuickLitematicaContainerMaterialsAccess.readCompoundList(nbt, key);
                            for (int index = 0; index < equipment.size(); index++) {
                                appendItem(QuickLitematicaContainerMaterialsAccess.readCompound(equipment, index), lookup, materials);
                            }
                        }
                    }
                }
            }
        }
        var passengers = QuickLitematicaContainerMaterialsAccess.readCompoundList(nbt, "Passengers");
        for (int index = 0; index < passengers.size(); index++) {
            appendMaterials(QuickLitematicaContainerMaterialsAccess.readCompound(passengers, index), lookup, materials, depth + 1);
        }
    }

    private static void appendDisplayedItem(CompoundTag nbt, String key, HolderLookup.Provider lookup, List<ItemStack> materials) {
        if (nbt.get(key) instanceof CompoundTag item) { appendItem(item, lookup, materials); }
    }

    private static void appendItem(CompoundTag nbt, HolderLookup.Provider lookup, List<ItemStack> materials) {
        if (nbt.isEmpty()) { return; }
        ItemStack stack = QuickLitematicaSchematicData.decodeItem(nbt, lookup);
        if (!stack.isEmpty()) { materials.add(stack); }
    }

    static final class EntityMaterialList extends MaterialListBase {
        final LitematicaSchematic schematic;
        final List<String> regions;
        QuickLitematicaMaterialNavigation navigation;

        EntityMaterialList(LitematicaSchematic schematic, Collection<String> regions) {
            this.schematic = QuickLitematicaContainerMemory.original(schematic);
            this.regions = List.copyOf(regions);
            this.reCreateMaterialList();
        }

        @Override
        public String getName() { return this.schematic.getMetadata().getName(); }

        @Override
        public String getTitle() { return StringUtils.translate("quickcraft.litematica.title.entity_materials", this.getName()); }

        @Override
        public void reCreateMaterialList() {
            var client = Minecraft.getInstance();
            var lookup = client.level == null ? null : client.level.registryAccess();
            List<ItemStack> materials = new ArrayList<>();
            for (String region : this.regions) {
                var entities = this.schematic.getEntityListForRegion(region);
                if (entities == null) { continue; }
                for (var entity : entities) {
                    appendMaterials(QuickLitematicaContainerMaterialsAccess.getEntityNbt(entity), lookup, materials, 0);
                }
            }
            Map<QuickLitematicaContainerMaterials.ItemKey, MaterialListEntry> entries = new LinkedHashMap<>();
            for (ItemStack stack : materials) {
                var key = QuickLitematicaContainerMaterialsAccess.createItemKey(stack);
                var previous = entries.get(key);
                int total = stack.getCount() + (previous == null ? 0 : previous.getCountTotal());
                entries.put(key, new MaterialListEntry(stack.copyWithCount(1), total, total, 0, 0));
            }
            var result = new ArrayList<>(entries.values());
            if (client.player != null) { MaterialListUtils.updateAvailableCounts(result, client.player); }
            this.setMaterialListEntries(result);
        }
    }
}
