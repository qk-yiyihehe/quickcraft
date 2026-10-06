package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewSchematicFiles.*;

class QuickLitematicaPreviewSchematicFilesTest {
    @Test
    void resolvesMissingIdsAcrossPackedWordsAndSignedRegionDimensions() {
        CompoundTag region = region();
        InventoryPalette lookup = InventoryPalette.from(region);
        assertThat(lookup).isNotNull();
        assertThat(lookup.idAt(position(1, 0, 1))).isEqualTo("minecraft:hopper");
        assertThat(isNonVisualInventory(lookup.idAt(position(1, 0, 1)))).isTrue();
        assertThat(lookup.idAt(position(1, 1, 0))).isEqualTo("minecraft:campfire");
        assertThat(isNonVisualInventory(lookup.idAt(position(1, 1, 0)))).isFalse();
        assertThat(isNonVisualInventory(lookup.idAt(position(3, 1, 0)))).isTrue();
        assertThat(isNonVisualInventory("other:blue_shulker_box")).isFalse();
    }

    @Test
    void preservesUnknownDataWhenCoordinatesOrPackedStorageAreInvalid() {
        CompoundTag region = region();
        InventoryPalette lookup = InventoryPalette.from(region);
        assertThat(lookup.idAt(new CompoundTag())).isEmpty();
        assertThat(lookup.idAt(position(20, 0, 0))).isEmpty();
        assertThat(lookup.idAt(position(-1, 0, 0))).isEmpty();
        assertThat(isNonVisualInventory(lookup.idAt(position(0, 0, 0)))).isFalse();
        region.putLongArray("BlockStates", new long[1]);
        assertThat(InventoryPalette.from(region)).isNull();
    }

    @Test
    void skipsOnlyWhitelistedTypesWithoutRenderersAndKeepsVisibleAndUnknownData() {
        CompoundTag root = root(7);
        CompoundTag region = regionOf(root);
        ListTag entries = new ListTag();
        CompoundTag furnace = entity("minecraft:furnace", true);
        CompoundTag comparator = entity("minecraft:comparator", false);
        CompoundTag customHopper = entity("minecraft:hopper", true);
        customHopper.putString("CustomName", "custom renderer data");
        CompoundTag campfire = entity("minecraft:campfire", true);
        CompoundTag skull = entity("minecraft:skull", false);
        skull.putString("SkullOwner", "profile data");
        CompoundTag chest = entity("minecraft:chest", true);
        CompoundTag unknown = entity("other:machine", true);
        ListTag palette = new ListTag();
        String[] blockIds = {"minecraft:furnace", "minecraft:comparator", "minecraft:hopper", "minecraft:campfire",
                "minecraft:wither_skeleton_wall_skull", "minecraft:chest", "other:machine"};
        CompoundTag[] records = {furnace, comparator, customHopper, campfire, skull, chest, unknown};
        for (int i = 0; i < records.length; i++) {
            CompoundTag state = new CompoundTag();
            state.putString("Name", blockIds[i]);
            palette.add(state);
            records[i].putInt("x", i);
            records[i].putInt("y", 0);
            records[i].putInt("z", 0);
            entries.add(records[i]);
        }
        chest.putString("CustomName", "kept payload");
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", new long[]{(1L << 3) | (2L << 6) | (3L << 9) | (4L << 12) | (5L << 15) | (6L << 18), 0, 0, 0});
        region.put("TileEntities", entries);
        var campfireBefore = campfire.copy();
        var skullBefore = skull.copy();
        var unknownBefore = unknown.copy();
        var statesBefore = region.get("BlockStates").copy();
        ListTag entities = new ListTag();
        entities.add(entity("minecraft:armor_stand", true));
        region.put("Entities", entities);
        var entitiesBefore = entities.copy();

        PreparationStats stats = preparePreviewNbt(root, new AtomicBoolean(), id -> id.equals("minecraft:hopper"), QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true);

        assertThat(stats.blockEntities()).isEqualTo(7);
        assertThat(stats.skippedBlockEntities()).isEqualTo(2);
        assertThat(stats.skippedByType()).containsEntry("minecraft:furnace", 1).containsEntry("minecraft:comparator", 1);
        assertThat(entries).containsExactly(customHopper, campfire, skull, chest, unknown);
        assertThat(campfire).isEqualTo(campfireBefore);
        assertThat(skull).isEqualTo(skullBefore);
        assertThat(unknown).isEqualTo(unknownBefore);
        assertThat(customHopper.contains("CustomName")).isTrue();
        assertThat(chest.contains("Items")).isFalse();
        assertThat(region.get("BlockStates")).isEqualTo(statesBefore);
        assertThat(region.get("Entities")).isEqualTo(entitiesBefore);
    }

