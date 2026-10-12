package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class QuickLitematicaContainerMemoryTest {
    @BeforeAll
    static void initializeMinecraft() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test
    void fileRuleUpdatesMatchingLoadedObjectsBeforeTheirFirstReplacement() {
        String context = "schematic:C:/build/source.litematic#regions=region";
        var fileObject = mock(LitematicaSchematic.class);
        var loadedObject = mock(LitematicaSchematic.class);
        var unrelated = mock(LitematicaSchematic.class);
        var placement = mock(SchematicPlacement.class);
        var holder = mock(SchematicHolder.class);
        var manager = mock(SchematicPlacementManager.class);
        when(loadedObject.getAreas()).thenReturn(Map.of());
        when(holder.getAllSchematics()).thenReturn(List.of(fileObject, loadedObject, unrelated));
        when(manager.getAllSchematicsPlacements()).thenReturn(List.of(placement));
        var regions = loadedObject.getAreas().keySet();

        try (var data = mockStatic(DataManager.class);
             var schematics = mockStatic(SchematicHolder.class);
             var contexts = mockStatic(QuickLitematicaMaterialLists.class);
             var memory = mockStatic(QuickLitematicaContainerMemory.class,
                     call -> call.getMethod().getName().equals("update") ? null : call.callRealMethod())) {
            data.when(DataManager::getSchematicPlacementManager).thenReturn(manager);
            schematics.when(SchematicHolder::getInstance).thenReturn(holder);
            contexts.when(() -> QuickLitematicaMaterialLists.getContainerReplacementContextKey(fileObject)).thenReturn("other");
            contexts.when(() -> QuickLitematicaMaterialLists.getContainerReplacementContextKey(loadedObject)).thenReturn(context);
            contexts.when(() -> QuickLitematicaMaterialLists.getContainerReplacementContextKey(unrelated)).thenReturn("other");
            contexts.when(() -> QuickLitematicaMaterialLists.getContainerReplacementContextKey(placement)).thenReturn(context);

            QuickLitematicaContainerMemory.rulesChanged(context);

            memory.verify(() -> QuickLitematicaContainerMemory.update(loadedObject, regions, context));
            memory.verify(() -> QuickLitematicaContainerMemory.update(placement));
            verify(unrelated, never()).getAreas();
        }
    }
}
