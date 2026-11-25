package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ExpectedContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.WorldUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ChunkAccess;
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
        ClientLevel world = chestWorld(first, second, Direction.NORTH);
        SimpleContainer expected = new SimpleContainer(54);
        expected.setItem(0, new ItemStack(Items.STONE, 5));
        SimpleContainer found = new SimpleContainer(54);
        found.setItem(0, new ItemStack(Items.STONE, 3));
        Minecraft client = mock(Minecraft.class);
        Field worldField = Minecraft.class.getField("level");
        worldField.set(client, world);
        var host = mock(QuickLitematicaContainerVerification.Host.class);
        when(host.quickcraft$verificationSchematicPlacement()).thenReturn(mock(SchematicPlacement.class));
        var verification = new QuickLitematicaContainerVerification(host);

        try (var minecraft = mockStatic(Minecraft.class);
                var worldUtils = mockStatic(WorldUtils.class);
                var verifier = mockStatic(QuickLitematicaContainerVerifier.class);
                var replacements = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            worldUtils.when(() -> WorldUtils.getBestWorld(client)).thenReturn(world);
            verifier.when(QuickLitematicaContainerVerifier::isEnabled).thenReturn(true);
            verifier.when(() -> QuickLitematicaContainerVerifier.isMatchingDoubleChest(any(), any())).thenReturn(true);
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.getExpectedContainerAt(
                            world,
                            first,
                            host.quickcraft$verificationSchematicPlacement()
                    )
            ).thenReturn(new ExpectedContainer(first, Blocks.CHEST.defaultBlockState(), null, expected, Set.of()));
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.getExpectedContainerAt(
                            world,
                            second,
                            host.quickcraft$verificationSchematicPlacement()
                    )
            ).thenReturn(new ExpectedContainer(second, Blocks.CHEST.defaultBlockState(), null, expected, Set.of()));
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
        ClientLevel world = chestWorld(first, second, Direction.NORTH);
        BlockState left = chest(Blocks.CHEST.defaultBlockState(), ChestType.LEFT, Direction.NORTH);
        BlockState right = chest(Blocks.CHEST.defaultBlockState(), ChestType.RIGHT, Direction.NORTH);
        SimpleContainer expected27First = new SimpleContainer(27);
        expected27First.setItem(0, new ItemStack(Items.STONE, 5));
        SimpleContainer expected27Second = new SimpleContainer(27);
        expected27Second.setItem(0, new ItemStack(Items.STONE, 5));
        SimpleContainer found27First = new SimpleContainer(27);
        found27First.setItem(0, new ItemStack(Items.STONE, 3));
        SimpleContainer found27Second = new SimpleContainer(27);
        found27Second.setItem(0, new ItemStack(Items.STONE, 3));
        SimpleContainer expected54 = new SimpleContainer(54);
        expected54.setItem(0, new ItemStack(Items.STONE, 5));
        SimpleContainer found54 = new SimpleContainer(54);
        found54.setItem(0, new ItemStack(Items.STONE, 3));
        BlockEntity foundEntityFirst = mock(BlockEntity.class, withSettings().extraInterfaces(Container.class));
        BlockEntity foundEntitySecond = mock(BlockEntity.class, withSettings().extraInterfaces(Container.class));
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getBlockEntity(first)).thenReturn(foundEntityFirst);
        when(chunk.getBlockEntity(second)).thenReturn(foundEntitySecond);
        when(chunk.getBlockState(first)).thenReturn(left);
        when(chunk.getBlockState(second)).thenReturn(right);
        Minecraft client = mock(Minecraft.class);
        Field worldField = Minecraft.class.getField("level");
        worldField.set(client, world);
        var host = mock(QuickLitematicaContainerVerification.Host.class);
        when(host.quickcraft$verificationSchematicPlacement()).thenReturn(mock(SchematicPlacement.class));
        var verification = new QuickLitematicaContainerVerification(host);

        try (var minecraft = mockStatic(Minecraft.class);
                var worldUtils = mockStatic(WorldUtils.class);
                var verifier = mockStatic(QuickLitematicaContainerVerifier.class);
                var replacements = mockStatic(QuickLitematicaGlobalReplacements.class, CALLS_REAL_METHODS)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            worldUtils.when(() -> WorldUtils.getBestWorld(client)).thenReturn(world);
            verifier.when(QuickLitematicaContainerVerifier::isEnabled).thenReturn(true);
            verifier.when(() -> QuickLitematicaContainerVerifier.isMatchingDoubleChest(any(), any())).thenReturn(true);
            verifier.when(() -> QuickLitematicaContainerVerifier.hasUnreadableExpectedInventory(any(), any()))
                    .thenReturn(false);
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.getActualInventory(
                            world,
                            first,
                            (Container) foundEntityFirst,
                            expected27First
                    )
            ).thenReturn(found27First);
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.getActualInventory(
                            world,
                            second,
                            (Container) foundEntitySecond,
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
                    Level.class,
                    ChunkAccess.class,
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
        Level world = chestWorld(first, second, facing);
        var verification = new QuickLitematicaContainerVerification(
                mock(QuickLitematicaContainerVerification.Host.class)
        );
        assertThat(verification.quickcraft$materialMissingKey(world, first, 54)).isEqualTo(
                verification.quickcraft$materialMissingKey(world, second, 54)
        );
    }

    private static ClientLevel chestWorld(BlockPos first, BlockPos second, Direction facing) {
        BlockState left = chest(Blocks.CHEST.defaultBlockState(), ChestType.LEFT, facing);
        BlockState right = chest(Blocks.CHEST.defaultBlockState(), ChestType.RIGHT, facing);
        ClientLevel world = mock(ClientLevel.class);
        when(world.getBlockState(any())).thenAnswer(invocation -> {
            BlockPos pos = invocation.getArgument(0);
            if (pos.equals(first)) {
                return left;
            }
            if (pos.equals(second)) {
                return right;
            }
            return Blocks.AIR.defaultBlockState();
        });
        return world;
    }

    private static BlockState chest(BlockState state, ChestType type, Direction facing) {
        return state.setValue(ChestBlock.TYPE, type).setValue(ChestBlock.FACING, facing);
    }

    private static int materialGapTotal(QuickLitematicaContainerVerification verification) {
        return verification.quickcraft$getMaterialMissingStacks().stream().mapToInt(ItemStack::getCount).sum();
    }
}
