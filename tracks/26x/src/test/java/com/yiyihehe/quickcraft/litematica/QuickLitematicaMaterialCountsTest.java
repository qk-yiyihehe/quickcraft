package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaMaterialCountsTest {
    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void keepsEntityContributionWhenAnEntityAndBlocksRequireTheSameItem() {
        var nativeRows = List.of(new MaterialListEntry(new ItemStack(Items.OAK_PLANKS), 12, 7, 3, 4),
                new MaterialListEntry(new ItemStack(Items.ITEM_FRAME), 2, 1, 0, 0));
        var blockRows = List.of(new MaterialListEntry(new ItemStack(Items.OAK_PLANKS), 10, 6, 2, 4));
        var entities = QuickLitematicaProjectionMaterials.combineEntries(nativeRows, blockRows, -1);
        assertThat(entities).hasSize(2);
        var planks = entities.stream().filter(e -> e.getStack().is(Items.OAK_PLANKS)).findFirst().orElseThrow();
        assertThat(planks.getCountTotal()).isEqualTo(2);
        assertThat(planks.getCountMissing()).isEqualTo(1);
        assertThat(planks.getCountMismatched()).isEqualTo(1);

        var replaced = QuickLitematicaProjectionMaterials.combineEntries(
                List.of(new MaterialListEntry(new ItemStack(Items.GLASS), 10, 6, 2, 0)), entities, 1);
        assertThat(replaced).hasSize(3);
        assertThat(replaced.stream().mapToInt(MaterialListEntry::getCountTotal).sum()).isEqualTo(14);
    }

    @Test
    void doesNotInventEntitiesForLegacyBlockOnlyLists() {
        var rows = List.of(new MaterialListEntry(new ItemStack(Items.OAK_PLANKS), 10, 6, 2, 0));
        assertThat(QuickLitematicaProjectionMaterials.combineEntries(rows, rows, -1)).isEmpty();
    }
}
