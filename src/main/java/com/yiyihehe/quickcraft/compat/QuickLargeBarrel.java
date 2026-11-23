package com.yiyihehe.quickcraft.compat;

import net.minecraft.block.BarrelBlock;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public record QuickLargeBarrel(BlockPos first, BlockPos second) {
    /** 桶底相邻只说明可以配对，服务端也可能仍然打开 3 行单桶菜单。 */
    @Nullable
    public static QuickLargeBarrel findPair(World world, BlockPos pos) {
        if (world == null) {
            return null;
        }
        var state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BarrelBlock)) {
            return null;
        }
        Direction facing = state.get(BarrelBlock.FACING);
        Direction towardOther = facing.getOpposite();
        BlockPos otherPos = pos.offset(towardOther);
        var otherState = world.getBlockState(otherPos);
        if (!(otherState.getBlock() instanceof BarrelBlock)
                || otherState.get(BarrelBlock.FACING) != towardOther) {
            return null;
        }
        // TIS 将桶口朝轴负方向的半桶放在前 27 格。
        return facing.getDirection() == Direction.AxisDirection.NEGATIVE
                ? new QuickLargeBarrel(pos.toImmutable(), otherPos)
                : new QuickLargeBarrel(otherPos, pos.toImmutable());
    }

    public static boolean hasSixRows(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler chest && chest.getRows() == 6;
    }

    public BlockPos other(BlockPos pos) {
        return first.equals(pos) ? second : first;
    }
}