    @Test
    void handlesWrappedLegacyNbtAndMissingIdsWithoutRemovingCampfireItems() {
        CompoundTag root = root(1);
        CompoundTag furnaceState = new CompoundTag();
        furnaceState.putString("Name", "minecraft:furnace");
        regionOf(root).getListOrEmpty("BlockStatePalette").set(0, furnaceState);
        ListTag entries = new ListTag();
        CompoundTag hopper = position(1, 0, 1);
        hopper.put("TileNBT", entity("", true));
        CompoundTag furnace = position(0, 0, 0);
        furnace.put("TileNBT", entity("Furnace", true));
        CompoundTag campfire = position(1, 1, 0);
        CompoundTag visibleNbt = entity("", true);
        campfire.put("TileNBT", visibleNbt);
        CompoundTag chest = position(2, 0, 1);
        CompoundTag chestNbt = entity("Chest", true);
        chestNbt.putString("CustomName", "legacy name");
        chest.put("TileNBT", chestNbt);
        for (CompoundTag entry : new CompoundTag[]{hopper, furnace, campfire, chest}) entries.add(entry);
        regionOf(root).put("TileEntities", entries);
        var visibleBefore = visibleNbt.copy();
        visibleBefore.putString("id", "minecraft:campfire");

        PreparationStats stats = preparePreviewNbt(root, new AtomicBoolean(), id -> false, QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true);

        assertThat(stats.skippedByType()).containsEntry("minecraft:hopper", 1).containsEntry("minecraft:furnace", 1);
        assertThat(entries).containsExactly(campfire, chest);
        assertThat(visibleNbt).isEqualTo(visibleBefore);
        assertThat(chestNbt.contains("Items")).isFalse();
        assertThat(chestNbt.get("id")).isEqualTo(entity("Chest", false).get("id"));
    }

    @Test
    void leavesFutureFormatsUntouched() {
        CompoundTag root = root(999);
        ListTag entries = new ListTag();
        entries.add(entity("minecraft:furnace", true));
        regionOf(root).put("TileEntities", entries);
        var before = root.copy();
        assertThat(preparePreviewNbt(root, new AtomicBoolean(), id -> false, QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true).modified()).isFalse();
        assertThat(root).isEqualTo(before);
    }

    @Test
    void restoresMissingTypesBeforeRemovingInventoryAndPreservesSkullProfile() {
        CompoundTag root = root(7);
        ListTag entries = new ListTag();
        CompoundTag chest = position(2, 0, 1);
        chest.put("Items", entity("", true).get("Items").copy());
        chest.putString("CustomName", "keep name");
        CompoundTag shulker = position(3, 1, 0);
        shulker.put("Items", entity("", true).get("Items").copy());
        CompoundTag components = new CompoundTag();
        components.putString("minecraft:custom_name", "visible component");
        shulker.put("components", components);
        CompoundTag skull = position(4, 0, 1);
        skull.putString("SkullOwner", "profile data");
        for (CompoundTag entry : new CompoundTag[]{chest, shulker, skull}) entries.add(entry);
        regionOf(root).put("TileEntities", entries);

        PreparationStats stats = preparePreviewNbt(root, new AtomicBoolean(), id -> true,
                QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true);

        assertThat(stats.restoredBlockEntityIds()).isEqualTo(3);
        assertThat(stats.inferredInventoryIds()).isEqualTo(2);
        assertThat(stats.modified()).isTrue();
        assertThat(entries).containsExactly(chest, shulker, skull);
        assertThat(chest.get("id")).isEqualTo(entity("minecraft:chest", false).get("id"));
        assertThat(shulker.get("id")).isEqualTo(entity("minecraft:shulker_box", false).get("id"));
        assertThat(skull.get("id")).isEqualTo(entity("minecraft:skull", false).get("id"));
        assertThat(chest.contains("Items")).isFalse();
        assertThat(shulker.contains("Items")).isFalse();
        assertThat(shulker.get("components")).isEqualTo(components);
        assertThat(skull.getStringOr("SkullOwner", "")).isEqualTo("profile data");
    }

