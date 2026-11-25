package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIntArray;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaPreviewMeshBuilderTest {
    @Test
    void stackedBeaconsReadTheSharedSkyColumnOnlyOnce() throws Exception {
        var view = org.mockito.Mockito.mock(QuickLitematicaPreview3D.RegionBlockView.class);
        var skyReads = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(
                view.getBlockState(org.mockito.ArgumentMatchers.any(net.minecraft.util.math.BlockPos.class))
        ).thenAnswer(call -> {
            net.minecraft.util.math.BlockPos pos = call.getArgument(0);
            if (pos.getX() == 0 && pos.getZ() == 0 && pos.getY() > 5) {
                skyReads.incrementAndGet();
            }
            return pos.equals(new net.minecraft.util.math.BlockPos(0, 12, 0))
                    ? net.minecraft.block.Blocks.RED_STAINED_GLASS.getDefaultState()
                    : net.minecraft.block.Blocks.AIR.getDefaultState();
        });
        var tag = new NbtCompound();
        tag.putString("Name", "minecraft:beacon");
        var beacons = java.util.List.of(
                new QuickLitematicaPreviewAccess.BlockEntityData(0, 5, 0, tag, new NbtCompound()),
                new QuickLitematicaPreviewAccess.BlockEntityData(0, 0, 0, tag, new NbtCompound())
        );
        var states =
                new java.util.HashMap<net.minecraft.util.math.BlockPos, QuickLitematicaPreviewAccess.BlockStateData>();
        var bounds = new QuickLitematicaPreview3D.Bounds(
                net.minecraft.util.math.BlockPos.ORIGIN,
                new net.minecraft.util.math.BlockPos(0, 16, 0)
        );
        var method = QuickLitematicaPreviewMeshBuilder.class.getDeclaredMethod(
                "recordBeaconBlockStates",
                java.util.Map.class,
                java.util.List.class,
                java.util.List.class,
                QuickLitematicaPreview3D.Bounds.class,
                java.util.concurrent.atomic.AtomicBoolean.class
        );
        method.setAccessible(true);
        method.invoke(
                null,
                states,
                beacons,
                java.util.List.of(view),
                bounds,
                new java.util.concurrent.atomic.AtomicBoolean()
        );
        assertThat(skyReads).hasValue(11);
        assertThat(states).containsOnlyKeys(new net.minecraft.util.math.BlockPos(0, 12, 0));
    }

    @Test
    void translatesLegacyAndModernAttachmentWithoutChangingSource() {
        NbtCompound source = source();
        NbtCompound moved = QuickLitematicaPreviewMeshBuilder.copyEntityNbtAt(source, 23.5, 13.5, 9.96875);
        assertThat(((NbtIntArray) moved.get("block_pos")).getIntArray()).containsExactly(23, 13, 9);
        assertThat(((NbtInt) moved.get("TileX")).intValue()).isEqualTo(23);
        assertThat(((NbtInt) moved.get("TileY")).intValue()).isEqualTo(13);
        assertThat(((NbtInt) moved.get("TileZ")).intValue()).isEqualTo(9);
        assertThat(((NbtIntArray) source.get("block_pos")).getIntArray()).containsExactly(-1094, -94, -575);
        assertThat(((NbtDouble) ((NbtList) source.get("Pos")).get(0)).doubleValue()).isEqualTo(-1093.5);
    }

    @Test
    void preservesAttachmentOffsetAndDoesNotTranslateTwice() {
        NbtCompound source = source();
        source.put("block_pos", new NbtIntArray(new int[] { -1095, -94, -575 }));
        NbtCompound moved = QuickLitematicaPreviewMeshBuilder.copyEntityNbtAt(source, 23.5, 13.5, 9.96875);
        assertThat(((NbtIntArray) moved.get("block_pos")).getIntArray()).containsExactly(22, 13, 9);
        NbtCompound repeated = QuickLitematicaPreviewMeshBuilder.copyEntityNbtAt(moved, 23.5, 13.5, 9.96875);
        assertThat(repeated).isEqualTo(moved);
    }

    private static NbtCompound source() {
        NbtCompound source = new NbtCompound();
        NbtList position = new NbtList();
        position.add(NbtDouble.of(-1093.5));
        position.add(NbtDouble.of(-93.5));
        position.add(NbtDouble.of(-574.03125));
        source.put("Pos", position);
        source.put("block_pos", new NbtIntArray(new int[] { -1094, -94, -575 }));
        source.putInt("TileX", -1094);
        source.putInt("TileY", -94);
        source.putInt("TileZ", -575);
        return source;
    }
}
