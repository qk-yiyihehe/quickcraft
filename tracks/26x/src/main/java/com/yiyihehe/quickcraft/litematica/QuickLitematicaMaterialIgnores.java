package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.util.data.ItemType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** 当前连接内、按投影隔离的材料忽略；不进入配置文件。 */
public final class QuickLitematicaMaterialIgnores {
    private static final Map<String, Set<String>> IGNORED = new HashMap<>();
    private static final Map<MaterialListBase, Set<MaterialListEntry>> RESTORED = new WeakHashMap<>();
    private static final Map<QuickLitematicaContainerMaterials.ContainerMaterialsData, Set<String>> RESTORED_GROUPS = new WeakHashMap<>();

    private QuickLitematicaMaterialIgnores() { }

    public interface IgnoredMaterials {
        Set<MaterialListEntry> quickcraft$getIgnoredMaterials();
    }

    private static boolean enabled() {
        return QuickCraftConfigs.ProjectionTools.PERSIST_PROJECTION_FEATURES.getBooleanValue()
                && QuickCraftConfigs.ProjectionTools.ALLOW_PERSISTENT_MATERIAL_IGNORES.getBooleanValue();
    }

    public static Set<String> effective(MaterialListBase list) {
        Set<String> values = enabled() ? get(QuickLitematicaMaterialLists.getPersistentContextKey(list)) : new HashSet<>();
        for (MaterialListEntry entry : ((IgnoredMaterials) list).quickcraft$getIgnoredMaterials()) {
            values.add(new ItemType(entry.getStack(), true, true).toString());
        }
        return values;
    }

    public static Set<String> get(String context) {
        return enabled() ? new HashSet<>(IGNORED.getOrDefault(context, Set.of())) : new HashSet<>();
    }

    public static void add(String context, String value) {
        if (enabled() && !context.isBlank()) { IGNORED.computeIfAbsent(context, key -> new HashSet<>()).add(value); }
    }

    public static void clear(String context) { IGNORED.remove(context); }

    public static void restore(MaterialListBase list, List<MaterialListEntry> entries) {
        if (!enabled()) { return; }
        var saved = get(QuickLitematicaMaterialLists.getPersistentContextKey(list));
        if (saved.isEmpty()) { return; }
        var nativeIgnored = ((IgnoredMaterials) list).quickcraft$getIgnoredMaterials();
        for (MaterialListEntry entry : entries) {
            if (saved.contains(new ItemType(entry.getStack(), true, true).toString()) && nativeIgnored.add(entry)) {
                RESTORED.computeIfAbsent(list, key -> new HashSet<>()).add(entry);
            }
        }
    }

    static void restoreGroups(QuickLitematicaContainerMaterials.ContainerMaterialsData data) {
        var saved = get(data.persistentContextKey() + "#groups");
        saved.removeAll(data.ignoredGroupSignatures);
        if (!saved.isEmpty()) {
            data.ignoredGroupSignatures.addAll(saved);
            RESTORED_GROUPS.computeIfAbsent(data, key -> new HashSet<>()).addAll(saved);
        }
    }

    public static void settingsChanged() {
        if (enabled()) { return; }
        IGNORED.clear();
        // 只撤销扩展恢复的条目，当前页面由玩家手动忽略的原生状态照常保留。
        RESTORED.forEach((list, entries) -> {
            ((IgnoredMaterials) list).quickcraft$getIgnoredMaterials().removeAll(entries);
            list.refreshPreFilteredList();
            list.recreateFilteredList();
        });
        RESTORED_GROUPS.forEach((data, groups) -> data.ignoredGroupSignatures.removeAll(groups));
        RESTORED.clear();
        RESTORED_GROUPS.clear();
    }

    public static void clearSession() {
        IGNORED.clear();
        RESTORED.clear();
        RESTORED_GROUPS.clear();
    }
}
