package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.BlockMismatchExtension;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 26.1.2 原理图校验差异模型访问适配（字段直接访问）。
 */
public final class QuickLitematicaVerifierAccess {
    private QuickLitematicaVerifierAccess() {
    }

    public static MismatchType getMismatchType(BlockMismatch mismatch) {
        return mismatch.mismatchType;
    }

    public static BlockState getExpectedState(BlockMismatch mismatch) {
        return mismatch.stateExpected;
    }

    public static BlockState getFoundState(BlockMismatch mismatch) {
        return mismatch.stateFound;
    }

    public static BlockMismatchExtension getMismatchExtension(BlockMismatch mismatch) {
        return (BlockMismatchExtension) mismatch;
    }

    public static MismatchType getRenderType(MismatchRenderPos position) {
        return position.type;
    }

    public static BlockPos getRenderPos(MismatchRenderPos position) {
        return position.pos;
    }
}