    @Test
    void writesTypeOnlyRepairsAndPreservesUnresolvedMissingIds() {
        CompoundTag root = root(7);
        ListTag entries = new ListTag();
        CompoundTag skull = position(4, 0, 1);
        CompoundTag unresolved = position(0, 0, 0);
        unresolved.put("Items", entity("", true).get("Items").copy());
        var before = unresolved.copy();
        entries.add(skull);
        entries.add(unresolved);
        regionOf(root).put("TileEntities", entries);

        PreparationStats stats = preparePreviewNbt(root, new AtomicBoolean(), id -> false,
                QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true);

        assertThat(stats.restoredBlockEntityIds()).isEqualTo(1);
        assertThat(stats.removedInventories()).isZero();
        assertThat(stats.skippedBlockEntities()).isZero();
        assertThat(stats.modified()).isTrue();
        assertThat(unresolved).isEqualTo(before);
    }

    @Test
    void skipsDefaultContainerPayloadsButKeepsNamesComponentsAndUnknownTypes() {
        CompoundTag root = root(7);
        ListTag entries = new ListTag();
        CompoundTag chest = position(2, 0, 1);
        chest.putString("id", "minecraft:chest");
        chest.put("Items", entity("", true).get("Items").copy());
        CompoundTag shulker = position(3, 1, 0);
        shulker.putString("id", "minecraft:shulker_box");
        CompoundTag named = chest.copy();
        named.putString("CustomName", "visible name");
        CompoundTag emptyComponents = shulker.copy();
        emptyComponents.put("components", new CompoundTag());
        CompoundTag components = shulker.copy();
        CompoundTag payload = new CompoundTag();
        payload.putString("minecraft:custom_name", "visible component");
        components.put("components", payload);
        CompoundTag malformedComponents = shulker.copy();
        malformedComponents.putString("components", "");
        CompoundTag unknown = position(2, 0, 1);
        unknown.putString("id", "other:chest");
        unknown.put("Items", entity("", true).get("Items").copy());
        var unknownBefore = unknown.copy();
        var statesBefore = regionOf(root).get("BlockStates").copy();
        for (CompoundTag entry : new CompoundTag[]{chest, shulker, emptyComponents, named, components, malformedComponents, unknown}) entries.add(entry);
        regionOf(root).put("TileEntities", entries);

        PreparationStats stats = preparePreviewNbt(root, new AtomicBoolean(), id -> true,
                QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true);

        assertThat(stats.defaultContainerNbt()).isEqualTo(3);
        assertThat(stats.skippedByType()).containsEntry("minecraft:chest", 1).containsEntry("minecraft:shulker_box", 2);
        assertThat(entries).containsExactly(named, components, malformedComponents, unknown);
        assertThat(named.contains("CustomName")).isTrue();
        assertThat(components.get("components")).isEqualTo(payload);
        assertThat(malformedComponents.getString("components").orElse("missing")).isEmpty();
        assertThat(unknown).isEqualTo(unknownBefore);
        assertThat(regionOf(root).get("BlockStates")).isEqualTo(statesBefore);
    }

