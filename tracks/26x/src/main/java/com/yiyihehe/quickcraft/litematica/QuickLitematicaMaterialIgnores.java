package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.util.data.ItemType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class QuickLitematicaMaterialIgnores {
    private QuickLitematicaMaterialIgnores() { }

    public interface IgnoredMaterials {
        Set<MaterialListEntry> quickcraft$getIgnoredMaterials();
    }

    public static Set<String> effective(MaterialListBase list) {
        Set<String> values = get(QuickLitematicaMaterialLists.getPersistentContextKey(list));
        // 持久化开关只控制记录读写，当前页面的原生忽略仍须影响 HUD 和收集。
        for (MaterialListEntry entry : ((IgnoredMaterials) list).quickcraft$getIgnoredMaterials()) {
            values.add(new ItemType(entry.getStack(), true, true).toString());
        }
        return values;
    }

    public static Set<String> get(String context) {
        Set<String> values = new HashSet<>();
        if (!QuickCraftConfigs.ProjectionTools.ALLOW_PERSISTENT_MATERIAL_IGNORES.getBooleanValue()) { return values; }
        for (String entry : QuickCraftConfigs.ProjectionTools.MATERIAL_LIST_IGNORES.getStrings()) {
            try {
                var json = JsonParser.parseString(entry).getAsJsonObject();
                if (context.equals(json.get("context").getAsString())) { values.add(json.get("value").getAsString()); }
            } catch (RuntimeException ignored) { }
        }
        return values;
    }

    public static void add(String context, String value) {
        if (!QuickCraftConfigs.ProjectionTools.ALLOW_PERSISTENT_MATERIAL_IGNORES.getBooleanValue() || context.isBlank() || get(context).contains(value)) { return; }
        var entries = new ArrayList<>(QuickCraftConfigs.ProjectionTools.MATERIAL_LIST_IGNORES.getStrings());
        JsonObject json = new JsonObject();
        json.addProperty("context", context);
        json.addProperty("value", value);
        entries.add(json.toString());
        QuickCraftConfigs.ProjectionTools.MATERIAL_LIST_IGNORES.setStrings(entries);
        QuickCraftConfigs.saveToFile();
    }

    public static void clear(String context) {
        if (context.isBlank()) { return; }
        var entries = new ArrayList<>(QuickCraftConfigs.ProjectionTools.MATERIAL_LIST_IGNORES.getStrings());
        entries.removeIf(entry -> {
            try { return context.equals(JsonParser.parseString(entry).getAsJsonObject().get("context").getAsString()); }
            catch (RuntimeException ignored) { return false; }
        });
        QuickCraftConfigs.ProjectionTools.MATERIAL_LIST_IGNORES.setStrings(entries);
        QuickCraftConfigs.saveToFile();
    }
}
