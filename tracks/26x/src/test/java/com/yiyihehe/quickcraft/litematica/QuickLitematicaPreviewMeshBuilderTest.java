package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.IntArrayTag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaPreviewMeshBuilderTest {
    @Test
    void stackedBeaconsReadTheSharedSkyColumnOnlyOnce() throws Exception {
        var viewType = Class.forName(QuickLitematicaPreviewMeshBuilder.class.getName() + "$RegionBlockView");
        var view = org.mockito.Mockito.mock(viewType);
        var readState = viewType.getDeclaredMethod("getBlockState", net.minecraft.core.BlockPos.class);
        readState.setAccessible(true);
        var skyReads = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(
                readState.invoke(view, org.mockito.ArgumentMatchers.any(net.minecraft.core.BlockPos.class))
        ).thenAnswer(call -> {
            net.minecraft.core.BlockPos pos = call.getArgument(0);
            if (pos.getX() == 0 && pos.getZ() == 0 && pos.getY() > 5) {
                skyReads.incrementAndGet();
            }
            return pos.equals(new net.minecraft.core.BlockPos(0, 12, 0))
                    ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                            net.minecraft.resources.Identifier.withDefaultNamespace("red_stained_glass")
                    ).defaultBlockState() : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        });
        var tag = new CompoundTag();
        tag.putString("Name", "minecraft:beacon");
        var beacons = java.util.List.of(
                new QuickLitematicaPreview3D.BlockEntityData(0, 5, 0, tag, new CompoundTag()),
                new QuickLitematicaPreview3D.BlockEntityData(0, 0, 0, tag, new CompoundTag())
        );
        var states = new java.util.HashMap<net.minecraft.core.BlockPos, QuickLitematicaPreview3D.BlockStateData>();
        var boundsType = Class.forName(QuickLitematicaPreviewMeshBuilder.class.getName() + "$Bounds");
        var constructor = boundsType.getDeclaredConstructor(
                net.minecraft.core.BlockPos.class,
                net.minecraft.core.BlockPos.class
        );
        constructor.setAccessible(true);
        var bounds = constructor.newInstance(
                net.minecraft.core.BlockPos.ZERO,
                new net.minecraft.core.BlockPos(0, 16, 0)
        );
        var method = QuickLitematicaPreviewMeshBuilder.class.getDeclaredMethod(
                "recordBeaconBlockStates",
                java.util.Map.class,
                java.util.List.class,
                java.util.List.class,
                boundsType,
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
        assertThat(states).containsOnlyKeys(new net.minecraft.core.BlockPos(0, 12, 0));
    }

    @Test
    void translatesLegacyAndModernAttachmentWithoutChangingSource() {
        CompoundTag source = source();
        CompoundTag moved = QuickLitematicaPreviewMeshBuilder.copyEntityNbtAt(source, 23.5, 13.5, 9.96875);
        assertThat(((IntArrayTag) moved.get("block_pos")).getAsIntArray()).containsExactly(23, 13, 9);
        assertThat(((IntTag) moved.get("TileX")).intValue()).isEqualTo(23);
        assertThat(((IntTag) moved.get("TileY")).intValue()).isEqualTo(13);
        assertThat(((IntTag) moved.get("TileZ")).intValue()).isEqualTo(9);
        assertThat(((IntArrayTag) source.get("block_pos")).getAsIntArray()).containsExactly(-1094, -94, -575);
        assertThat(((DoubleTag) ((ListTag) source.get("Pos")).get(0)).doubleValue()).isEqualTo(-1093.5);
    }

    @Test
    void preservesAttachmentOffsetAndDoesNotTranslateTwice() {
        CompoundTag source = source();
        source.put("block_pos", new IntArrayTag(new int[] { -1095, -94, -575 }));
        CompoundTag moved = QuickLitematicaPreviewMeshBuilder.copyEntityNbtAt(source, 23.5, 13.5, 9.96875);
        assertThat(((IntArrayTag) moved.get("block_pos")).getAsIntArray()).containsExactly(22, 13, 9);
        CompoundTag repeated = QuickLitematicaPreviewMeshBuilder.copyEntityNbtAt(moved, 23.5, 13.5, 9.96875);
        assertThat(repeated).isEqualTo(moved);
    }

    private static CompoundTag source() {
        CompoundTag source = new CompoundTag();
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(-1093.5));
        position.add(DoubleTag.valueOf(-93.5));
        position.add(DoubleTag.valueOf(-574.03125));
        source.put("Pos", position);
        source.put("block_pos", new IntArrayTag(new int[] { -1094, -94, -575 }));
        source.putInt("TileX", -1094);
        source.putInt("TileY", -94);
        source.putInt("TileZ", -575);
        return source;
    }
}