    @Test
    void removesRetiredBedNbtOnlyWhenTheActualColoredBlockNoLongerHasARenderer() {
        CompoundTag root = root(7);
        ListTag entries = new ListTag();
        CompoundTag coloredBed = position(5, 0, 1);
        coloredBed.putString("id", "minecraft:bed");
        CompoundTag legacyBed = position(6, 0, 1);
        legacyBed.putString("id", "minecraft:bed");
        legacyBed.putInt("color", 14);
        var legacyBefore = legacyBed.copy();
        entries.add(coloredBed);
        entries.add(legacyBed);
        regionOf(root).put("TileEntities", entries);
        var source = root.copy();

        PreparationStats retained = preparePreviewNbt(source, new AtomicBoolean(), id -> true,
                QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true);
        assertThat(retained.skippedBlockEntities()).isZero();
        assertThat(source).isEqualTo(root);

        PreparationStats pruned = preparePreviewNbt(root, new AtomicBoolean(), id -> !id.equals("minecraft:red_bed"),
                QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true);
        assertThat(pruned.skippedByType()).containsEntry("minecraft:bed", 1);
        assertThat(entries).containsExactly(legacyBed);
        assertThat(legacyBed).isEqualTo(legacyBefore);
    }

    @Test
    void preservesInventoryForUnknownBlocksReusingVanillaIdsAndCustomRenderers() {
        CompoundTag root = root(7);
        CompoundTag customState = new CompoundTag();
        customState.putString("Name", "other:machine");
        regionOf(root).getListOrEmpty("BlockStatePalette").set(0, customState);
        ListTag entries = new ListTag();
        CompoundTag reusedId = position(0, 0, 0);
        reusedId.putString("id", "minecraft:hopper");
        reusedId.put("Items", entity("", true).get("Items").copy());
        CompoundTag visibleChest = position(2, 0, 1);
        visibleChest.putString("id", "minecraft:chest");
        visibleChest.put("Items", entity("", true).get("Items").copy());
        CompoundTag defaultChest = position(2, 0, 1);
        defaultChest.putString("id", "minecraft:chest");
        defaultChest.put("components", new CompoundTag());
        var defaultBefore = defaultChest.copy();
        var reusedBefore = reusedId.copy();
        var chestBefore = visibleChest.copy();
        entries.add(reusedId);
        entries.add(visibleChest);
        entries.add(defaultChest);
        regionOf(root).put("TileEntities", entries);

        PreparationStats stats = preparePreviewNbt(root, new AtomicBoolean(), id -> true,
                QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> false);

        assertThat(stats.modified()).isFalse();
        assertThat(reusedId).isEqualTo(reusedBefore);
        assertThat(visibleChest).isEqualTo(chestBefore);
        assertThat(defaultChest).isEqualTo(defaultBefore);
        assertThat(entries).containsExactly(reusedId, visibleChest, defaultChest);
    }

    @Test
    void preservesMismatchedBedIdsOnBlocksWithVisibleInventory() {
        CompoundTag root = root(7);
        ListTag entries = new ListTag();
        CompoundTag campfireWithWrongId = position(1, 1, 0);
        campfireWithWrongId.putString("id", "minecraft:bed");
        campfireWithWrongId.put("Items", entity("", true).get("Items").copy());
        entries.add(campfireWithWrongId);
        regionOf(root).put("TileEntities", entries);
        var before = root.copy();

        PreparationStats stats = preparePreviewNbt(root, new AtomicBoolean(), id -> false,
                QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true);

        assertThat(stats.modified()).isFalse();
        assertThat(root).isEqualTo(before);
    }

