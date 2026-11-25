package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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
    private static DynamicRegistryManager lookup;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        lookup = DynamicRegistryManager.of(Registries.REGISTRIES);
    }

    @AfterEach
    void clearRules() throws Exception {
        setRules(List.of());
    }

    @Test
    void adjacentDoubleChestsKeepTheirOwnHalvesAndRightFirstOrder() throws Exception {
        LitematicaSchematic schematic = schematic();
        ClientWorld world = mock(ClientWorld.class);
        when(world.getRegistryManager()).thenReturn(lookup);

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client(world));
            var data = QuickLitematicaContainerMaterials.ContainerMaterialsData.create(schematic, List.of(REGION), "");

            assertThat(data.groups).hasSize(2);
            var first = group(data.groups, Items.DIAMOND);
            var second = group(data.groups, Items.GLASS);
            assertThat(first.slotStacks()).hasSize(54);
            assertThat(second.slotStacks()).hasSize(54);
            // 右半在菜单 0..26，左半在 27..53；相邻的第二座双箱不能覆盖第一座。
            assertThat(first.slotStacks().get(0).isOf(Items.DIAMOND)).isTrue();
            assertThat(first.slotStacks().get(27).isOf(Items.STONE)).isTrue();
            assertThat(second.slotStacks().get(0).isOf(Items.GLASS)).isTrue();
            assertThat(second.slotStacks().get(27).isOf(Items.DIRT)).isTrue();

        }
    }

    @Test
    void replacementWritesEachTargetBackToItsOwnHalfAndSlot() throws Exception {
        LitematicaSchematic schematic = schematic();
        ClientWorld world = mock(ClientWorld.class);
        when(world.getRegistryManager()).thenReturn(lookup);

        List<ItemStack> firstSlots = emptySlots(54);
        firstSlots.set(0, new ItemStack(Items.DIAMOND));
        firstSlots.set(27, new ItemStack(Items.STONE));
        List<ItemStack> secondSlots = emptySlots(54);
        secondSlots.set(0, new ItemStack(Items.GLASS));
        secondSlots.set(27, new ItemStack(Items.DIRT));
        setRules(
                List.of(
                        new QuickLitematicaContainerReplacements.ContainerReplacement(
                                new ItemStack(Items.DIAMOND),
                                new ItemStack(Items.EMERALD),
                                "test",
                                groupSignature(firstSlots),
                                0,
                                true
                        ),
                        new QuickLitematicaContainerReplacements.ContainerReplacement(
                                new ItemStack(Items.DIRT),
                                new ItemStack(Items.GOLD_INGOT),
                                "test",
                                groupSignature(secondSlots),
                                27,
                                true
                        )
                )
        );

        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client(world));
            LitematicaSchematic replaced = QuickLitematicaContainerMaterials.createContainerReplacementCopy(
                    schematic,
                    List.of(REGION),
                    "test"
            );
            Map<BlockPos, NbtCompound> entities = QuickLitematicaContainerMaterialsAccess.getBlockEntities(
                    replaced,
                    REGION
            );

            assertThat(itemAt(entities.get(RIGHT_ONE), 0)).isEqualTo(Items.EMERALD);
            assertThat(itemAt(entities.get(LEFT_ONE), 0)).isEqualTo(Items.STONE);
            assertThat(itemAt(entities.get(RIGHT_TWO), 0)).isEqualTo(Items.GLASS);
            assertThat(itemAt(entities.get(LEFT_TWO), 0)).isEqualTo(Items.GOLD_INGOT);
        }
    }

    private LitematicaSchematic schematic() throws Exception {
        try (var files = mockStatic(fi.dy.masa.malilib.util.FileUtils.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            files.when(fi.dy.masa.malilib.util.FileUtils::getMinecraftDirectoryAsPath).thenReturn(
                    java.nio.file.Path.of(System.getProperty("user.dir"))
            );
            return schematicFixture(java.nio.file.Path.of("double-chest-test.litematic"), fixture());
        }
    }

    private NbtCompound fixture() throws Exception {
        NbtCompound root = new NbtCompound();
        root.putInt("Version", 6);
        root.putInt("MinecraftDataVersion", LitematicaSchematic.MINECRAFT_DATA_VERSION);
        NbtCompound region = new NbtCompound();
        NbtCompound position = new NbtCompound();
        position.putInt("x", 0);
        position.putInt("y", 0);
        position.putInt("z", 0);
        region.put("Position", position);
        NbtCompound size = new NbtCompound();
        size.putInt("x", 4);
        size.putInt("y", 1);
        size.putInt("z", 1);
        region.put("Size", size);
        LitematicaBlockStateContainer states = new LitematicaBlockStateContainer(4, 1, 1);
        states.set(0, 0, 0, chest(ChestType.LEFT));
        states.set(1, 0, 0, chest(ChestType.RIGHT));
        states.set(2, 0, 0, chest(ChestType.LEFT));
        states.set(3, 0, 0, chest(ChestType.RIGHT));
        region.put("BlockStatePalette", states.getPalette().writeToNBT());
        region.putLongArray("BlockStates", states.getBackingLongArray());
        NbtList tiles = new NbtList();
        // 故意让后一座双箱的左半先出现；旧四邻居扫描会把它和前一座的右半拼在一起。
        tiles.add(chestEntity(LEFT_TWO, Items.DIRT));
        tiles.add(chestEntity(LEFT_ONE, Items.STONE));
        tiles.add(chestEntity(RIGHT_ONE, Items.DIAMOND));
        tiles.add(chestEntity(RIGHT_TWO, Items.GLASS));
        region.put("TileEntities", tiles);
        NbtCompound regions = new NbtCompound();
        regions.put(REGION, region);
        root.put("Regions", regions);
        return root;
    }

    private NbtCompound chestEntity(BlockPos pos, net.minecraft.item.Item item) throws Exception {
        NbtCompound chest = new NbtCompound();
        chest.putInt("x", pos.getX());
        chest.putInt("y", pos.getY());
        chest.putInt("z", pos.getZ());
        chest.putString("id", "minecraft:chest");
        NbtList items = new NbtList();
        NbtCompound stack = (NbtCompound) ItemStack.OPTIONAL_CODEC.encodeStart(
                lookup.getOps(net.minecraft.nbt.NbtOps.INSTANCE),
                new ItemStack(item)
        ).getOrThrow();
        stack.putByte("Slot", (byte) 0);
        items.add(stack);
        chest.put("Items", items);
        return chest;
    }

    private static MinecraftClient client(ClientWorld world) throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        java.lang.reflect.Field field = MinecraftClient.class.getField("world");
        field.set(client, world);
        return client;
    }

    private static BlockState chest(ChestType type) {
        return Blocks.CHEST.getDefaultState().with(ChestBlock.CHEST_TYPE, type).with(
                ChestBlock.FACING,
                Direction.NORTH
        );
    }

    private static QuickLitematicaContainerMaterials.ContainerGroup group(
            List<QuickLitematicaContainerMaterials.ContainerGroup> groups,
            net.minecraft.item.Item item
    ) {
        return groups.stream().filter(group -> !group.slotStacks().isEmpty() && group.slotStacks().get(0).isOf(item))
                .findFirst().orElseThrow();
    }

    private static String groupSignature(List<ItemStack> slots) {
        return QuickLitematicaContainerReplacements.containerGroupSignature(new ItemStack(Items.CHEST), slots);
    }

    private static List<ItemStack> emptySlots(int size) {
        List<ItemStack> slots = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            slots.add(ItemStack.EMPTY);
        }
        return slots;
    }

    private static net.minecraft.item.Item itemAt(NbtCompound chest, int slot) {
        NbtList items = QuickLitematicaContainerMaterialsAccess.readCompoundList(chest, "Items");
        for (int index = 0; index < items.size(); index++) {
            NbtCompound item = QuickLitematicaContainerMaterialsAccess.readCompound(items, index);
            if ((QuickLitematicaContainerMaterialsAccess.readByte(item, "Slot") & 255) == slot) {
                return QuickLitematicaSchematicData.decodeItem(item, DynamicRegistryManager.of(Registries.REGISTRIES))
                        .getItem();
            }
        }
        return Items.AIR;
    }

    private static LitematicaSchematic schematicFixture(java.nio.file.Path file, NbtCompound nbt)
            throws ReflectiveOperationException {
        var constructor = LitematicaSchematic.class.getDeclaredConstructor(
                java.nio.file.Path.class,
                fi.dy.masa.litematica.util.FileType.class
        );
        constructor.setAccessible(true);
        var schematic = (LitematicaSchematic) constructor.newInstance(
                file,
                fi.dy.masa.litematica.util.FileType.LITEMATICA_SCHEMATIC
        );
        var read = LitematicaSchematic.class.getDeclaredMethod("readFromNBT", NbtCompound.class);
        read.setAccessible(true);
        assertThat((boolean) read.invoke(schematic, nbt)).isTrue();
        return schematic;
    }

    private void setRules(List<QuickLitematicaContainerReplacements.ContainerReplacement> rules) throws Exception {
        List<String> entries = new ArrayList<>();
        for (var rule : rules) {
            entries.add(QuickLitematicaContainerReplacements.encodeContainerRule(rule, lookup));
        }
        Method method = QuickLitematicaContainerReplacements.class.getDeclaredMethod(
                "setConfiguredRules",
                QuickLitematicaContainerReplacements.Scope.class,
                List.class
        );
        method.setAccessible(true);
        method.invoke(null, QuickLitematicaContainerReplacements.Scope.CONTAINER, entries);
    }
}
