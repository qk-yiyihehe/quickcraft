package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** BUG-18：两座紧邻双箱必须各自沿原版连接方向配对，并按右半、左半顺序读写。 */
@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaDoubleChestMaterialsTest {
    private static final String REGION = "main";
    private static final BlockPos LEFT_ONE = new BlockPos(0, 0, 0);
    private static final BlockPos RIGHT_ONE = new BlockPos(1, 0, 0);
    private static final BlockPos LEFT_TWO = new BlockPos(2, 0, 0);
    private static final BlockPos RIGHT_TWO = new BlockPos(3, 0, 0);
    private static RegistryAccess lookup;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @AfterEach
    void clearRules() throws Exception {
        setRules(List.of());
    }

    @Test
    void adjacentDoubleChestsKeepTheirOwnHalvesAndRightFirstOrder() throws Exception {
        LitematicaSchematic schematic = schematic();
        ClientLevel world = mock(ClientLevel.class);
        when(world.registryAccess()).thenReturn(lookup);

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client(world));
            var data = QuickLitematicaContainerMaterials.ContainerMaterialsData.create(
                    schematic, List.of(REGION), "");

            assertThat(data.groups).hasSize(2);
            var first = group(data.groups, Items.DIAMOND);
            var second = group(data.groups, Items.GLASS);
            assertThat(first.slotStacks()).hasSize(54);
            assertThat(second.slotStacks()).hasSize(54);
            assertThat(first.slotStacks().get(0).is(Items.DIAMOND)).isTrue();
            assertThat(first.slotStacks().get(27).is(Items.STONE)).isTrue();
            assertThat(second.slotStacks().get(0).is(Items.GLASS)).isTrue();
            assertThat(second.slotStacks().get(27).is(Items.DIRT)).isTrue();
        }
    }

    @Test
    void replacementWritesEachTargetBackToItsOwnHalfAndSlot() throws Exception {
        LitematicaSchematic schematic = schematic();
        ClientLevel world = mock(ClientLevel.class);
        when(world.registryAccess()).thenReturn(lookup);

        List<ItemStack> firstSlots = emptySlots(54);
        firstSlots.set(0, new ItemStack(Items.DIAMOND));
        firstSlots.set(27, new ItemStack(Items.STONE));
        List<ItemStack> secondSlots = emptySlots(54);
        secondSlots.set(0, new ItemStack(Items.GLASS));
        secondSlots.set(27, new ItemStack(Items.DIRT));
        setRules(List.of(
                new QuickLitematicaContainerReplacements.ContainerReplacement(
                        new ItemStack(Items.DIAMOND), new ItemStack(Items.EMERALD),
                        "test", groupSignature(firstSlots), 0, true),
                new QuickLitematicaContainerReplacements.ContainerReplacement(
                        new ItemStack(Items.DIRT), new ItemStack(Items.GOLD_INGOT),
                        "test", groupSignature(secondSlots), 27, true)
        ));

        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client(world));
            LitematicaSchematic replaced = QuickLitematicaContainerMaterials.createContainerReplacementCopy(
                    schematic, List.of(REGION), "test");
            Map<BlockPos, CompoundTag> entities = QuickLitematicaContainerMaterialsAccess.getBlockEntities(replaced, REGION);

            assertThat(itemAt(entities.get(RIGHT_ONE), 0)).isEqualTo(Items.EMERALD);
            assertThat(itemAt(entities.get(LEFT_ONE), 0)).isEqualTo(Items.STONE);
            assertThat(itemAt(entities.get(RIGHT_TWO), 0)).isEqualTo(Items.GLASS);
            assertThat(itemAt(entities.get(LEFT_TWO), 0)).isEqualTo(Items.GOLD_INGOT);
        }
    }

    private LitematicaSchematic schematic() throws Exception {
        try (var files = mockStatic(fi.dy.masa.malilib.util.FileUtils.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            files.when(fi.dy.masa.malilib.util.FileUtils::getMinecraftDirectory)
                    .thenReturn(java.nio.file.Path.of(System.getProperty("user.dir")));
            return schematicFixture(java.nio.file.Path.of("double-chest-test.litematic"), fixture());
        }
    }

    private CompoundTag fixture() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 6);
        root.putInt("MinecraftDataVersion", LitematicaSchematic.MINECRAFT_DATA_VERSION);
        CompoundTag region = new CompoundTag();
        CompoundTag position = new CompoundTag();
        position.putInt("x", 0);
        position.putInt("y", 0);
        position.putInt("z", 0);
        region.put("Position", position);
        CompoundTag size = new CompoundTag();
        size.putInt("x", 4);
        size.putInt("y", 1);
        size.putInt("z", 1);
        region.put("Size", size);
        LitematicaBlockStateContainer states = new LitematicaBlockStateContainer(4, 1, 1);
        states.set(0, 0, 0, chest(ChestType.LEFT));
        states.set(1, 0, 0, chest(ChestType.RIGHT));
        states.set(2, 0, 0, chest(ChestType.LEFT));
        states.set(3, 0, 0, chest(ChestType.RIGHT));
        ListTag palette = new ListTag();
        for (int index = 0; index < states.getPalette().getPaletteSize(); index++) {
            palette.add(net.minecraft.nbt.NbtUtils.writeBlockState(states.getPalette().getBlockState(index)));
        }
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", states.getBackingLongArray());
        ListTag tiles = new ListTag();
        tiles.add(chestEntity(LEFT_TWO, Items.DIRT));
        tiles.add(chestEntity(LEFT_ONE, Items.STONE));
        tiles.add(chestEntity(RIGHT_ONE, Items.DIAMOND));
        tiles.add(chestEntity(RIGHT_TWO, Items.GLASS));
        region.put("TileEntities", tiles);
        CompoundTag regions = new CompoundTag();
        regions.put(REGION, region);
        root.put("Regions", regions);
        return root;
    }

    private CompoundTag chestEntity(BlockPos pos, Item item) throws Exception {
        CompoundTag chest = new CompoundTag();
        chest.putInt("x", pos.getX());
        chest.putInt("y", pos.getY());
        chest.putInt("z", pos.getZ());
        chest.putString("id", "minecraft:chest");
        ListTag items = new ListTag();
        CompoundTag stack = (CompoundTag) ItemStack.OPTIONAL_CODEC.encodeStart(
                lookup.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), new ItemStack(item)).getOrThrow();
        stack.putByte("Slot", (byte) 0);
        items.add(stack);
        chest.put("Items", items);
        return chest;
    }

    private static Minecraft client(ClientLevel world) throws Exception {
        Minecraft client = mock(Minecraft.class);
        java.lang.reflect.Field field = Minecraft.class.getField("level");
        field.set(client, world);
        return client;
    }

    private static BlockState chest(ChestType type) {
        return Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.TYPE, type)
                .setValue(ChestBlock.FACING, Direction.NORTH);
    }

    private static QuickLitematicaContainerMaterials.ContainerGroup group(
            List<QuickLitematicaContainerMaterials.ContainerGroup> groups, Item item) {
        return groups.stream()
                .filter(group -> !group.slotStacks().isEmpty() && group.slotStacks().get(0).is(item))
                .findFirst().orElseThrow();
    }

    private static String groupSignature(List<ItemStack> slots) {
        return QuickLitematicaContainerReplacements.containerGroupSignature(new ItemStack(Items.CHEST), slots);
    }

    private static List<ItemStack> emptySlots(int size) {
        List<ItemStack> slots = new ArrayList<>(size);
        for (int index = 0; index < size; index++) slots.add(ItemStack.EMPTY);
        return slots;
    }

    private static Item itemAt(CompoundTag chest, int slot) {
        ListTag items = QuickLitematicaContainerMaterialsAccess.readCompoundList(chest, "Items");
        for (int index = 0; index < items.size(); index++) {
            CompoundTag item = QuickLitematicaContainerMaterialsAccess.readCompound(items, index);
            if ((QuickLitematicaContainerMaterialsAccess.readByte(item, "Slot") & 255) == slot) {
                return QuickLitematicaSchematicData.decodeItem(item, RegistryAccess.fromRegistryOfRegistries(
                        BuiltInRegistries.REGISTRY)).getItem();
            }
        }
        return Items.AIR;
    }

    private static LitematicaSchematic schematicFixture(java.nio.file.Path file, CompoundTag nbt)
            throws ReflectiveOperationException {
        for (var constructor : LitematicaSchematic.class.getConstructors()) {
            var types = constructor.getParameterTypes();
            if (types.length == 3 && types[0] == java.nio.file.Path.class
                    && types[2] == fi.dy.masa.litematica.util.FileType.class) {
                var empty = LitematicaSchematic.class.getDeclaredConstructor(
                        java.nio.file.Path.class, fi.dy.masa.litematica.util.FileType.class);
                empty.setAccessible(true);
                var schematic = (LitematicaSchematic) empty.newInstance(file,
                        fi.dy.masa.litematica.util.FileType.LITEMATICA_SCHEMATIC);
                boolean nativeNbt = types[1] == CompoundTag.class;
                var read = LitematicaSchematic.class.getDeclaredMethod(
                        nativeNbt ? "readFromNBT" : "readFromData", types[1]);
                read.setAccessible(true);
                assertThat((boolean) read.invoke(schematic, nativeNbt ? nbt
                        : fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt.fromVanillaCompound(nbt))).isTrue();
                return schematic;
            }
        }
        throw new NoSuchMethodException("Litematica schematic data constructor");
    }

    private void setRules(List<QuickLitematicaContainerReplacements.ContainerReplacement> rules) throws Exception {
        List<String> entries = new ArrayList<>();
        for (var rule : rules) {
            entries.add(QuickLitematicaContainerReplacements.encodeContainerRule(rule, lookup));
        }
        Method method = QuickLitematicaContainerReplacements.class.getDeclaredMethod(
                "setConfiguredRules", QuickLitematicaContainerReplacements.Scope.class, List.class);
        method.setAccessible(true);
        method.invoke(null, QuickLitematicaContainerReplacements.Scope.CONTAINER, entries);
    }
}
