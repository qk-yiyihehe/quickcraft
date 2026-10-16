package com.yiyihehe.quickcraft.litematica;

import com.google.common.collect.ImmutableList;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListPlacementAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListSchematicAccessor;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaMaterialNavigationTest {
    @BeforeAll
    static void initializeMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void wrapsEveryButtonWithoutHidingOperations() {
        var layout = QuickLitematicaMaterialLayout.rows(100, 0, new int[] {40, 50, 70});
        assertThat(layout.slots()).containsExactly(
                new QuickLitematicaMaterialLayout.Slot(0, 0, 40),
                new QuickLitematicaMaterialLayout.Slot(42, 0, 50),
                new QuickLitematicaMaterialLayout.Slot(0, 24, 70));
        assertThat(layout.height()).isEqualTo(48);
    }

    @Test
    void reservesMultiplierSpaceOnlyOnFirstRowAndClampsOversizedButtons() {
        var reserved = QuickLitematicaMaterialLayout.rows(100, 70, new int[] {40, 50});
        assertThat(reserved.slots()).containsExactly(
                new QuickLitematicaMaterialLayout.Slot(0, 24, 40),
                new QuickLitematicaMaterialLayout.Slot(42, 24, 50));
        var oversized = QuickLitematicaMaterialLayout.rows(100, 0, new int[] {200, 30});
        assertThat(oversized.slots()).containsExactly(
                new QuickLitematicaMaterialLayout.Slot(0, 0, 100),
                new QuickLitematicaMaterialLayout.Slot(0, 24, 30));
        assertThat(oversized.height()).isEqualTo(48);
    }

    @Test
    void reservesTotalLineAboveBottomNavigationEvenWhenButtonsWrap() {
        var wide = QuickLitematicaMaterialLayout.footer(300, 600, new int[] {90, 90, 90, 90, 90});
        assertThat(wide.buttonsY()).isEqualTo(276);
        assertThat(wide.totalY() + 12).isLessThanOrEqualTo(wide.buttonsY());
        assertThat(wide.listBottom()).isLessThan(wide.totalY());
        var narrow = QuickLitematicaMaterialLayout.footer(300, 200, new int[] {90, 90, 90, 90, 90});
        assertThat(narrow.buttonsY()).isEqualTo(228);
        assertThat(narrow.totalY() + 12).isLessThanOrEqualTo(narrow.buttonsY());
        assertThat(narrow.listBottom()).isLessThan(narrow.totalY());
    }

    @Test
    void keepsBatchReplacementAndClearSelectionTogetherWhenWrapping() {
        var layout = QuickLitematicaMaterialLayout.rows(200, 0, new int[] {90, 60, 60}, 2);
        assertThat(layout.slots()).containsExactly(
                new QuickLitematicaMaterialLayout.Slot(0, 0, 90),
                new QuickLitematicaMaterialLayout.Slot(0, 24, 60),
                new QuickLitematicaMaterialLayout.Slot(62, 24, 60));
        var tiny = QuickLitematicaMaterialLayout.rows(50, 0, new int[] {40, 40}, 2);
        assertThat(tiny.slots().getLast().y()).isEqualTo(24);
    }

    @Test
    void repeatedScreenInitializationKeepsFileSourceAndRegionScope() {
        var schematic = mock(LitematicaSchematic.class);
        var list = mock(MaterialListBase.class, withSettings().extraInterfaces(LitematicaMaterialListSchematicAccessor.class));
        var source = (LitematicaMaterialListSchematicAccessor) list;
        when(source.quickcraft$getSchematic()).thenReturn(schematic);
        when(source.quickcraft$getRegions()).thenReturn(ImmutableList.of("selected"));
        var screen = screen(list);
        var first = QuickLitematicaMaterialNavigation.bind(screen);
        assertThat(QuickLitematicaMaterialNavigation.bind(screen)).isSameAs(first);
        assertThat(first.schematic).isSameAs(schematic);
        assertThat(first.regions).containsExactly("selected");
        assertThat(first.world).isFalse();
        assertThat(first.placement).isNull();
        assertThat(first.views(QuickLitematicaMaterialNavigation.View.ENTITIES)).contains(QuickLitematicaMaterialNavigation.View.ENTITIES);
    }

    @Test
    void worldSourceKeepsItsPlacementInsteadOfGuessingFromFileName() {
        var placement = mock(SchematicPlacement.class);
        var schematic = mock(LitematicaSchematic.class);
        when(placement.getSchematic()).thenReturn(schematic);
        when(placement.getEnabledRelativeSubRegionPlacements()).thenReturn(ImmutableMap.of("enabled", mock(SubRegionPlacement.class)));
        var list = mock(MaterialListBase.class, withSettings().extraInterfaces(LitematicaMaterialListPlacementAccessor.class));
        when(((LitematicaMaterialListPlacementAccessor) list).quickcraft$getPlacement()).thenReturn(placement);
        var navigation = QuickLitematicaMaterialNavigation.bind(screen(list));
        assertThat(navigation.world).isTrue();
        assertThat(navigation.placement).isSameAs(placement);
        assertThat(navigation.schematic).isSameAs(schematic);
        assertThat(navigation.regions).containsExactly("enabled");
        assertThat(navigation.views(QuickLitematicaMaterialNavigation.View.ENTITIES)).doesNotContain(QuickLitematicaMaterialNavigation.View.ENTITIES);
    }

    private static GuiMaterialList screen(MaterialListBase list) {
        var screen = mock(GuiMaterialList.class, withSettings().extraInterfaces(QuickLitematicaMaterialNavigation.NavigationScreen.class));
        when(screen.getMaterialList()).thenReturn(list);
        var access = (QuickLitematicaMaterialNavigation.NavigationScreen) screen;
        var navigation = new AtomicReference<QuickLitematicaMaterialNavigation>();
        when(access.quickcraft$getNavigation()).thenAnswer(ignored -> navigation.get());
        doAnswer(invocation -> { navigation.set(invocation.getArgument(0)); return null; }).when(access).quickcraft$setNavigation(any());
        return screen;
    }
}
