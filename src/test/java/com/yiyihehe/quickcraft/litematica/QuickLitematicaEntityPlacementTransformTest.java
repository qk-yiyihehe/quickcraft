package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BUG-39：画的小写 facing 使用二维水平编码，旋转/镜像后按原版方向约定换算。 */
@ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaEntityPlacementTransformTest {
    @Test
    void paintingFacingUsesHorizontalEncodingUnderRotation() throws Exception {
        NbtCompound nbt = painting(Direction.EAST);
        transform(
                nbt,
                net.minecraft.util.BlockMirror.NONE,
                net.minecraft.util.BlockRotation.CLOCKWISE_90,
                net.minecraft.util.BlockMirror.NONE,
                net.minecraft.util.BlockRotation.NONE
        );
        assertThat(QuickLitematicaEntityPlacementAccess.byteValue(nbt, "facing")).isEqualTo((byte) 0);
    }

    @Test
    void paintingFacingUsesHorizontalEncodingUnderMirror() throws Exception {
        NbtCompound nbt = painting(Direction.EAST);
        transform(
                nbt,
                net.minecraft.util.BlockMirror.FRONT_BACK,
                net.minecraft.util.BlockRotation.NONE,
                net.minecraft.util.BlockMirror.NONE,
                net.minecraft.util.BlockRotation.NONE
        );
        assertThat(QuickLitematicaEntityPlacementAccess.byteValue(nbt, "facing")).isEqualTo((byte) 1);
    }

    @Test
    void nonPaintingLowercaseFacingKeepsSixWayEncoding() throws Exception {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:item_frame");
        nbt.putByte("facing", QuickLitematicaEntityPlacementAccess.directionIndex(Direction.EAST));
        transform(
                nbt,
                net.minecraft.util.BlockMirror.NONE,
                net.minecraft.util.BlockRotation.CLOCKWISE_90,
                net.minecraft.util.BlockMirror.NONE,
                net.minecraft.util.BlockRotation.NONE
        );
        assertThat(QuickLitematicaEntityPlacementAccess.byteValue(nbt, "facing")).isEqualTo(
                QuickLitematicaEntityPlacementAccess.directionIndex(Direction.SOUTH)
        );
    }

    private static NbtCompound painting(Direction facing) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:painting");
        nbt.putByte("facing", (byte) switch (facing) {
            case SOUTH -> 0;
            case WEST -> 1;
            case NORTH -> 2;
            default -> 3;
        });
        return nbt;
    }

    private static void transform(
            NbtCompound nbt,
            net.minecraft.util.BlockMirror placementMirror,
            net.minecraft.util.BlockRotation placementRotation,
            net.minecraft.util.BlockMirror regionMirror,
            net.minecraft.util.BlockRotation regionRotation
    ) throws Exception {
        SchematicPlacement placement = mock(SchematicPlacement.class);
        when(placement.getMirror()).thenReturn(placementMirror);
        when(placement.getRotation()).thenReturn(placementRotation);
        SubRegionPlacement subRegion = mock(SubRegionPlacement.class);
        when(subRegion.getMirror()).thenReturn(regionMirror);
        when(subRegion.getRotation()).thenReturn(regionRotation);
        Method method = QuickLitematicaEntityPlacement.class.getDeclaredMethod(
                "transformEntityTreeState",
                NbtCompound.class,
                SchematicPlacement.class,
                SubRegionPlacement.class,
                BlockPos.class,
                int.class
        );
        method.setAccessible(true);
        method.invoke(null, nbt, placement, subRegion, BlockPos.ORIGIN, 0);
    }
}
