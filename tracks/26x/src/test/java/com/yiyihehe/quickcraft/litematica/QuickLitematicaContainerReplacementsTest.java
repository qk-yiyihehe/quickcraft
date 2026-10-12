package com.yiyihehe.quickcraft.litematica;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaContainerReplacementsTest {
    private static HolderLookup.Provider lookup;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void persistsCountAndComponentsWithoutDelimiterLoss() {
        ItemStack target = new ItemStack(item("minecraft:diamond_axe"));
        target.set(DataComponents.CUSTOM_NAME, Component.literal("a->b||#").withStyle(style -> style.withBold(true)));
        target.setDamageValue(27);
        var decoded = roundTrip(new ItemStack(item("minecraft:stone"), 64), target, 3, false);

        assertThat(decoded).isNotNull();
        assertThat(ItemStack.isSameItemSameComponents(target, decoded.target())).isTrue();
        assertThat(decoded.target().getCount()).isEqualTo(1);
        assertThat(decoded.slotIndex()).isEqualTo(3);
    }

    @Test
    void slotOptOutWinsOverBulkRegardlessOfRuleOrder() {
        ItemStack source = new ItemStack(item("minecraft:stone"), 32);
        var bulk = roundTrip(source, new ItemStack(item("minecraft:glass"), 1), -1, true);
        var original = roundTrip(source, source.copy(), 4, false);

        for (var rules : List.of(List.of(bulk, original), List.of(original, bulk))) {
            var selected = QuickLitematicaContainerReplacements.findRule(rules, source,
                    QuickLitematicaContainerReplacements.Scope.CONTAINER, "context", "group", 4);
            assertThat(selected).isSameAs(original);
            var following = QuickLitematicaContainerReplacements.findRule(rules, source,
                    QuickLitematicaContainerReplacements.Scope.CONTAINER, "context", "group", 5);
            assertThat(following).isSameAs(bulk);
            assertThat(QuickLitematicaContainerReplacements.resolveTarget(source, following).getCount()).isEqualTo(32);
        }
    }

    @Test
    void canFillEmptySlotAndKeepExplicitCount() {
        var rule = roundTrip(ItemStack.EMPTY, new ItemStack(item("minecraft:glass"), 7), 8, false);
        var selected = QuickLitematicaContainerReplacements.findRule(List.of(rule), ItemStack.EMPTY,
                QuickLitematicaContainerReplacements.Scope.CONTAINER, "context", "group", 8);
        assertThat(selected).isSameAs(rule);
        assertThat(QuickLitematicaContainerReplacements.resolveTarget(ItemStack.EMPTY, selected).getCount()).isEqualTo(7);
    }

    @Test
    void fileAndPlacementContainerRulesMigrateToSameFileScopeWithNewestRuleWinning() {
        String fileContext = "schematic:C:/build/source.litematic#regions=a,b";
        ItemStack source = new ItemStack(item("minecraft:stone"), 32);
        String placementRule = QuickLitematicaContainerReplacements.encodeContainerRule(
                new QuickLitematicaContainerReplacements.ContainerReplacement(source, new ItemStack(item("minecraft:glass")),
                        "placement:old", "group", -1, true), lookup);
        String fileRule = QuickLitematicaContainerReplacements.encodeContainerRule(
                new QuickLitematicaContainerReplacements.ContainerReplacement(source.copyWithCount(64), new ItemStack(item("minecraft:oak_planks")),
                        "schematic:C:/build/source.litematic#regions=a", "group", -1, true), lookup);
        String unrelated = QuickLitematicaContainerReplacements.encodeContainerRule(
                new QuickLitematicaContainerReplacements.ContainerReplacement(source, new ItemStack(item("minecraft:dirt")),
                        "schematic:C:/build/other.litematic#regions=a", "group", -1, true), lookup);
        var migrated = QuickLitematicaContainerReplacements.migrateContainerEntries(
                List.of(placementRule, unrelated, fileRule, "minecraft:stone->minecraft:glass"),
                "placement:old", fileContext, lookup);
        assertThat(migrated).contains(unrelated, "minecraft:stone->minecraft:glass").hasSize(3);
        var selected = migrated.stream().map(entry -> QuickLitematicaContainerReplacements.decodeContainerRule(entry, lookup))
                .filter(rule -> rule != null && fileContext.equals(rule.contextKey())).findFirst().orElseThrow();
        assertThat(selected.target().is(item("minecraft:oak_planks"))).isTrue();
        assertThat(selected.groupKey()).isEqualTo("group");
    }

    @Test
    void writingContainerMemoryPreservesBlocksOtherTagsAndIndependentSnapshot() throws ReflectiveOperationException {
        try (var files = org.mockito.Mockito.mockStatic(fi.dy.masa.malilib.util.FileUtils.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            files.when(fi.dy.masa.malilib.util.FileUtils::getMinecraftDirectory).thenReturn(java.nio.file.Path.of(System.getProperty("user.dir")));
            var root = new net.minecraft.nbt.CompoundTag();
            root.putInt("Version", 6);
            // 1.21 文件格式夹具：同时检查后续版本读取旧原理图时的数据隔离。
            root.putInt("MinecraftDataVersion", 3953);
            var region = new net.minecraft.nbt.CompoundTag();
            var position = new net.minecraft.nbt.CompoundTag();
            position.putInt("x", 0);
            position.putInt("y", 0);
            position.putInt("z", 0);
            region.put("Position", position);
            var size = new net.minecraft.nbt.CompoundTag();
            size.putInt("x", 1);
            size.putInt("y", 1);
            size.putInt("z", 1);
            region.put("Size", size);
            var states = new fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer(1, 1, 1);
            states.set(0, 0, 0, block("minecraft:chest").defaultBlockState());
            var palette = new net.minecraft.nbt.ListTag();
            for (int index = 0; index < states.getPalette().getPaletteSize(); index++) {
                var stateTag = net.minecraft.nbt.NbtUtils.writeBlockState(states.getPalette().getBlockState(index));
                // 夹具声明 1.21 数据版本，26.3 写出的 id/properties 必须还原成旧字段名。
                for (var key : java.util.Map.of("id", "Name", "properties", "Properties").entrySet()) {
                    if (stateTag.contains(key.getKey())) {
                        stateTag.put(key.getValue(), stateTag.get(key.getKey()));
                        stateTag.remove(key.getKey());
                    }
                }
                palette.add(stateTag);
            }
            region.put("BlockStatePalette", palette);
            region.putLongArray("BlockStates", states.getBackingLongArray());
            var chest = position.copy();
            chest.putString("id", "minecraft:chest");
            chest.putString("CustomName", "untouched");
            var items = new net.minecraft.nbt.ListTag();
            var stack = (net.minecraft.nbt.CompoundTag) ItemStack.OPTIONAL_CODEC.encodeStart(lookup.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), new ItemStack(item("minecraft:stone"), 64)).getOrThrow();
            stack.putByte("Slot", (byte) 0);
            items.add(stack);
            chest.put("Items", items);
            var tiles = new net.minecraft.nbt.ListTag();
            tiles.add(chest);
            region.put("TileEntities", tiles);
            var regions = new net.minecraft.nbt.CompoundTag();
            regions.put("test", region);
            root.put("Regions", regions);
            var file = java.nio.file.Path.of("memory-test.litematic");
            var original = schematicFixture(file, root.copy());
            var replaced = schematicFixture(file, root.copy());
            var replacementItems = new net.minecraft.nbt.ListTag();
            replacementItems.add(ItemStack.OPTIONAL_CODEC.encodeStart(lookup.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), new ItemStack(item("minecraft:glass"), 7)).getOrThrow());
            var replacementEntities = QuickLitematicaContainerMaterialsAccess.getBlockEntities(replaced, "test");
            replacementEntities.get(net.minecraft.core.BlockPos.ZERO).put("Items", replacementItems);
            QuickLitematicaContainerMaterialsAccess.setBlockEntities(replaced, "test", replacementEntities);
            QuickLitematicaContainerMemory.copyContainerItems(replaced, original);
            var actual = QuickLitematicaContainerMaterialsAccess.getBlockEntities(original, "test").get(net.minecraft.core.BlockPos.ZERO);
            assertThat(QuickLitematicaContainerMaterialsAccess.itemStackFromNbt(lookup, QuickLitematicaContainerMaterialsAccess.readCompound(QuickLitematicaContainerMaterialsAccess.readCompoundList(actual, "Items"), 0)).getCount()).isEqualTo(7);
            assertThat(QuickLitematicaContainerMaterialsAccess.readString(actual, "CustomName")).isEqualTo("untouched");
            assertThat(original.getSubRegionContainer("test").get(0, 0, 0)).isEqualTo(block("minecraft:chest").defaultBlockState());
            QuickLitematicaContainerMaterialsAccess.readCompoundList(actual, "Items").clear();
            assertThat(replacementItems.size()).isEqualTo(1);
            assertThat(QuickLitematicaContainerMaterialsAccess.itemStackFromNbt(lookup,
                    QuickLitematicaContainerMaterialsAccess.readCompound(QuickLitematicaContainerMaterialsAccess.readCompoundList(chest, "Items"), 0)).getCount()).isEqualTo(64);
        }
    }

    @Test
    void copiesUseIncreasingNamesWithoutOverwritingExistingFiles(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws java.io.IOException {
        var source = directory.resolve("machine.litematic");
        java.nio.file.Files.writeString(source, "original");
        var existing = directory.resolve("machine_副本_1.litematic");
        java.nio.file.Files.writeString(existing, "existing copy");
        var nbt = new net.minecraft.nbt.CompoundTag();
        nbt.putString("name", "copy");
        var second = QuickLitematicaContainerMaterials.writeNumberedCopy(source, nbt);
        var third = QuickLitematicaContainerMaterials.writeNumberedCopy(source, nbt);
        assertThat(second.getFileName().toString()).isEqualTo("machine_副本_2.litematic");
        assertThat(third.getFileName().toString()).isEqualTo("machine_副本_3.litematic");
        assertThat(java.nio.file.Files.readString(source)).isEqualTo("original");
        assertThat(java.nio.file.Files.readString(existing)).isEqualTo("existing copy");
        assertThat(java.nio.file.Files.size(second)).isPositive();
    }

    @Test
    void changeHighlightDependsOnContentsRatherThanTheExistenceOfARule() {
        ItemStack original = new ItemStack(item("minecraft:shulker_shell"), 64);
        assertThat(QuickLitematicaContainerMaterials.hasStackChange(original, original.copy())).isFalse();
        assertThat(QuickLitematicaContainerMaterials.hasStackChange(original, original.copyWithCount(32))).isTrue();
        ItemStack named = original.copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("renamed"));
        assertThat(QuickLitematicaContainerMaterials.hasStackChange(original, named)).isTrue();
        assertThat(QuickLitematicaContainerMaterials.hasStackChange(original, ItemStack.EMPTY)).isTrue();
    }

    @Test
    void emptyTargetIsAnExplicitOverrideAndNeverBecomesAnAirMaterial() {
        ItemStack source = new ItemStack(item("minecraft:stone"), 32);
        var bulk = roundTrip(source, new ItemStack(item("minecraft:glass")), -1, true);
        var empty = roundTrip(source, ItemStack.EMPTY, 4, false);
        var selected = QuickLitematicaContainerReplacements.findRule(List.of(bulk, empty), source,
                QuickLitematicaContainerReplacements.Scope.CONTAINER, "context", "group", 4);
        assertThat(selected).isSameAs(empty);
        assertThat(QuickLitematicaContainerReplacements.resolveTarget(source, selected).isEmpty()).isTrue();
    }

    @Test
    void containerRulesCannotMatchProjectionOrAnotherContainerContext() {
        ItemStack source = new ItemStack(item("minecraft:oak_stairs"));
        var rule = roundTrip(source, new ItemStack(item("minecraft:stone_stairs")), -1, true);
        assertThat(QuickLitematicaContainerReplacements.findRule(List.of(rule), source,
                QuickLitematicaContainerReplacements.Scope.PROJECTION, "context", "group", 4)).isNull();
        assertThat(QuickLitematicaContainerReplacements.findRule(List.of(rule), source,
                QuickLitematicaContainerReplacements.Scope.CONTAINER, "other", "group", 4)).isNull();
    }

    @Test
    void rejectsOversizedStacksAndDoesNotFallBackPastInvalidSlotRule() {
        ItemStack source = new ItemStack(item("minecraft:stone"), 64);
        var bulk = roundTrip(source, new ItemStack(item("minecraft:glass"), 1), -1, true);
        var oversized = roundTrip(source, new ItemStack(item("minecraft:diamond_axe")), 4, true);
        assertThat(QuickLitematicaContainerReplacements.findRule(List.of(bulk, oversized), source,
                QuickLitematicaContainerReplacements.Scope.CONTAINER, "context", "group", 4)).isNull();
        assertThatThrownBy(() -> roundTrip(source, new ItemStack(item("minecraft:diamond_axe"), 2), 4, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static QuickLitematicaContainerReplacements.ReplacementRule roundTrip(
            ItemStack source, ItemStack target, int slot, boolean preserveCount) {
        String encoded = QuickLitematicaContainerReplacements.encodeContainerRule(
                new QuickLitematicaContainerReplacements.ContainerReplacement(
                        source, target, "context", "group", slot, preserveCount), lookup);
        return QuickLitematicaContainerReplacements.decodeContainerRule(encoded, lookup);
    }
    private static net.minecraft.world.item.Item item(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id));
    }

    private static net.minecraft.world.level.block.Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(id));
    }


    private static fi.dy.masa.litematica.schematic.LitematicaSchematic schematicFixture(
            java.nio.file.Path file, net.minecraft.nbt.CompoundTag nbt) throws ReflectiveOperationException {
        // 声明下限仍接受原生 NBT；主适配版本的构造器接受 MaLiLib 数据标签。
        for (var constructor : fi.dy.masa.litematica.schematic.LitematicaSchematic.class.getConstructors()) {
            var types = constructor.getParameterTypes();
            if (types.length == 3 && types[0] == java.nio.file.Path.class
                    && types[2] == fi.dy.masa.litematica.util.FileType.class) {
                // 原生三参构造器在 converter 初始化前读取旧数据；分开构造与读取。
                var empty = fi.dy.masa.litematica.schematic.LitematicaSchematic.class.getDeclaredConstructor(
                        java.nio.file.Path.class, fi.dy.masa.litematica.util.FileType.class);
                empty.setAccessible(true);
                var schematic = (fi.dy.masa.litematica.schematic.LitematicaSchematic) empty.newInstance(file,
                        fi.dy.masa.litematica.util.FileType.LITEMATICA_SCHEMATIC);
                boolean nativeNbt = types[1] == net.minecraft.nbt.CompoundTag.class;
                var read = fi.dy.masa.litematica.schematic.LitematicaSchematic.class.getDeclaredMethod(
                        nativeNbt ? "readFromNBT" : "readFromData", types[1]);
                read.setAccessible(true);
                assertThat((boolean) read.invoke(schematic, nativeNbt ? nbt
                        : fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt.fromVanillaCompound(nbt))).isTrue();
                return schematic;
            }
        }
        throw new NoSuchMethodException("Litematica schematic data constructor");
    }
}
