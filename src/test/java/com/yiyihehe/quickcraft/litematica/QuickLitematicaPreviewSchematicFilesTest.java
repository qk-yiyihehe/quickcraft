package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewSchematicFiles.*;

class QuickLitematicaPreviewSchematicFilesTest {
    @Test
    void resolvesMissingIdsAcrossPackedWordsAndSignedRegionDimensions() {
        NbtCompound region = region();
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
        NbtCompound region = region();
        InventoryPalette lookup = InventoryPalette.from(region);
        assertThat(lookup.idAt(new NbtCompound())).isEmpty();
        assertThat(lookup.idAt(position(20, 0, 0))).isEmpty();
        assertThat(lookup.idAt(position(-1, 0, 0))).isEmpty();
        assertThat(isNonVisualInventory(lookup.idAt(position(0, 0, 0)))).isFalse();
        region.putLongArray("BlockStates", new long[1]);
        assertThat(InventoryPalette.from(region)).isNull();
    }

    @Test
    void skipsOnlyWhitelistedTypesWithoutRenderersAndKeepsVisibleAndUnknownData() {
        NbtCompound root = root(7);
        NbtCompound region = regionOf(root);
        NbtList entries = new NbtList();
        NbtCompound furnace = entity("minecraft:furnace", true);
        NbtCompound comparator = entity("minecraft:comparator", false);
        NbtCompound customHopper = entity("minecraft:hopper", true);
        customHopper.putString("CustomName", "custom renderer data");
        NbtCompound campfire = entity("minecraft:campfire", true);
        NbtCompound skull = entity("minecraft:skull", false);
        skull.putString("SkullOwner", "profile data");
        NbtCompound chest = entity("minecraft:chest", true);
        NbtCompound unknown = entity("other:machine", true);
        NbtList palette = new NbtList();
        String[] blockIds = {"minecraft:furnace", "minecraft:comparator", "minecraft:hopper", "minecraft:campfire",
                "minecraft:wither_skeleton_wall_skull", "minecraft:chest", "other:machine"};
        NbtCompound[] records = {furnace, comparator, customHopper, campfire, skull, chest, unknown};
        for (int i = 0; i < records.length; i++) {
            NbtCompound state = new NbtCompound();
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
        NbtList entities = new NbtList();
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
        NbtCompound root = root(1);
        NbtCompound furnaceState = new NbtCompound();
        furnaceState.putString("Name", "minecraft:furnace");
        QuickLitematicaPreviewAccess.readCompoundList(regionOf(root), "BlockStatePalette").set(0, furnaceState);
        NbtList entries = new NbtList();
        NbtCompound hopper = position(1, 0, 1);
        hopper.put("TileNBT", entity("", true));
        NbtCompound furnace = position(0, 0, 0);
        furnace.put("TileNBT", entity("Furnace", true));
        NbtCompound campfire = position(1, 1, 0);
        NbtCompound visibleNbt = entity("", true);
        campfire.put("TileNBT", visibleNbt);
        NbtCompound chest = position(2, 0, 1);
        NbtCompound chestNbt = entity("Chest", true);
        chestNbt.putString("CustomName", "legacy name");
        chest.put("TileNBT", chestNbt);
        for (NbtCompound entry : new NbtCompound[]{hopper, furnace, campfire, chest}) entries.add(entry);
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
        NbtCompound root = root(999);
        NbtList entries = new NbtList();
        entries.add(entity("minecraft:furnace", true));
        regionOf(root).put("TileEntities", entries);
        var before = root.copy();
        assertThat(preparePreviewNbt(root, new AtomicBoolean(), id -> false, QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true).modified()).isFalse();
        assertThat(root).isEqualTo(before);
    }

    @Test
    void restoresMissingTypesBeforeRemovingInventoryAndPreservesSkullProfile() {
        NbtCompound root = root(7);
        NbtList entries = new NbtList();
        NbtCompound chest = position(2, 0, 1);
        chest.put("Items", entity("", true).get("Items").copy());
        chest.putString("CustomName", "keep name");
        NbtCompound shulker = position(3, 1, 0);
        shulker.put("Items", entity("", true).get("Items").copy());
        NbtCompound components = new NbtCompound();
        components.putString("minecraft:custom_name", "visible component");
        shulker.put("components", components);
        NbtCompound skull = position(4, 0, 1);
        skull.putString("SkullOwner", "profile data");
        for (NbtCompound entry : new NbtCompound[]{chest, shulker, skull}) entries.add(entry);
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
        assertThat(QuickLitematicaPreviewAccess.readString(skull, "SkullOwner")).isEqualTo("profile data");
    }

    @Test
    void writesTypeOnlyRepairsAndPreservesUnresolvedMissingIds() {
        NbtCompound root = root(7);
        NbtList entries = new NbtList();
        NbtCompound skull = position(4, 0, 1);
        NbtCompound unresolved = position(0, 0, 0);
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
        NbtCompound root = root(7);
        NbtList entries = new NbtList();
        NbtCompound chest = position(2, 0, 1);
        chest.putString("id", "minecraft:chest");
        chest.put("Items", entity("", true).get("Items").copy());
        NbtCompound shulker = position(3, 1, 0);
        shulker.putString("id", "minecraft:shulker_box");
        NbtCompound named = chest.copy();
        named.putString("CustomName", "visible name");
        NbtCompound emptyComponents = shulker.copy();
        emptyComponents.put("components", new NbtCompound());
        NbtCompound components = shulker.copy();
        NbtCompound payload = new NbtCompound();
        payload.putString("minecraft:custom_name", "visible component");
        components.put("components", payload);
        NbtCompound malformedComponents = shulker.copy();
        malformedComponents.putString("components", "");
        NbtCompound unknown = position(2, 0, 1);
        unknown.putString("id", "other:chest");
        unknown.put("Items", entity("", true).get("Items").copy());
        var unknownBefore = unknown.copy();
        var statesBefore = regionOf(root).get("BlockStates").copy();
        for (NbtCompound entry : new NbtCompound[]{chest, shulker, emptyComponents, named, components, malformedComponents, unknown}) entries.add(entry);
        regionOf(root).put("TileEntities", entries);

        PreparationStats stats = preparePreviewNbt(root, new AtomicBoolean(), id -> true,
                QuickLitematicaPreviewSchematicFilesTest::inferredType, id -> true);

        assertThat(stats.defaultContainerNbt()).isEqualTo(3);
        assertThat(stats.skippedByType()).containsEntry("minecraft:chest", 1).containsEntry("minecraft:shulker_box", 2);
        assertThat(entries).containsExactly(named, components, malformedComponents, unknown);
        assertThat(named.contains("CustomName")).isTrue();
        assertThat(components.get("components")).isEqualTo(payload);
        assertThat(malformedComponents.getString("components")).isEmpty();
        assertThat(unknown).isEqualTo(unknownBefore);
        assertThat(regionOf(root).get("BlockStates")).isEqualTo(statesBefore);
    }

    @Test
    void removesRetiredBedNbtOnlyWhenTheActualColoredBlockNoLongerHasARenderer() {
        NbtCompound root = root(7);
        NbtList entries = new NbtList();
        NbtCompound coloredBed = position(5, 0, 1);
        coloredBed.putString("id", "minecraft:bed");
        NbtCompound legacyBed = position(6, 0, 1);
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
        NbtCompound root = root(7);
        NbtCompound customState = new NbtCompound();
        customState.putString("Name", "other:machine");
        QuickLitematicaPreviewAccess.readCompoundList(regionOf(root), "BlockStatePalette").set(0, customState);
        NbtList entries = new NbtList();
        NbtCompound reusedId = position(0, 0, 0);
        reusedId.putString("id", "minecraft:hopper");
        reusedId.put("Items", entity("", true).get("Items").copy());
        NbtCompound visibleChest = position(2, 0, 1);
        visibleChest.putString("id", "minecraft:chest");
        visibleChest.put("Items", entity("", true).get("Items").copy());
        NbtCompound defaultChest = position(2, 0, 1);
        defaultChest.putString("id", "minecraft:chest");
        defaultChest.put("components", new NbtCompound());
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
        NbtCompound root = root(7);
        NbtList entries = new NbtList();
        NbtCompound campfireWithWrongId = position(1, 1, 0);
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
        NbtCompound root = root(7);
        NbtCompound region = regionOf(root);
        NbtCompound state = new NbtCompound();
        state.putString("Name", "minecraft:beehive");
        NbtCompound properties = new NbtCompound();
        properties.putString("facing", "east");
        properties.putString("honey_level", "5");
        state.put("Properties", properties);
        QuickLitematicaPreviewAccess.readCompoundList(region, "BlockStatePalette").set(0, state);
        NbtCompound hive = position(0, 0, 0);
        hive.putString("id", "minecraft:beehive");
        NbtList bees = new NbtList();
        bees.add(entity("minecraft:bee", false));
        hive.put("Bees", bees);
        hive.put("FlowerPos", position(10, 10, 10));
        hive.put("components", new NbtCompound());
        NbtCompound extra = hive.copy();
        extra.putString("other:render_data", "preserve");
        NbtCompound nonemptyComponents = hive.copy();
        NbtCompound payload = new NbtCompound();
        payload.putString("other:render_data", "preserve");
        nonemptyComponents.put("components", payload);
        NbtCompound wrongBlock = hive.copy();
        wrongBlock.putInt("x", 1);
        wrongBlock.putInt("y", 1);
        NbtList entries = new NbtList();
        for (NbtCompound entry : new NbtCompound[]{hive, extra, nonemptyComponents, wrongBlock}) entries.add(entry);
        region.put("TileEntities", entries);
        NbtList entities = new NbtList();
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

    private static NbtCompound entity(String id, boolean inventory) {
        NbtCompound entry = new NbtCompound();
        if (!id.isEmpty()) entry.putString("id", id);
        if (inventory) {
            NbtList items = new NbtList();
            NbtCompound item = new NbtCompound();
            item.putString("id", "minecraft:diamond");
            items.add(item);
            entry.put("Items", items);
        }
        return entry;
    }

    private static NbtCompound root(int version) {
        NbtCompound root = new NbtCompound();
        root.putInt("Version", version);
        NbtCompound regions = new NbtCompound();
        regions.put("region", region());
        root.put("Regions", regions);
        return root;
    }

    private static NbtCompound regionOf(NbtCompound root) { return QuickLitematicaPreviewAccess.readCompound(QuickLitematicaPreviewAccess.readCompound(root, "Regions"), "region"); }

    private static NbtCompound region() {
        NbtCompound region = new NbtCompound();
        NbtCompound size = position(-20, -2, -2);
        region.put("Size", size);
        NbtList palette = new NbtList();
        for (String id : new String[]{"minecraft:air", "minecraft:hopper", "minecraft:campfire",
                "minecraft:white_shulker_box", "minecraft:chest", "minecraft:wither_skeleton_wall_skull", "minecraft:red_bed", "minecraft:bed"}) {
            NbtCompound state = new NbtCompound();
            state.putString("Name", id);
            palette.add(state);
        }
        region.put("BlockStatePalette", palette);
        // 3 位调色板索引：21 跨第一个 long 边界，41/43 区分 Y/Z 排列。
        region.putLongArray("BlockStates", new long[]{Long.MIN_VALUE, (2L << 59) | 16L | (5L << 8) | (6L << 11) | (7L << 14), 6L, 0L});
        return region;
    }

    private static NbtCompound position(int x, int y, int z) {
        NbtCompound tag = new NbtCompound();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }
}
