package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class QuickLitematicaPreviewStructureIdentityTest {
    @BeforeAll
    static void initializeMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void adapterUsesTheSameContainerNormalizationAsPreviewLoading() {
        try (var preview = mockStatic(QuickLitematicaPreview3D.class)) {
            preview.when(() -> QuickLitematicaPreview3D.isPreviewInventoryHidden("minecraft:chest")).thenReturn(true);
            preview.when(() -> QuickLitematicaPreview3D.hasPreviewRendererForBlockEntity("minecraft:chest")).thenReturn(
                    true
            );
            preview.when(() -> QuickLitematicaPreview3D.inferPreviewBlockEntityId("minecraft:chest", 3955)).thenReturn(
                    "minecraft:chest"
            );

            String complete = QuickLitematicaPreviewAccess.structureFingerprint(schematic(fixture(true, 1L)));
            String preprocessed = QuickLitematicaPreviewAccess.structureFingerprint(schematic(fixture(false, 1L)));
            String changedStructure = QuickLitematicaPreviewAccess.structureFingerprint(schematic(fixture(false, 0L)));

            assertThat(complete).isEqualTo(preprocessed);
            assertThat(changedStructure).isNotEqualTo(preprocessed);
        }
    }

    private static LitematicaSchematic schematic(CompoundTag root) {
        LitematicaSchematic schematic = mock(LitematicaSchematic.class);
        when(schematic.writeToData()).thenReturn(DataConverterNbt.fromVanillaCompound(root));
        return schematic;
    }

    private static CompoundTag fixture(boolean withContainerItems, long blockState) {
        CompoundTag root = new CompoundTag();
        root.putInt("MinecraftDataVersion", 3955);
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);

        CompoundTag region = new CompoundTag();
        region.put("Size", position(1, 1, 1));
        ListTag palette = new ListTag();
        palette.add(state("minecraft:air"));
        palette.add(state("minecraft:chest"));
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", new long[] { blockState });
        ListTag tileEntities = new ListTag();
        if (withContainerItems) {
            CompoundTag chest = new CompoundTag();
            chest.putString("id", "minecraft:chest");
            chest.putInt("x", 0);
            chest.putInt("y", 0);
            chest.putInt("z", 0);
            ListTag items = new ListTag();
            CompoundTag item = new CompoundTag();
            item.putString("id", "minecraft:diamond");
            items.add(item);
            chest.put("Items", items);
            tileEntities.add(chest);
        }
        region.put("TileEntities", tileEntities);

        CompoundTag regions = new CompoundTag();
        regions.put("region", region);
        root.put("Regions", regions);
        return root;
    }

    private static CompoundTag state(String name) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", name);
        return state;
    }

    private static CompoundTag position(int x, int y, int z) {
        CompoundTag position = new CompoundTag();
        position.putInt("x", x);
        position.putInt("y", y);
        position.putInt("z", z);
        return position;
    }
}
