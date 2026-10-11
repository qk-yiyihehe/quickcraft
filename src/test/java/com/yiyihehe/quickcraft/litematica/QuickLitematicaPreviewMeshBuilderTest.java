package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIntArray;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewMeshBuilderTest {
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
        source.put("block_pos", new NbtIntArray(new int[]{-1095, -94, -575}));
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
        source.put("block_pos", new NbtIntArray(new int[]{-1094, -94, -575}));
        source.putInt("TileX", -1094);
        source.putInt("TileY", -94);
        source.putInt("TileZ", -575);
        return source;
    }
}
