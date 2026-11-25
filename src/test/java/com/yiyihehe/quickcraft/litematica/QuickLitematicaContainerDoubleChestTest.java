package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ExpectedContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.WorldUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaContainerDoubleChestTest {
    @Test
    void doubleChestRefreshRecordsMaterialGapOnceForBothPositionsAndRepeatedRefresh() throws Exception {
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(1, 0, 0);
        ClientWorld world = chestWorld(first, second, Direction.NORTH);
        SimpleInventory expected = new SimpleInventory(54);
        expected.setStack(0, new ItemStack(Items.STONE, 5));
        SimpleInventory found = new SimpleInventory(54);
        found.setStack(0, new ItemStack(Items.STONE, 3));
        MinecraftClient client = mock(MinecraftClient.class);
        Field worldField = MinecraftClient.class.getField("world");
        worldField.set(client, world);
        var host = mock(QuickLitematicaContainerVerification.Host.class);
        when(host.quickcraft$verificationSchematicPlacement()).thenReturn(mock(SchematicPlacement.class));
        var verification = new QuickLitematicaContainerVerification(host);

        try (var minecraft = mockStatic(MinecraftClient.class);
                var worldUtils = mockStatic(WorldUtils.class);
                var verifier = mockStatic(QuickLitematicaContainerVerifier.class);
                var replacements = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            worldUtils.when(() -> WorldUtils.getBestWorld(client)).thenReturn(world);
            verifier.when(QuickLitematicaContainerVerifier::isEnabled).thenReturn(true);
            verifier.when(() -> QuickLitematicaContainerVerifier.isMatchingDoubleChest(any(), any())).thenReturn(true);
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.getExpectedContainerAt(
                            world,
                            first,
                            host.quickcraft$verificationSchematicPlacement()
                    )
            ).thenReturn(new ExpectedContainer(first, Blocks.CHEST.getDefaultState(), null, expected, Set.of()));
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.getExpectedContainerAt(
                            world,
                            second,
                            host.quickcraft$verificationSchematicPlacement()
                    )
            ).thenReturn(new ExpectedContainer(second, Blocks.CHEST.getDefaultState(), null, expected, Set.of()));
            verifier.when(() -> QuickLitematicaContainerVerifier.getMaterialInventoryAt(world, first, 54)).thenReturn(
                    expected
            );
            verifier.when(() -> QuickLitematicaContainerVerifier.getMaterialInventoryAt(world, second, 54)).thenReturn(
                    expected
            );
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.findMismatches(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any()
                    )
            ).thenReturn(java.util.List.of());
            replacements.when(QuickLitematicaGlobalReplacements::hasRuntimeRules).thenReturn(true);

            verification.quickcraft$refreshContainerMismatchAt(first, found, Set.of());
            verification.quickcraft$refreshContainerMismatchAt(second, found, Set.of());

            assertThat(verification.quickcraft$getCheckedContainerCount()).isEqualTo(2);
            assertThat(verification.quickcraft$getMaterialMissingStacks()).singleElement().satisfies(stack -> {
                assertThat(stack.getItem()).isEqualTo(Items.STONE);
                assertThat(stack.getCount()).isEqualTo(2);
            });

            verification.quickcraft$refreshContainerMismatchAt(first, found, Set.of());
            verification.quickcraft$refreshContainerMismatchAt(second, found, Set.of());
            assertThat(verification.quickcraft$getMaterialMissingStacks()).hasSize(1);

            replacements.when(QuickLitematicaGlobalReplacements::hasRuntimeRules).thenReturn(false);
            var disabled = new QuickLitematicaContainerVerification(host);
            disabled.quickcraft$refreshContainerMismatchAt(first, found, Set.of());
            assertThat(disabled.quickcraft$getMaterialMissingStacks()).isEmpty();
        }
    }

    @Test
    void initialScanPerHalf27AndRepresentationTransitionsKeepGapCounts() throws Exception {
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(1, 0, 0);
        ClientWorld world = chestWorld(first, second, Direction.NORTH);
        BlockState left = chest(Blocks.CHEST.getDefaultState(), ChestType.LEFT, Direction.NORTH);
        BlockState right = chest(Blocks.CHEST.getDefaultState(), ChestType.RIGHT, Direction.NORTH);
        SimpleInventory expected27First = new SimpleInventory(27);
        expected27First.setStack(0, new ItemStack(Items.STONE, 5));
        SimpleInventory expected27Second = new SimpleInventory(27);
        expected27Second.setStack(0, new ItemStack(Items.STONE, 5));
        SimpleInventory found27First = new SimpleInventory(27);
        found27First.setStack(0, new ItemStack(Items.STONE, 3));
        SimpleInventory found27Second = new SimpleInventory(27);
        found27Second.setStack(0, new ItemStack(Items.STONE, 3));
        SimpleInventory expected54 = new SimpleInventory(54);
        expected54.setStack(0, new ItemStack(Items.STONE, 5));
        SimpleInventory found54 = new SimpleInventory(54);
        found54.setStack(0, new ItemStack(Items.STONE, 3));
        BlockEntity foundEntityFirst = mock(BlockEntity.class, withSettings().extraInterfaces(Inventory.class));
        BlockEntity foundEntitySecond = mock(BlockEntity.class, withSettings().extraInterfaces(Inventory.class));
        Chunk chunk = mock(Chunk.class);
        when(chunk.getBlockEntity(first)).thenReturn(foundEntityFirst);
        when(chunk.getBlockEntity(second)).thenReturn(foundEntitySecond);
        when(chunk.getBlockState(first)).thenReturn(left);
        when(chunk.getBlockState(second)).thenReturn(right);
        MinecraftClient client = mock(MinecraftClient.class);
        Field worldField = MinecraftClient.class.getField("world");
        worldField.set(client, world);
        var host = mock(QuickLitematicaContainerVerification.Host.class);
        when(host.quickcraft$verificationSchematicPlacement()).thenReturn(mock(SchematicPlacement.class));
        var verification = new QuickLitematicaContainerVerification(host);

        try (var minecraft = mockStatic(MinecraftClient.class);
                var worldUtils = mockStatic(WorldUtils.class);
                var verifier = mockStatic(QuickLitematicaContainerVerifier.class);
                var replacements = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            worldUtils.when(() -> WorldUtils.getBestWorld(client)).thenReturn(world);
            verifier.when(QuickLitematicaContainerVerifier::isEnabled).thenReturn(true);
            verifier.when(() -> QuickLitematicaContainerVerifier.isMatchingDoubleChest(any(), any())).thenReturn(true);
            verifier.when(() -> QuickLitematicaContainerVerifier.hasUnreadableExpectedInventory(any(), any()))
                    .thenReturn(false);
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.getActualInventory(
                            world,
                            first,
                            (Inventory) foundEntityFirst,
                            expected27First
                    )
            ).thenReturn(found27First);
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.getActualInventory(
                            world,
                            second,
                            (Inventory) foundEntitySecond,
                            expected27Second
                    )
            ).thenReturn(found27Second);
            verifier.when(() -> QuickLitematicaContainerVerifier.getMaterialInventoryAt(world, first, 27)).thenReturn(
                    expected27First
            );
            verifier.when(() -> QuickLitematicaContainerVerifier.getMaterialInventoryAt(world, second, 27)).thenReturn(
                    expected27Second
            );
            verifier.when(() -> QuickLitematicaContainerVerifier.getMaterialInventoryAt(world, first, 54)).thenReturn(
                    expected54
            );
            verifier.when(() -> QuickLitematicaContainerVerifier.getMaterialInventoryAt(world, second, 54)).thenReturn(
                    expected54
            );
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.getExpectedContainerAt(
                            world,
                            first,
                            host.quickcraft$verificationSchematicPlacement()
                    )
            ).thenReturn(new ExpectedContainer(first, left, null, expected54, Set.of()));
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.getExpectedContainerAt(
                            world,
                            second,
                            host.quickcraft$verificationSchematicPlacement()
                    )
            ).thenReturn(new ExpectedContainer(second, right, null, expected54, Set.of()));
            verifier.when(() -> QuickLitematicaContainerVerifier.getDisabledSlots(any())).thenReturn(Set.of());
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.findMismatches(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any()
                    )
            ).thenReturn(java.util.List.of());
            replacements.when(QuickLitematicaGlobalReplacements::hasRuntimeRules).thenReturn(true);

            Method initialScan = QuickLitematicaContainerVerification.class.getDeclaredMethod(
                    "quickcraft$collectContainerMismatchesDuringVerification",
                    World.class,
                    Chunk.class,
                    BlockPos.class,
                    BlockState.class,
                    BlockEntity.class,
                    ExpectedContainer.class
            );
            initialScan.setAccessible(true);
            initialScan.invoke(
                    verification,
                    world,
                    chunk,
                    first,
                    left,
                    null,
                    new ExpectedContainer(first, left, null, expected27First, Set.of())
            );
            initialScan.invoke(
                    verification,
                    world,
                    chunk,
                    second,
                    right,
                    null,
                    new ExpectedContainer(second, right, null, expected27Second, Set.of())
            );
            assertThat(verification.quickcraft$getMaterialMissingStacks()).hasSize(2);
            assertThat(materialGapTotal(verification)).isEqualTo(4);

            verification.quickcraft$refreshContainerMismatchAt(first, found54, Set.of());
            verification.quickcraft$refreshContainerMismatchAt(second, found54, Set.of());
            assertThat(verification.quickcraft$getMaterialMissingStacks()).hasSize(1);
            assertThat(materialGapTotal(verification)).isEqualTo(2);
            assertThat(verification.quickcraft$getCheckedContainerCount()).isEqualTo(2);

            initialScan.invoke(
                    verification,
                    world,
                    chunk,
                    first,
                    left,
                    null,
                    new ExpectedContainer(first, left, null, expected27First, Set.of())
            );
            initialScan.invoke(
                    verification,
                    world,
                    chunk,
                    second,
                    right,
                    null,
                    new ExpectedContainer(second, right, null, expected27Second, Set.of())
            );
            assertThat(verification.quickcraft$getMaterialMissingStacks()).hasSize(2);
            assertThat(materialGapTotal(verification)).isEqualTo(4);

            verification.quickcraft$refreshContainerMismatchAt(first, found54, Set.of());
            verification.quickcraft$refreshContainerMismatchAt(second, found54, Set.of());
            assertThat(verification.quickcraft$getMaterialMissingStacks()).hasSize(1);
            assertThat(materialGapTotal(verification)).isEqualTo(2);
        }
    }

    @Test
    void materialMissingKeyCanonicalizesRotatedDoubleChest() {
        assertSameKey(Direction.NORTH, new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
        assertSameKey(Direction.EAST, new BlockPos(0, 0, 0), new BlockPos(0, 0, 1));
    }

    private static void assertSameKey(Direction facing, BlockPos first, BlockPos second) {
        World world = chestWorld(first, second, facing);
        var verification = new QuickLitematicaContainerVerification(
                mock(QuickLitematicaContainerVerification.Host.class)
        );
        assertThat(verification.quickcraft$materialMissingKey(world, first, 54)).isEqualTo(
                verification.quickcraft$materialMissingKey(world, second, 54)
        );
    }

    private static ClientWorld chestWorld(BlockPos first, BlockPos second, Direction facing) {
        BlockState left = chest(Blocks.CHEST.getDefaultState(), ChestType.LEFT, facing);
        BlockState right = chest(Blocks.CHEST.getDefaultState(), ChestType.RIGHT, facing);
        ClientWorld world = mock(ClientWorld.class);
        when(world.getBlockState(any())).thenAnswer(invocation -> {
            BlockPos pos = invocation.getArgument(0);
            if (pos.equals(first)) {
                return left;
            }
            if (pos.equals(second)) {
                return right;
            }
            return Blocks.AIR.getDefaultState();
        });
        return world;
    }

    private static BlockState chest(BlockState state, ChestType type, Direction facing) {
        return state.with(ChestBlock.CHEST_TYPE, type).with(ChestBlock.FACING, facing);
    }

    private static int materialGapTotal(QuickLitematicaContainerVerification verification) {
        return verification.quickcraft$getMaterialMissingStacks().stream().mapToInt(ItemStack::getCount).sum();
    }
}
