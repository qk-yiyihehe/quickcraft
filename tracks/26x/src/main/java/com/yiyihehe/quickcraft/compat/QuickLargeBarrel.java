package com.yiyihehe.quickcraft.compat;

import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public record QuickLargeBarrel(BlockPos first, BlockPos second) {
    /** 桶底相邻只说明可以配对，服务端也可能仍然打开 3 行单桶菜单。 */
    @Nullable
    public static QuickLargeBarrel findPair(Level world, BlockPos pos) {
        if (world == null) {
            return null;
        }
        var state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BarrelBlock)) {
            return null;
        }
        Direction facing = state.getValue(BarrelBlock.FACING);
        Direction towardOther = facing.getOpposite();
        BlockPos otherPos = pos.relative(towardOther);
        var otherState = world.getBlockState(otherPos);
        if (!(otherState.getBlock() instanceof BarrelBlock)
                || otherState.getValue(BarrelBlock.FACING) != towardOther) {
            return null;
        }
        // TIS 将桶口朝轴负方向的半桶放在前 27 格。
        return facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE
                ? new QuickLargeBarrel(pos.immutable(), otherPos)
                : new QuickLargeBarrel(otherPos, pos.immutable());
    }

    public static boolean hasSixRows(AbstractContainerMenu handler) {
        return handler instanceof ChestMenu chest && chest.getRowCount() == 6;
    }

    public BlockPos other(BlockPos pos) {
        return first.equals(pos) ? second : first;
    }
}
