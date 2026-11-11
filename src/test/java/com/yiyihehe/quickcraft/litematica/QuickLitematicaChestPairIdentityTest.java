package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 材料侧与期望侧的双箱配对身份回归：真实代码路径读取基线与投影数据，只 mock 数据来源，
 * 不 stub isMatchingDoubleChest、baselineLocation 或期望/材料合并判断本身。
 */
@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaChestPairIdentityTest {
    private static final BlockPos CURRENT = new BlockPos(0, 0, 0);
    private static final BlockPos ADJACENT = new BlockPos(1, 0, 0);
    private static final String REGION = "main";

    private static DynamicRegistryManager lookup;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        lookup = DynamicRegistryManager.of(Registries.REGISTRIES);
    }

    @Test
    void materialInventoryDoesNotMergeHalfFromAnotherPlacement() throws Exception {
        LitematicaSchematic schematicA = schematic(1, 1,
                Map.of(BlockPos.ORIGIN, chest(ChestType.LEFT)),
                Map.of(BlockPos.ORIGIN, chestNbt(Items.STONE, 3)));
        LitematicaSchematic schematicB = schematic(1, 1,
                Map.of(BlockPos.ORIGIN, chest(ChestType.RIGHT)),
                Map.of(BlockPos.ORIGIN, chestNbt(Items.GLASS, 2)));
        SchematicPlacement placementA = placement(schematicA, CURRENT);
        SchematicPlacement placementB = placement(schematicB, ADJACENT);
        ClientWorld world = world();
        MinecraftClient client = client(world);

        try (var minecraft = mockStatic(MinecraftClient.class);
             var data = mockStatic(DataManager.class);
             var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placementA, CURRENT, CURRENT), part(placementB, ADJACENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class)))
                    .thenAnswer(invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic());

            // 当前半在 A、相邻半在 B：跨 placement 的 27+27 不能拼成 54。
            assertThat(QuickLitematicaContainerVerifier.getMaterialInventoryAt(world, CURRENT, 54)).isNull();
            // 单半正常读取不受影响。
            Inventory singleHalf = QuickLitematicaContainerVerifier.getMaterialInventoryAt(world, CURRENT, 27);
            assertThat(singleHalf).isNotNull();
            assertThat(singleHalf.size()).isEqualTo(27);
        }
    }

    @Test
    void materialInventoryMergesLegalPairInsideOnePlacement() throws Exception {
        LitematicaSchematic schematic = schematic(2, 1,
                Map.of(new BlockPos(0, 0, 0), chest(ChestType.LEFT),
                        new BlockPos(1, 0, 0), chest(ChestType.RIGHT)),
                Map.of(new BlockPos(0, 0, 0), chestNbt(Items.STONE, 3),
                        new BlockPos(1, 0, 0), chestNbt(Items.GLASS, 2)));
        SchematicPlacement placement = placement(schematic, CURRENT);
        ClientWorld world = world();
        MinecraftClient client = client(world);

        try (var minecraft = mockStatic(MinecraftClient.class);
             var data = mockStatic(DataManager.class);
             var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placement, CURRENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class)))
                    .thenAnswer(invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic());

            Inventory merged = QuickLitematicaContainerVerifier.getMaterialInventoryAt(world, CURRENT, 54);
            assertThat(merged).isNotNull();
            assertThat(merged.size()).isEqualTo(54);
            // LEFT 半在 CURRENT：邻半(1,0,0) 先占 0..26，当前半占 27..53。
            assertThat(merged.getStack(0).isOf(Items.GLASS)).isTrue();
            assertThat(merged.getStack(27).isOf(Items.STONE)).isTrue();
        }
    }

    @Test
    void materialInventoryRejectsSamePlacementUnpairedNeighbor() throws Exception {
        LitematicaSchematic schematic = schematic(2, 1,
                Map.of(new BlockPos(0, 0, 0), chest(ChestType.LEFT),
                        new BlockPos(1, 0, 0), Blocks.BARREL.getDefaultState()),
                Map.of(new BlockPos(0, 0, 0), chestNbt(Items.STONE, 3),
                        new BlockPos(1, 0, 0), barrelNbt(Items.GLASS, 2)));
        SchematicPlacement placement = placement(schematic, CURRENT);
        ClientWorld world = world();
        MinecraftClient client = client(world);

        try (var minecraft = mockStatic(MinecraftClient.class);
             var data = mockStatic(DataManager.class);
             var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placement, CURRENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class)))
                    .thenAnswer(invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic());

            assertThat(QuickLitematicaContainerVerifier.getMaterialInventoryAt(world, CURRENT, 54)).isNull();
        }
    }

    @Test
    void unpairedUnreadableNeighborDoesNotInvalidateCurrentExpectedHalf() throws Exception {
        LitematicaSchematic schematicA = schematic(1, 1,
                Map.of(BlockPos.ORIGIN, chest(ChestType.LEFT)),
                Map.of(BlockPos.ORIGIN, emptyChestNbt()));
        LitematicaSchematic schematicB = schematic(1, 1,
                Map.of(BlockPos.ORIGIN, Blocks.BARREL.getDefaultState()),
                Map.of(BlockPos.ORIGIN, unreadableBarrelNbt()));
        SchematicPlacement placementA = placement(schematicA, CURRENT);
        SchematicPlacement placementB = placement(schematicB, ADJACENT);
        ClientWorld world = world();
        MinecraftClient client = client(world);

        try (var minecraft = mockStatic(MinecraftClient.class);
             var data = mockStatic(DataManager.class);
             var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placementA, CURRENT, CURRENT), part(placementB, ADJACENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class)))
                    .thenAnswer(invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic());

            var expected = QuickLitematicaContainerVerifier.getExpectedContainerAt(world, CURRENT, null);
            assertThat(expected).isNotNull();
            assertThat(expected.inventory().size()).isEqualTo(27);
            assertThat(QuickLitematicaContainerInventory.getChestType(expected.state())).isEqualTo(ChestType.LEFT);
        }
    }

    @Test
    void pairedUnreadableNeighborStillFailsClosed() throws Exception {
        LitematicaSchematic schematic = schematic(2, 1,
                Map.of(new BlockPos(0, 0, 0), chest(ChestType.LEFT),
                        new BlockPos(1, 0, 0), chest(ChestType.RIGHT)),
                Map.of(new BlockPos(0, 0, 0), emptyChestNbt(),
                        new BlockPos(1, 0, 0), unreadableChestNbt()));
        SchematicPlacement placement = placement(schematic, CURRENT);
        ClientWorld world = world();
        MinecraftClient client = client(world);

        try (var minecraft = mockStatic(MinecraftClient.class);
             var data = mockStatic(DataManager.class);
             var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placement, CURRENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class)))
                    .thenAnswer(invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic());

            assertThat(QuickLitematicaContainerVerifier.getExpectedContainerAt(world, CURRENT, null)).isNull();
        }
    }

    @Test
    void pairedReadableNeighborMergesExpected54() throws Exception {
        LitematicaSchematic schematic = schematic(2, 1,
                Map.of(new BlockPos(0, 0, 0), chest(ChestType.LEFT),
                        new BlockPos(1, 0, 0), chest(ChestType.RIGHT)),
                Map.of(new BlockPos(0, 0, 0), emptyChestNbt(),
                        new BlockPos(1, 0, 0), emptyChestNbt()));
        SchematicPlacement placement = placement(schematic, CURRENT);
        ClientWorld world = world();
        MinecraftClient client = client(world);

        try (var minecraft = mockStatic(MinecraftClient.class);
             var data = mockStatic(DataManager.class);
             var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placement, CURRENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class)))
                    .thenAnswer(invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic());

            var expected = QuickLitematicaContainerVerifier.getExpectedContainerAt(world, CURRENT, null);
            assertThat(expected).isNotNull();
            assertThat(expected.inventory().size()).isEqualTo(54);
            assertThat(expected.pos()).isEqualTo(CURRENT);
        }
    }

    private static ClientWorld world() {
        ClientWorld world = mock(ClientWorld.class);
        when(world.getRegistryManager()).thenReturn(lookup);
        return world;
    }

    private static MinecraftClient client(ClientWorld world) throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        Field field = MinecraftClient.class.getField("world");
        field.set(client, world);
        return client;
    }

    private static org.mockito.MockedStatic<DataManager> stubManager(
            org.mockito.MockedStatic<DataManager> data,
            List<SchematicPlacementManager.PlacementPart> parts) {
        SchematicPlacementManager manager = mock(SchematicPlacementManager.class);
        when(manager.getAllPlacementsTouchingChunk(any())).thenReturn(parts);
        data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
        return data;
    }
    private static SchematicPlacementManager manager(List<SchematicPlacementManager.PlacementPart> parts) {
        SchematicPlacementManager manager = mock(SchematicPlacementManager.class);
        when(manager.getAllPlacementsTouchingChunk(any())).thenReturn(parts);
        return manager;
    }


    private static SchematicPlacementManager.PlacementPart part(SchematicPlacement placement,
                                                                BlockPos min, BlockPos max) throws Exception {
        Constructor<?> constructor = SchematicPlacementManager.PlacementPart.class.getConstructors()[0];
        Class<?> boxClass = constructor.getParameterTypes()[2];
        Object box = boxClass.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
                .newInstance(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        return (SchematicPlacementManager.PlacementPart) constructor.newInstance(placement, REGION, box);
    }

    private static SchematicPlacement placement(LitematicaSchematic schematic, BlockPos origin) {
        SubRegionPlacement subRegion = mock(SubRegionPlacement.class);
        when(subRegion.getPos()).thenReturn(BlockPos.ORIGIN);
        when(subRegion.getRotation()).thenReturn(BlockRotation.NONE);
        when(subRegion.getMirror()).thenReturn(BlockMirror.NONE);
        SchematicPlacement placement = mock(SchematicPlacement.class);
        when(placement.getSchematic()).thenReturn(schematic);
        when(placement.getOrigin()).thenReturn(origin);
        when(placement.getRotation()).thenReturn(BlockRotation.NONE);
        when(placement.getMirror()).thenReturn(BlockMirror.NONE);
        when(placement.isEnabled()).thenReturn(true);
        when(placement.getEnabledRelativeSubRegionPlacements()).thenReturn(com.google.common.collect.ImmutableMap.of(REGION, subRegion));
        when(placement.getRelativeSubRegionPlacement(REGION)).thenReturn(subRegion);
        return placement;
    }

    @SuppressWarnings("unchecked")
    private static LitematicaSchematic schematic(
            int sizeX,
            int sizeZ,
            Map<BlockPos, BlockState> states,
            Map<BlockPos, NbtCompound> blockEntities
    ) {
        LitematicaBlockStateContainer container = new LitematicaBlockStateContainer(sizeX, 1, sizeZ);
        states.forEach((pos, state) -> container.set(pos.getX(), 0, pos.getZ(), state));
        LitematicaSchematic schematic = mock(LitematicaSchematic.class);
        when(schematic.getSubRegionContainer(REGION)).thenReturn(container);
        when(schematic.getAreaSize(REGION)).thenReturn(new BlockPos(sizeX, 1, sizeZ));
        when(schematic.getBlockEntityMapForRegion(REGION)).thenReturn((java.util.Map) blockEntities);
        return schematic;
    }

    private static BlockState chest(ChestType type) {
        return Blocks.CHEST.getDefaultState()
                .with(ChestBlock.CHEST_TYPE, type)
                .with(ChestBlock.FACING, Direction.NORTH);
    }

    private static NbtCompound chestNbt(net.minecraft.item.Item item, int count) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:chest");
        NbtList items = new NbtList();
        items.add(itemNbt(item, count));
        nbt.put("Items", items);
        return nbt;
    }

    private static NbtCompound emptyChestNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:chest");
        nbt.put("Items", new NbtList());
        return nbt;
    }

    private static NbtCompound unreadableChestNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:chest");
        NbtList items = new NbtList();
        items.add(new NbtCompound());
        nbt.put("Items", items);
        return nbt;
    }

    private static NbtCompound barrelNbt(net.minecraft.item.Item item, int count) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:barrel");
        NbtList items = new NbtList();
        items.add(itemNbt(item, count));
        nbt.put("Items", items);
        return nbt;
    }

    private static NbtCompound unreadableBarrelNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:barrel");
        NbtList items = new NbtList();
        items.add(new NbtCompound());
        nbt.put("Items", items);
        return nbt;
    }

    private static NbtCompound itemNbt(net.minecraft.item.Item item, int count) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", net.minecraft.registry.Registries.ITEM.getId(item).toString());
        nbt.putInt("count", count);
        nbt.putByte("Slot", (byte) 0);
        return nbt;
    }
}