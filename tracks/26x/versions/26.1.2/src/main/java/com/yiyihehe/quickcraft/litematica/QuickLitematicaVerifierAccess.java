package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.BlockMismatchExtension;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.malilib.util.IntBoundingBox;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 26.1.2 原理图校验差异模型访问适配（字段直接访问与旧版 IntBoundingBox）。
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

    public static Predicate<BlockPos> placementChunkMatcher(SchematicPlacement placement, int chunkX, int chunkZ) {
        if (placement == null) {
            return pos -> false;
        }
        var boxes = placement.getBoxesWithinChunk(chunkX, chunkZ).values();
        return pos -> {
            for (IntBoundingBox box : boxes) {
                if (box.containsPos(pos)) {
                    return true;
                }
            }
            return false;
        };
    }

    public static int[] getPlacementChunkYRange(SchematicPlacement placement, int chunkX, int chunkZ, int defaultMinY, int defaultMaxY) {
        if (placement == null) {
            return new int[] {defaultMinY, defaultMaxY};
        }
        Map<String, IntBoundingBox> boxes = placement.getBoxesWithinChunk(chunkX, chunkZ);
        if (boxes == null || boxes.isEmpty()) {
            return new int[] {defaultMinY, defaultMaxY};
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (IntBoundingBox box : boxes.values()) {
            minY = Math.min(minY, box.minY());
            maxY = Math.max(maxY, box.maxY());
        }
        return new int[] {minY, maxY};
    }
}
