package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickMaterialCollector;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.entity.player.PlayerEntity;

import java.nio.file.Path;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListPlacementAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListSchematicAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Litematica 可选桥接：只在确认安装了 Litematica 后调用，避免主客户端入口硬加载投影类。
 */
public final class QuickLitematicaMaterialLists {
    private QuickLitematicaMaterialLists() {
    }

    public static List<QuickMaterialCollector.MaterialRequest> getVisibleMaterialRequests(PlayerEntity player) {
        List<QuickMaterialCollector.MaterialRequest> requests = new ArrayList<>();
        addRequests(DataManager.getMaterialList(), player, requests);

        return requests;
    }

    public static boolean hasVisibleMaterialLists(PlayerEntity player) {
        return isHudVisible(DataManager.getMaterialList());
    }

    public static int getCollectorSuccessColor() {
        return QuickLitematicaVerifierPalette.correctStateRgb();
    }

    public static int getCollectorShortageColor() {
        return QuickLitematicaVerifierPalette.wrongFillStateRgb();
    }

    private static void addRequests(MaterialListBase materialList,
                                    PlayerEntity player,
                                    List<QuickMaterialCollector.MaterialRequest> requests) {
        if (!isHudVisible(materialList)) {
            return;
        }

        if (materialList instanceof QuickLitematicaContainerMaterials.ContainerMaterialRequestSource source) {
            // 两类材料表都返回总需求，玩家库存统一由收集计划扣除；容器材料表只额外应用替换规则。
            requests.addAll(source.quickcraft$getReplacementMaterialRequests());
            return;
        }

        for (MaterialListEntry entry : QuickLitematicaProjectionMaterials.hud(materialList, false)) {
            int total = Math.max(1, materialList.getMultiplier()) * entry.getCountTotal();
            requests.add(new QuickMaterialCollector.MaterialRequest(entry.getStack().copy(), Math.max(0, total)));
        }
    }

    public static String getPersistentContextKey(MaterialListBase materialList) {
        if (materialList instanceof QuickLitematicaEntityMaterials.EntityMaterialList entities) {
            return getReplacementContextKey(entities.schematic, entities.regions) + "#entity";
        }
        if (materialList instanceof QuickLitematicaContainerMaterials.ContainerReplacementBindingSource source) {
            return source.quickcraft$getPersistentContextKey();
        }
        if (materialList instanceof LitematicaMaterialListPlacementAccessor accessor) {
            SchematicPlacement placement = accessor.quickcraft$getPlacement();
            return getReplacementContextKey(placement.getSchematic(),
                    placement.getEnabledRelativeSubRegionPlacements().keySet()) + "#projection";
        }

        String key = getReplacementContextKey(materialList);
        return key.isEmpty() ? "" : key + "#projection";
    }

    public static String getReplacementContextKey(MaterialListBase materialList) {
        if (materialList instanceof LitematicaMaterialListPlacementAccessor accessor) {
            SchematicPlacement placement = accessor.quickcraft$getPlacement();
            String key = getContainerReplacementContextKey(placement);
            QuickLitematicaContainerReplacements.migrateProjectionContext(getReplacementContextKey(placement), key);
            return key;
        }
        if (materialList instanceof LitematicaMaterialListSchematicAccessor accessor) {
            String key = getContainerReplacementContextKey(accessor.quickcraft$getSchematic());
            QuickLitematicaContainerReplacements.migrateProjectionContext("", key);
            return key;
        }

        return "";
    }

    public static String getContainerReplacementContextKey(SchematicPlacement placement) {
        return placement == null ? "" : getContainerReplacementContextKey(placement.getSchematic());
    }

    public static String getContainerReplacementContextKey(LitematicaSchematic schematic) {
        return schematic == null ? "" : getReplacementContextKey(schematic, schematic.getAreas().keySet());
    }

    public static String getReplacementContextKey(SchematicPlacement placement) {
        return placement == null ? "" : "placement:" + placement.getHashId();
    }

    public static String getReplacementContextKey(LitematicaSchematic schematic, Collection<?> regions) {
        if (schematic == null) {
            return "";
        }

        Path file = QuickLitematicaContainerMaterialsAccess.schematicPath(schematic);
        String identity = file != null
                ? file.toAbsolutePath().toString()
                : schematic.getMetadata().getName()
                + "#instance=" + Integer.toHexString(System.identityHashCode(schematic));
        List<String> regionNames = new ArrayList<>();
        if (regions != null) {
            for (Object region : regions) {
                if (region instanceof String name) {
                    regionNames.add(sanitizeContextPart(name));
                }
            }
        }
        regionNames.sort(Comparator.naturalOrder());
        return "schematic:" + sanitizeContextPart(identity) + "#regions=" + String.join(",", regionNames);
    }

    private static String sanitizeContextPart(String value) {
        return value == null ? "" : value.replace("||", "");
    }

    private static boolean isHudVisible(MaterialListBase materialList) {
        return materialList != null && materialList.getHudRenderer().getShouldRenderCustom();
    }
}
