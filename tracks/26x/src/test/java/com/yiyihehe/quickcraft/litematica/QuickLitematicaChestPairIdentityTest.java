package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
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

    private static RegistryAccess lookup;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void materialInventoryDoesNotMergeHalfFromAnotherPlacement() throws Exception {
        LitematicaSchematic schematicA = schematic(
                1,
                1,
                Map.of(BlockPos.ZERO, chest(ChestType.LEFT)),
                Map.of(BlockPos.ZERO, chestNbt(Items.STONE, 3))
        );
        LitematicaSchematic schematicB = schematic(
                1,
                1,
                Map.of(BlockPos.ZERO, chest(ChestType.RIGHT)),
                Map.of(BlockPos.ZERO, chestNbt(Items.GLASS, 2))
        );
        SchematicPlacement placementA = placement(schematicA, CURRENT);
        SchematicPlacement placementB = placement(schematicB, ADJACENT);
        ClientLevel level = level();
        Minecraft client = client(level);

        try (var minecraft = mockStatic(Minecraft.class);
                var data = mockStatic(DataManager.class);
                var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placementA, CURRENT, CURRENT), part(placementB, ADJACENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class))).thenAnswer(
                    invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic()
            );

            // 当前半在 A、相邻半在 B：跨 placement 的 27+27 不能拼成 54。
            assertThat(QuickLitematicaContainerVerifier.getMaterialInventoryAt(level, CURRENT, 54)).isNull();
            Container singleHalf = QuickLitematicaContainerVerifier.getMaterialInventoryAt(level, CURRENT, 27);
            assertThat(singleHalf).isNotNull();
            assertThat(singleHalf.getContainerSize()).isEqualTo(27);
        }
    }

    @Test
    void materialInventoryMergesLegalPairInsideOnePlacement() throws Exception {
        LitematicaSchematic schematic = schematic(
                2,
                1,
                Map.of(new BlockPos(0, 0, 0), chest(ChestType.LEFT), new BlockPos(1, 0, 0), chest(ChestType.RIGHT)),
                Map.of(new BlockPos(0, 0, 0), chestNbt(Items.STONE, 3), new BlockPos(1, 0, 0), chestNbt(Items.GLASS, 2))
        );
        SchematicPlacement placement = placement(schematic, CURRENT);
        ClientLevel level = level();
        Minecraft client = client(level);

        try (var minecraft = mockStatic(Minecraft.class);
                var data = mockStatic(DataManager.class);
                var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placement, CURRENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class))).thenAnswer(
                    invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic()
            );

            Container merged = QuickLitematicaContainerVerifier.getMaterialInventoryAt(level, CURRENT, 54);
            assertThat(merged).isNotNull();
            assertThat(merged.getContainerSize()).isEqualTo(54);
            // LEFT 半在 CURRENT：邻半(1,0,0) 先占 0..26，当前半占 27..53。
            assertThat(merged.getItem(0).is(Items.GLASS)).isTrue();
            assertThat(merged.getItem(27).is(Items.STONE)).isTrue();
        }
    }

    @Test
    void materialInventoryRejectsSamePlacementUnpairedNeighbor() throws Exception {
        LitematicaSchematic schematic = schematic(
                2,
                1,
                Map.of(
                        new BlockPos(0, 0, 0),
                        chest(ChestType.LEFT),
                        new BlockPos(1, 0, 0),
                        Blocks.BARREL.defaultBlockState()
                ),
                Map.of(
                        new BlockPos(0, 0, 0),
                        chestNbt(Items.STONE, 3),
                        new BlockPos(1, 0, 0),
                        barrelNbt(Items.GLASS, 2)
                )
        );
        SchematicPlacement placement = placement(schematic, CURRENT);
        ClientLevel level = level();
        Minecraft client = client(level);

        try (var minecraft = mockStatic(Minecraft.class);
                var data = mockStatic(DataManager.class);
                var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placement, CURRENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class))).thenAnswer(
                    invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic()
            );

            assertThat(QuickLitematicaContainerVerifier.getMaterialInventoryAt(level, CURRENT, 54)).isNull();
        }
    }

    @Test
    void unpairedUnreadableNeighborDoesNotInvalidateCurrentExpectedHalf() throws Exception {
        LitematicaSchematic schematicA = schematic(
                1,
                1,
                Map.of(BlockPos.ZERO, chest(ChestType.LEFT)),
                Map.of(BlockPos.ZERO, emptyChestNbt())
        );
        LitematicaSchematic schematicB = schematic(
                1,
                1,
                Map.of(BlockPos.ZERO, Blocks.BARREL.defaultBlockState()),
                Map.of(BlockPos.ZERO, unreadableBarrelNbt())
        );
        SchematicPlacement placementA = placement(schematicA, CURRENT);
        SchematicPlacement placementB = placement(schematicB, ADJACENT);
        ClientLevel level = level();
        Minecraft client = client(level);

        try (var minecraft = mockStatic(Minecraft.class);
                var data = mockStatic(DataManager.class);
                var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placementA, CURRENT, CURRENT), part(placementB, ADJACENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class))).thenAnswer(
                    invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic()
            );

            var expected = QuickLitematicaContainerVerifier.getExpectedContainerAt(level, CURRENT, null);
            assertThat(expected).isNotNull();
            assertThat(expected.inventory().getContainerSize()).isEqualTo(27);
            assertThat(QuickLitematicaContainerInventory.getChestType(expected.state())).isEqualTo(ChestType.LEFT);
        }
    }

    @Test
    void pairedUnreadableNeighborStillFailsClosed() throws Exception {
        LitematicaSchematic schematic = schematic(
                2,
                1,
                Map.of(new BlockPos(0, 0, 0), chest(ChestType.LEFT), new BlockPos(1, 0, 0), chest(ChestType.RIGHT)),
                Map.of(new BlockPos(0, 0, 0), emptyChestNbt(), new BlockPos(1, 0, 0), unreadableChestNbt())
        );
        SchematicPlacement placement = placement(schematic, CURRENT);
        ClientLevel level = level();
        Minecraft client = client(level);

        try (var minecraft = mockStatic(Minecraft.class);
                var data = mockStatic(DataManager.class);
                var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placement, CURRENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class))).thenAnswer(
                    invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic()
            );

            assertThat(QuickLitematicaContainerVerifier.getExpectedContainerAt(level, CURRENT, null)).isNull();
        }
    }

    @Test
    void pairedReadableNeighborMergesExpected54() throws Exception {
        LitematicaSchematic schematic = schematic(
                2,
                1,
                Map.of(new BlockPos(0, 0, 0), chest(ChestType.LEFT), new BlockPos(1, 0, 0), chest(ChestType.RIGHT)),
                Map.of(new BlockPos(0, 0, 0), emptyChestNbt(), new BlockPos(1, 0, 0), emptyChestNbt())
        );
        SchematicPlacement placement = placement(schematic, CURRENT);
        ClientLevel level = level();
        Minecraft client = client(level);

        try (var minecraft = mockStatic(Minecraft.class);
                var data = mockStatic(DataManager.class);
                var globals = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            stubManager(data, List.of(part(placement, CURRENT, ADJACENT)));
            globals.when(() -> QuickLitematicaGlobalReplacements.runtime(any(SchematicPlacement.class))).thenAnswer(
                    invocation -> ((SchematicPlacement) invocation.getArgument(0)).getSchematic()
            );

            var expected = QuickLitematicaContainerVerifier.getExpectedContainerAt(level, CURRENT, null);
            assertThat(expected).isNotNull();
            assertThat(expected.inventory().getContainerSize()).isEqualTo(54);
            assertThat(expected.pos()).isEqualTo(CURRENT);
        }
    }

    private static ClientLevel level() {
        ClientLevel level = mock(ClientLevel.class);
        when(level.registryAccess()).thenReturn(lookup);
        return level;
    }

    private static Minecraft client(ClientLevel level) throws Exception {
        Minecraft client = mock(Minecraft.class);
        Field field = Minecraft.class.getField("level");
        field.set(client, level);
        return client;
    }

    private static org.mockito.MockedStatic<DataManager> stubManager(
            org.mockito.MockedStatic<DataManager> data,
            List<SchematicPlacementManager.PlacementPart> parts
    ) {
        SchematicPlacementManager manager = mock(SchematicPlacementManager.class);
        when(manager.getAllPlacementsTouchingChunk(any())).thenReturn(parts);
        data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
        return data;
    }

    private static SchematicPlacementManager.PlacementPart part(
            SchematicPlacement placement,
            BlockPos min,
            BlockPos max
    ) throws Exception {
        Constructor<?> constructor = SchematicPlacementManager.PlacementPart.class.getConstructors()[0];
        Class<?> boxClass = constructor.getParameterTypes()[2];
        Object box = boxClass.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
                .newInstance(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        return (SchematicPlacementManager.PlacementPart) constructor.newInstance(placement, REGION, box);
    }

    private static SchematicPlacement placement(LitematicaSchematic schematic, BlockPos origin) {
        SubRegionPlacement subRegion = mock(SubRegionPlacement.class);
        when(subRegion.getPos()).thenReturn(BlockPos.ZERO);
        when(subRegion.getRotation()).thenReturn(Rotation.NONE);
        when(subRegion.getMirror()).thenReturn(Mirror.NONE);
        SchematicPlacement placement = mock(SchematicPlacement.class);
        when(placement.getSchematic()).thenReturn(schematic);
        when(placement.getOrigin()).thenReturn(origin);
        when(placement.getRotation()).thenReturn(Rotation.NONE);
        when(placement.getMirror()).thenReturn(Mirror.NONE);
        when(placement.isEnabled()).thenReturn(true);
        when(placement.getEnabledRelativeSubRegionPlacements()).thenReturn(
                com.google.common.collect.ImmutableMap.of(REGION, subRegion)
        );
        when(placement.getRelativeSubRegionPlacement(REGION)).thenReturn(subRegion);
        return placement;
    }

    private static LitematicaSchematic schematic(
            int sizeX,
            int sizeZ,
            Map<BlockPos, BlockState> states,
            Map<BlockPos, CompoundTag> blockEntities
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
        return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, type).setValue(
                ChestBlock.FACING,
                Direction.NORTH
        );
    }

    private static CompoundTag chestNbt(Item item, int count) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:chest");
        ListTag items = new ListTag();
        items.add(itemNbt(item, count));
        nbt.put("Items", items);
        return nbt;
    }

    private static CompoundTag emptyChestNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:chest");
        nbt.put("Items", new ListTag());
        return nbt;
    }

    private static CompoundTag unreadableChestNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:chest");
        ListTag items = new ListTag();
        items.add(new CompoundTag());
        nbt.put("Items", items);
        return nbt;
    }

    private static CompoundTag barrelNbt(Item item, int count) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:barrel");
        ListTag items = new ListTag();
        items.add(itemNbt(item, count));
        nbt.put("Items", items);
        return nbt;
    }

    private static CompoundTag unreadableBarrelNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:barrel");
        ListTag items = new ListTag();
        items.add(new CompoundTag());
        nbt.put("Items", items);
        return nbt;
    }

    private static CompoundTag itemNbt(Item item, int count) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", BuiltInRegistries.ITEM.getKey(item).toString());
        nbt.putInt("count", count);
        nbt.putByte("Slot", (byte) 0);
        return nbt;
    }
}
