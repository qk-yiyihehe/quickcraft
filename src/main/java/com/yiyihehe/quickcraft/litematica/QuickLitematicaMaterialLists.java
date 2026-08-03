package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickMaterialCollector;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Litematica 可选桥接：只在确认安装了 Litematica 后调用，避免主客户端入口硬加载投影类。
 */
public final class QuickLitematicaMaterialLists {
    private QuickLitematicaMaterialLists() {
    }

    public static List<QuickMaterialCollector.MaterialRequest> getVisibleMaterialRequests(Player player) {
        List<QuickMaterialCollector.MaterialRequest> requests = new ArrayList<>();
        addRequests(DataManager.getMaterialList(), player, requests);

        return requests;
    }

    public static boolean hasVisibleMaterialLists(Player player) {
        return isHudVisible(DataManager.getMaterialList());
    }

    private static void addRequests(MaterialListBase materialList,
                                    Player player,
                                    List<QuickMaterialCollector.MaterialRequest> requests) {
        if (!isHudVisible(materialList)) {
            return;
        }

        if (materialList instanceof QuickLitematicaContainerMaterials.ContainerMaterialRequestSource source) {
            // 容器材料列表返回总需求；没有替换规则时会原样返回物品，避免后续再扣一次玩家库存。
            requests.addAll(source.quickcraft$getReplacementMaterialRequests());
            return;
        }

        MaterialListUtils.updateAvailableCounts(materialList.getMaterialsAll(), player);
        for (MaterialListEntry entry : getIgnoredFilteredEntries(materialList)) {
            int missing = materialList.getMultiplier() == 1
                    ? entry.getCountMissing()
                    : materialList.getMultiplier() * entry.getCountTotal();
            requests.add(new QuickMaterialCollector.MaterialRequest(entry.getStack().copy(), Math.max(0, missing)));
        }
    }

    private static List<MaterialListEntry> getIgnoredFilteredEntries(MaterialListBase materialList) {
        try {
            Field field = MaterialListBase.class.getDeclaredField("materialListPreFiltered");
            field.setAccessible(true);
            Object value = field.get(materialList);
            if (value instanceof List<?> list) {
                List<MaterialListEntry> entries = new ArrayList<>();
                for (Object object : list) {
                    if (object instanceof MaterialListEntry entry) {
                        entries.add(entry);
                    }
                }
                return entries;
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // 反射失败时回退到 Litematica 的缺失列表，至少保持普通单 HUD 场景可用。
        }

        return materialList.getMaterialsMissingOnly(true);
    }

    private static boolean isHudVisible(MaterialListBase materialList) {
        return materialList != null && materialList.getHudRenderer().getShouldRenderCustom();
    }
}
