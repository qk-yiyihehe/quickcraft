package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials.ContainerSummaryEntry;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListPlacementAccessor;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaMaterialIgnoresTest {
    @Test
    void placementInstancesHaveSeparateIgnoreScopes() {
        SchematicPlacement first = mock(SchematicPlacement.class);
        SchematicPlacement second = mock(SchematicPlacement.class);
        when(first.getHashId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        when(second.getHashId()).thenReturn(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        var firstList = mock(
                MaterialListPlacement.class,
                withSettings().extraInterfaces(LitematicaMaterialListPlacementAccessor.class)
        );
        var secondList = mock(
                MaterialListPlacement.class,
                withSettings().extraInterfaces(LitematicaMaterialListPlacementAccessor.class)
        );
        when(((LitematicaMaterialListPlacementAccessor) firstList).quickcraft$getPlacement()).thenReturn(first);
        when(((LitematicaMaterialListPlacementAccessor) secondList).quickcraft$getPlacement()).thenReturn(second);
        assertThat(QuickLitematicaMaterialLists.getPersistentContextKey(firstList)).isNotEqualTo(
                QuickLitematicaMaterialLists.getPersistentContextKey(secondList)
        );
    }

    @Test
    void containerSummaryIgnoreKeepsComponentDistinctRows() {
        var list = new TestMaterialList();
        var plain = new ItemStack(Items.STONE);
        var named = new ItemStack(Items.STONE);
        named.set(DataComponentTypes.CUSTOM_NAME, Text.literal("named"));
        var plainEntry = new ContainerSummaryEntry(plain, 1, 0, List.of(plain));
        var namedEntry = new ContainerSummaryEntry(named, 1, 0, List.of(named));

        try (var inventory = mockStatic(QuickLitematicaMaterialInventory.class)) {
            inventory.when(() -> QuickLitematicaMaterialInventory.countAvailable(any())).thenReturn(0);
            list.setMaterialListEntries(List.of(plainEntry, namedEntry));
            list.ignoreEntry(namedEntry);

            assertThat(list.getMaterialsFiltered(false)).containsExactly(plainEntry);
            assertThat(list.getMaterialsAll()).containsExactly(plainEntry, namedEntry);

            list.clearIgnored();
            assertThat(list.getMaterialsFiltered(false)).containsExactly(plainEntry, namedEntry);
        }
    }

    @Test
    void disablingPersistenceDropsMemoryAndDoesNotRecordWhileDisabled() {
        var options = QuickCraftConfigs.ProjectionTools.PERSIST_PROJECTION_FEATURES;
        var materials = QuickCraftConfigs.ProjectionTools.ALLOW_PERSISTENT_MATERIAL_IGNORES;
        boolean oldMaster = options.getBooleanValue();
        boolean oldMaterials = materials.getBooleanValue();
        try {
            options.setBooleanValue(true);
            materials.setBooleanValue(true);
            QuickLitematicaMaterialIgnores.add("placement:first", "stone");
            assertThat(QuickLitematicaMaterialIgnores.get("placement:second")).isEmpty();
            assertThat(QuickLitematicaMaterialIgnores.get("placement:first")).containsExactly("stone");
            options.setBooleanValue(false);
            QuickLitematicaMaterialIgnores.settingsChanged();
            QuickLitematicaMaterialIgnores.add("placement:first", "dirt");
            options.setBooleanValue(true);
            assertThat(QuickLitematicaMaterialIgnores.get("placement:first")).isEmpty();
            QuickLitematicaMaterialIgnores.add("placement:first", "stone");
            materials.setBooleanValue(false);
            QuickLitematicaMaterialIgnores.settingsChanged();
            QuickLitematicaMaterialIgnores.add("placement:first", "dirt");
            materials.setBooleanValue(true);
            assertThat(QuickLitematicaMaterialIgnores.get("placement:first")).isEmpty();
            QuickLitematicaMaterialIgnores.add("placement:first", "stone");
            QuickLitematicaMaterialIgnores.clearSession();
            assertThat(QuickLitematicaMaterialIgnores.get("placement:first")).isEmpty();
        } finally {
            QuickLitematicaMaterialIgnores.clearSession();
            options.setBooleanValue(oldMaster);
            materials.setBooleanValue(oldMaterials);
        }
    }

    private static final class TestMaterialList extends MaterialListBase {
        @Override
        public String getName() {
            return "test";
        }

        @Override
        public String getTitle() {
            return "test";
        }

        @Override
        public void reCreateMaterialList() {}
    }
}
