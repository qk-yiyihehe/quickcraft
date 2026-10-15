package com.yiyihehe.quickcraft.config;

import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 名称来自游戏 lang；配置仍保存 ID，使切换语言后同一份目标继续有效。 */
public final class QuickCraftAnvilEnchantments {
    public record Entry(String name, int level) {}
    private static final String PREFIX = "enchantment.";
    private static final Map<String, String> ENGLISH_NAMES = loadNames();

    private QuickCraftAnvilEnchantments() {}

    public static Entry parse(String text) {
        String value = text.trim();
        int end = value.length();
        int start = end;
        while (start > 0 && value.charAt(start - 1) >= '0' && value.charAt(start - 1) <= '9') start--;
        if (start == end || start == 0) throw new IllegalArgumentException("config");
        String name = value.substring(0, start).trim();
        if (name.endsWith("=")) name = name.substring(0, name.length() - 1).trim();
        if (name.isEmpty() || name.contains("=")) throw new IllegalArgumentException("config");
        try { return new Entry(name, Integer.parseInt(value.substring(start))); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("config"); }
    }

    public static String canonical(String text) {
        try {
            Entry entry = parse(text);
            String name = entry.name;
            for (var known : ENGLISH_NAMES.entrySet()) {
                String translated = Language.getInstance().getOrDefault(known.getKey());
                if (translated.equals(name) || known.getValue().equalsIgnoreCase(name)) {
                    String id = known.getKey().substring(PREFIX.length());
                    int dot = id.indexOf('.');
                    name = id.substring(0, dot) + ':' + id.substring(dot + 1);
                    break;
                }
            }
            if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
            return name + '=' + entry.level;
        } catch (IllegalArgumentException invalid) {
            // 无效输入留在编辑器里供修正，开始规划时统一校验，不能悄悄改成另一条附魔。
            return text;
        }
    }

    public static String display(String text) {
        try {
            Entry entry = parse(canonical(text));
            String id = entry.name.contains(":") ? entry.name : "minecraft:" + entry.name;
            String key = PREFIX + id.replace(':', '.');
            String name = Language.getInstance().has(key) ? Language.getInstance().getOrDefault(key) : entry.name;
            return name + entry.level;
        } catch (IllegalArgumentException invalid) {
            return text;
        }
    }

    private static Map<String, String> loadNames() {
        var stream = QuickCraftAnvilEnchantments.class.getResourceAsStream("/assets/minecraft/lang/en_us.json");
        if (stream == null) return Map.of();
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Map<String, String> names = new LinkedHashMap<>();
            for (var entry : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                if (entry.getKey().startsWith(PREFIX) && !entry.getKey().startsWith("enchantment.level.")) {
                    names.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return Map.copyOf(names);
        } catch (java.io.IOException invalid) {
            return Map.of();
        }
    }
}
