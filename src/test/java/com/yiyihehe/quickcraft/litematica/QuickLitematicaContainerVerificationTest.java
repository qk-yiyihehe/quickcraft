package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ContainerMismatch;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ExpectedContainer;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatch;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatchStatus;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.malilib.util.WorldUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaContainerVerificationTest {
    @Test
    void groupsFourIdenticalContainersAndKeepsIdentityWhenRepresentativeDisappears() {
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(mock(MinecraftClient.class));
            var mismatches = new ArrayList<ContainerMismatch>();
            for (int x = 0; x < 4; x++) {
                mismatches.add(mismatch(x, Blocks.BARREL.getDefaultState(), 64, 0, Set.of()));
            }
            var groups = QuickLitematicaContainerVerification.quickcraft$groupContainerMismatches(mismatches);
            assertThat(groups).hasSize(1);
            assertThat(groups.values().iterator().next()).hasSize(4);
            var key = groups.keySet().iterator().next();
            mismatches.removeFirst();
            var remaining = QuickLitematicaContainerVerification.quickcraft$groupContainerMismatches(mismatches);
            assertThat(remaining).containsOnlyKeys(key);
            assertThat(remaining.get(key)).extracting(ContainerMismatch::pos).containsExactly(
                    new BlockPos(1, 0, 0),
                    new BlockPos(2, 0, 0),
                    new BlockPos(3, 0, 0)
            );
        }
    }

    @Test
    void separatesStateCountSlotComponentsLocksAndMatchingContents() {
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(mock(MinecraftClient.class));
            var state = Blocks.BARREL.getDefaultState();
            var base = mismatch(0, state, 64, 0, Set.of());
            var count = mismatch(1, state, 32, 0, Set.of());
            var slot = mismatch(2, state, 64, 1, Set.of());
            var facing = mismatch(3, state.cycle(BarrelBlock.FACING), 64, 0, Set.of());
            var locks = mismatch(4, state, 64, 0, Set.of(2));
            var named = mismatch(5, state, 64, 0, Set.of());
            named.expectedInventory().getStack(0).set(DataComponentTypes.CUSTOM_NAME, Text.literal("named"));
            var matchingContents = mismatch(6, state, 64, 0, Set.of());
            // 缺失槽相同，但已正确放置的其他槽不同，也必须分组。
            matchingContents.expectedInventory().setStack(2, new ItemStack(Items.STONE));
            matchingContents.foundInventory().setStack(2, new ItemStack(Items.STONE));
            var found = mismatch(7, state, 64, 0, Set.of());
            found.foundInventory().setStack(0, new ItemStack(Items.REDSTONE, 1));
            assertThat(
                    QuickLitematicaContainerVerification.quickcraft$groupContainerMismatches(
                            List.of(base, count, slot, facing, locks, named, matchingContents, found)
                    )
            ).hasSize(8);
        }
    }

    @Test
    void pendingRefreshRunsWhenWorldTimeHasNonZeroRemainder() throws Exception {
        BlockPos pos = new BlockPos(0, 0, 0);
        MinecraftClient client = mock(MinecraftClient.class);
        ClientWorld world = mock(ClientWorld.class);
        Field worldField = MinecraftClient.class.getField("world");
        worldField.set(client, world);
        when(world.getTime()).thenReturn(1002L);

        SimpleInventory expectedInventory = new SimpleInventory(27);
        SimpleInventory foundInventory = new SimpleInventory(27);
        BlockEntity foundBlockEntity = mock(BlockEntity.class, withSettings().extraInterfaces(Inventory.class));
        when(((Inventory) foundBlockEntity).size()).thenReturn(27);
        when(world.getBlockEntity(pos)).thenReturn(foundBlockEntity);

        var host = mock(QuickLitematicaContainerVerification.Host.class);
        when(host.quickcraft$verificationWorldClient()).thenReturn(world);
        var verification = new QuickLitematicaContainerVerification(host);
        Method markPending = QuickLitematicaContainerVerification.class.getDeclaredMethod(
                "quickcraft$markContainerPending",
                BlockPos.class
        );
        markPending.setAccessible(true);
        markPending.invoke(verification, pos);

        try (var minecraft = mockStatic(MinecraftClient.class);
                var worldUtils = mockStatic(WorldUtils.class);
                var verifier = mockStatic(QuickLitematicaContainerVerifier.class, CALLS_REAL_METHODS)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            worldUtils.when(() -> WorldUtils.getBestWorld(client)).thenReturn(world);
            verifier.when(QuickLitematicaContainerVerifier::isEnabled).thenReturn(true);
            verifier.when(() -> QuickLitematicaContainerVerifier.getExpectedContainerAt(world, pos, null)).thenReturn(
                    new ExpectedContainer(pos, Blocks.CHEST.getDefaultState(), null, expectedInventory, Set.of())
            );
            verifier.when(
                    () -> QuickLitematicaContainerVerifier.getActualInventory(
                            world,
                            pos,
                            (Inventory) foundBlockEntity,
                            expectedInventory
                    )
            ).thenReturn(foundInventory);
            verifier.when(() -> QuickLitematicaContainerVerifier.getDisabledSlots(foundBlockEntity)).thenReturn(
                    Set.of()
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
            ).thenReturn(List.of());

            verification.quickcraft$refreshContainerMismatches();
        }

        assertThat(verification.quickcraft$getPendingContainerCount()).isZero();
        assertThat(verification.quickcraft$getCheckedContainerCount()).isEqualTo(1);
    }

    @Test
    void containerMismatchAddsRaycastBlockPositionsWithinLimit() throws Exception {
        BlockPos near = new BlockPos(1, 0, 0);
        BlockPos far = new BlockPos(2, 0, 0);
        var host = mock(QuickLitematicaContainerVerification.Host.class);
        List<MismatchRenderPos> renderPositions = new ArrayList<>();
        List<BlockPos> blockPositions = new ArrayList<>();
        when(host.quickcraft$verificationMismatchPositionsForRender()).thenReturn(renderPositions);
        when(host.quickcraft$verificationMismatchBlockPositionsForRender()).thenReturn(blockPositions);
        var verification = new QuickLitematicaContainerVerification(host);

        Field closest = QuickLitematicaContainerVerification.class.getDeclaredField(
                "quickcraft$containerPositionsClosest"
        );
        closest.setAccessible(true);
        closest.set(
                verification,
                new HashMap<>(Map.of(QuickLitematicaContainerVerifier.WRONG_FILL, List.of(near, far)))
        );

        try (var verifier = mockStatic(QuickLitematicaContainerVerifier.class, CALLS_REAL_METHODS)) {
            verifier.when(QuickLitematicaContainerVerifier::isEnabled).thenReturn(true);
            verification.quickcraft$combineClosestInventoryPositions(new BlockPos(0, 0, 0), 1);
        }

        assertThat(renderPositions).hasSize(1);
        assertThat(renderPositions.getFirst().type).isEqualTo(QuickLitematicaContainerVerifier.WRONG_FILL);
        assertThat(renderPositions.getFirst().pos).isEqualTo(near);
        assertThat(blockPositions).containsExactly(near);
    }

    @Test
    void doubleChestPairRequiresOppositeHalfSameFacingBlockAndProjection() {
        BlockState left = chest(Blocks.CHEST.getDefaultState(), ChestType.LEFT, Direction.NORTH);
        BlockState right = chest(Blocks.CHEST.getDefaultState(), ChestType.RIGHT, Direction.NORTH);

        assertThat(QuickLitematicaContainerVerifier.isMatchingDoubleChest(left, right)).isTrue();
        assertThat(QuickLitematicaContainerVerifier.isMatchingDoubleChest(left, right, false)).isFalse();
        assertThat(QuickLitematicaContainerVerifier.isMatchingDoubleChest(left, left)).isFalse();
        assertThat(
                QuickLitematicaContainerVerifier.isMatchingDoubleChest(
                        left,
                        chest(Blocks.CHEST.getDefaultState(), ChestType.RIGHT, Direction.EAST)
                )
        ).isFalse();
        assertThat(
                QuickLitematicaContainerVerifier.isMatchingDoubleChest(
                        left,
                        chest(Blocks.TRAPPED_CHEST.getDefaultState(), ChestType.RIGHT, Direction.NORTH)
                )
        ).isFalse();
        assertThat(QuickLitematicaContainerVerifier.isMatchingDoubleChest(left, Blocks.BARREL.getDefaultState()))
                .isFalse();
    }

    @Test
    void incompleteLargeChestTemplateIsRejected() {
        World world = mock(World.class);
        BlockPos pos = new BlockPos(0, 0, 0);
        BlockState state = chest(Blocks.CHEST.getDefaultState(), ChestType.LEFT, Direction.NORTH);
        SimpleInventory inventory = new SimpleInventory(27);

        try (var verifier = mockStatic(QuickLitematicaContainerVerifier.class, CALLS_REAL_METHODS)) {
            verifier.when(() -> QuickLitematicaContainerVerifier.getExpectedContainerAt(world, pos)).thenReturn(
                    new ExpectedContainer(pos, state, null, inventory, Set.of())
            );
            assertThat(QuickLitematicaContainerVerifier.getTemplateSnapshotAt(world, pos)).isNull();
        }
    }

    @Test
    void doubleChestPairSurvivesRotation() {
        BlockState left = chest(Blocks.CHEST.getDefaultState(), ChestType.LEFT, Direction.NORTH);
        BlockState right = chest(Blocks.CHEST.getDefaultState(), ChestType.RIGHT, Direction.NORTH);
        for (BlockRotation rotation : BlockRotation.values()) {
            assertThat(
                    QuickLitematicaContainerVerifier.isMatchingDoubleChest(
                            left.rotate(rotation),
                            right.rotate(rotation)
                    )
            ).isTrue();
        }
    }

    private static BlockState chest(BlockState state, ChestType type, Direction facing) {
        return state.with(ChestBlock.CHEST_TYPE, type).with(ChestBlock.FACING, facing);
    }

    private static ContainerMismatch mismatch(int x, BlockState state, int count, int slot, Set<Integer> locks) {
        var expected = new SimpleInventory(27);
        var found = new SimpleInventory(27);
        var stack = new ItemStack(Items.REDSTONE, count);
        expected.setStack(slot, stack);
        return new ContainerMismatch(
                new BlockPos(x, 0, 0),
                state,
                state,
                slot,
                MismatchType.MISSING,
                stack,
                ItemStack.EMPTY,
                expected,
                found,
                locks,
                Set.of(),
                List.of(new SlotMismatch(slot, SlotMismatchStatus.MISSING, stack, ItemStack.EMPTY))
        );
    }
}
