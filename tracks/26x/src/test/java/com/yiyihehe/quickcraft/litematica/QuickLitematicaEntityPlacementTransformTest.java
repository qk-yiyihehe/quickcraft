package com.yiyihehe.quickcraft.litematica;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
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
        CompoundTag nbt = painting(Direction.EAST);
        transform(nbt, Mirror.NONE, Rotation.CLOCKWISE_90, Mirror.NONE, Rotation.NONE);
        assertThat(nbt.getByteOr("facing", (byte) 0)).isEqualTo((byte) Direction.SOUTH.get2DDataValue());
    }

    @Test
    void paintingFacingUsesHorizontalEncodingUnderMirror() throws Exception {
        CompoundTag nbt = painting(Direction.EAST);
        transform(nbt, Mirror.FRONT_BACK, Rotation.NONE, Mirror.NONE, Rotation.NONE);
        assertThat(nbt.getByteOr("facing", (byte) 0)).isEqualTo((byte) Direction.WEST.get2DDataValue());
    }

    @Test
    void nonPaintingLowercaseFacingKeepsSixWayEncoding() throws Exception {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:item_frame");
        nbt.putByte("facing", (byte) Direction.EAST.get3DDataValue());
        transform(nbt, Mirror.NONE, Rotation.CLOCKWISE_90, Mirror.NONE, Rotation.NONE);
        assertThat(nbt.getByteOr("facing", (byte) 0)).isEqualTo((byte) Direction.SOUTH.get3DDataValue());
    }

    private static CompoundTag painting(Direction facing) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:painting");
        nbt.putByte("facing", (byte) facing.get2DDataValue());
        return nbt;
    }

    private static void transform(
            CompoundTag nbt,
            Mirror placementMirror,
            Rotation placementRotation,
            Mirror regionMirror,
            Rotation regionRotation
    ) throws Exception {
        SchematicPlacement placement = mock(SchematicPlacement.class);
        when(placement.getMirror()).thenReturn(placementMirror);
        when(placement.getRotation()).thenReturn(placementRotation);
        SubRegionPlacement subRegion = mock(SubRegionPlacement.class);
        when(subRegion.getMirror()).thenReturn(regionMirror);
        when(subRegion.getRotation()).thenReturn(regionRotation);
        Method method = QuickLitematicaEntityPlacement.class.getDeclaredMethod(
                "transformEntityTreeState", CompoundTag.class, SchematicPlacement.class,
                SubRegionPlacement.class, BlockPos.class, int.class);
        method.setAccessible(true);
        method.invoke(null, nbt, placement, subRegion, BlockPos.ZERO, 0);
    }
}
