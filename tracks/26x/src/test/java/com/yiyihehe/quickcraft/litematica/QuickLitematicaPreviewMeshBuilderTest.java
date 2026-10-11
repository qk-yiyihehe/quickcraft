package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.IntArrayTag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewMeshBuilderTest {
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
        source.put("block_pos", new IntArrayTag(new int[]{-1095, -94, -575}));
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
        source.put("block_pos", new IntArrayTag(new int[]{-1094, -94, -575}));
        source.putInt("TileX", -1094);
        source.putInt("TileY", -94);
        source.putInt("TileZ", -575);
        return source;
    }
}