    @Test
    void skipsInternalHiveDataOnlyForVerifiedHivesWithoutRenderers() {
        CompoundTag root = root(7);
        CompoundTag region = regionOf(root);
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:beehive");
        CompoundTag properties = new CompoundTag();
        properties.putString("facing", "east");
        properties.putString("honey_level", "5");
        state.put("Properties", properties);
        region.getListOrEmpty("BlockStatePalette").set(0, state);
        CompoundTag hive = position(0, 0, 0);
        hive.putString("id", "minecraft:beehive");
        ListTag bees = new ListTag();
        bees.add(entity("minecraft:bee", false));
        hive.put("Bees", bees);
        hive.put("FlowerPos", position(10, 10, 10));
        hive.put("components", new CompoundTag());
        CompoundTag extra = hive.copy();
        extra.putString("other:render_data", "preserve");
        CompoundTag nonemptyComponents = hive.copy();
        CompoundTag payload = new CompoundTag();
        payload.putString("other:render_data", "preserve");
        nonemptyComponents.put("components", payload);
        CompoundTag wrongBlock = hive.copy();
        wrongBlock.putInt("x", 1);
        wrongBlock.putInt("y", 1);
        ListTag entries = new ListTag();
        for (CompoundTag entry : new CompoundTag[]{hive, extra, nonemptyComponents, wrongBlock}) entries.add(entry);
        region.put("TileEntities", entries);
        ListTag entities = new ListTag();
        entities.add(entity("minecraft:bee", false));
        region.put("Entities", entities);
        var before = root.copy();
        var entitiesBefore = entities.copy();
        var statesBefore = region.get("BlockStates").copy();
        var paletteBefore = region.get("BlockStatePalette").copy();

        assertThat(preparePreviewNbt(root, new AtomicBoolean(), id -> true,
                QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true).modified()).isFalse();
        assertThat(root).isEqualTo(before);
        PreparationStats stats = preparePreviewNbt(root, new AtomicBoolean(), id -> false,
                QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true);
        assertThat(stats.skippedByType()).containsEntry("minecraft:beehive", 1);
        assertThat(entries).containsExactly(extra, nonemptyComponents, wrongBlock);
        assertThat(entities).isEqualTo(entitiesBefore);
        assertThat(region.get("BlockStates")).isEqualTo(statesBefore);
        assertThat(region.get("BlockStatePalette")).isEqualTo(paletteBefore);
    }

    private static String inferredType(String blockId) {
        return switch (blockId) {
            case "minecraft:white_shulker_box" -> "minecraft:shulker_box";
            case "minecraft:wither_skeleton_wall_skull" -> "minecraft:skull";
            case "minecraft:chest", "minecraft:campfire", "minecraft:hopper", "minecraft:furnace", "minecraft:comparator", "minecraft:beehive" -> blockId;
            default -> "";
        };
    }

    private static CompoundTag entity(String id, boolean inventory) {
        CompoundTag entry = new CompoundTag();
        if (!id.isEmpty()) entry.putString("id", id);
        if (inventory) {
            ListTag items = new ListTag();
            CompoundTag item = new CompoundTag();
            item.putString("id", "minecraft:diamond");
            items.add(item);
            entry.put("Items", items);
        }
        return entry;
    }

    private static CompoundTag root(int version) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", version);
        CompoundTag regions = new CompoundTag();
        regions.put("region", region());
        root.put("Regions", regions);
        return root;
    }

    private static CompoundTag regionOf(CompoundTag root) { return root.getCompoundOrEmpty("Regions").getCompoundOrEmpty("region"); }

    private static CompoundTag region() {
        CompoundTag region = new CompoundTag();
        CompoundTag size = position(-20, -2, -2);
        region.put("Size", size);
        ListTag palette = new ListTag();
        for (String id : new String[]{"minecraft:air", "minecraft:hopper", "minecraft:campfire",
                "minecraft:white_shulker_box", "minecraft:chest", "minecraft:wither_skeleton_wall_skull", "minecraft:red_bed", "minecraft:bed"}) {
            CompoundTag state = new CompoundTag();
            state.putString("Name", id);
            palette.add(state);
        }
        region.put("BlockStatePalette", palette);
        // 3 位调色板索引：21 跨第一个 long 边界，41/43 区分 Y/Z 排列。
        region.putLongArray("BlockStates", new long[]{Long.MIN_VALUE, (2L << 59) | 16L | (5L << 8) | (6L << 11) | (7L << 14), 6L, 0L});
        return region;
    }

    private static CompoundTag position(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }
}
