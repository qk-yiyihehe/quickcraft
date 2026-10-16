package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ContainerMismatch;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatch;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.SlotMismatchStatus;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BarrelBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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
            assertThat(remaining.get(key)).extracting(ContainerMismatch::pos)
                    .containsExactly(new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 0, 0));
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
            assertThat(QuickLitematicaContainerVerification.quickcraft$groupContainerMismatches(
                    List.of(base, count, slot, facing, locks, named, matchingContents, found))).hasSize(8);
        }
    }

    private static ContainerMismatch mismatch(int x, BlockState state, int count, int slot, Set<Integer> locks) {
        var expected = new SimpleInventory(27);
        var found = new SimpleInventory(27);
        var stack = new ItemStack(Items.REDSTONE, count);
        expected.setStack(slot, stack);
        return new ContainerMismatch(new BlockPos(x, 0, 0), state, state, slot, MismatchType.MISSING,
                stack, ItemStack.EMPTY, expected, found, locks, Set.of(),
                List.of(new SlotMismatch(slot, SlotMismatchStatus.MISSING, stack, ItemStack.EMPTY)));
    }
}
