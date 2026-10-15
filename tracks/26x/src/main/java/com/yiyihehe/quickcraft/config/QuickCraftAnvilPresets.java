package com.yiyihehe.quickcraft.config;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import java.util.List;

/** 每项列表既是预设，也是玩家可持久化修改的目标；不保存运行中的材料和计划。 */
public final class QuickCraftAnvilPresets {
    private static final String PREFIX = "quickcraft.config.crafting";
    public static final ConfigStringList SWORD = preset("anvilSword", "sharpness=5", "looting=3", "sweeping_edge=3");
    public static final ConfigStringList PICKAXE_SILK = preset("anvilPickaxeSilk", "efficiency=5", "silk_touch=1");
    public static final ConfigStringList PICKAXE_FORTUNE = preset("anvilPickaxeFortune", "efficiency=5", "fortune=3");
    public static final ConfigStringList AXE = preset("anvilAxe", "efficiency=5", "silk_touch=1", "sharpness=5");
    public static final ConfigStringList SHOVEL = preset("anvilShovel", "efficiency=5", "silk_touch=1");
    public static final ConfigStringList HOE = preset("anvilHoe", "efficiency=5", "silk_touch=1");
    public static final ConfigStringList HELMET = preset("anvilHelmet", "protection=4", "respiration=3", "aqua_affinity=1");
    public static final ConfigStringList CHESTPLATE = preset("anvilChestplate", "protection=4");
    public static final ConfigStringList LEGGINGS = preset("anvilLeggings", "protection=4", "swift_sneak=3");
    public static final ConfigStringList BOOTS = preset("anvilBoots", "protection=4", "feather_falling=4", "depth_strider=3", "soul_speed=3");
    public static final ConfigStringList ELYTRA = preset("anvilElytra");
    public static final ConfigStringList BOW = new ConfigStringList("anvilBow",
            com.google.common.collect.ImmutableList.of("unbreaking=3", "power=5", "punch=2", "flame=1", "infinity=1")).apply(PREFIX);
    public static final ConfigStringList CROSSBOW = preset("anvilCrossbow", "quick_charge=3", "multishot=1");
    public static final ConfigStringList TRIDENT = preset("anvilTrident", "impaling=5", "loyalty=3", "channeling=1");
    public static final ConfigStringList MACE = preset("anvilMace", "density=5", "wind_burst=3", "fire_aspect=2");
    public static final ConfigStringList FISHING_ROD = preset("anvilFishingRod", "luck_of_the_sea=3", "lure=3");
    public static final ConfigStringList SHIELD = preset("anvilShield");
    public static final ConfigStringList SHEARS = preset("anvilShears", "efficiency=5");
    public static final ConfigStringList SPEAR = preset("anvilSpear", "sharpness=5", "looting=3", "lunge=3");
    public static final List<IConfigBase> OPTIONS = List.of(SWORD, PICKAXE_SILK, PICKAXE_FORTUNE, AXE,
            SHOVEL, HOE, HELMET, CHESTPLATE, LEGGINGS, BOOTS, ELYTRA, BOW, CROSSBOW, TRIDENT,
            MACE, FISHING_ROD, SHIELD, SHEARS, SPEAR);

    private QuickCraftAnvilPresets() {}

    private static ConfigStringList preset(String name, String... extra) {
        var entries = new java.util.ArrayList<>(List.of("unbreaking=3", "mending=1"));
        entries.addAll(List.of(extra));
        return new ConfigStringList(name, com.google.common.collect.ImmutableList.copyOf(entries)).apply(PREFIX);
    }

    static void migrateLegacyDefaults() {
        for (var profile : List.of(HELMET, CHESTPLATE, LEGGINGS, BOOTS)) {
            var oldDefaults = new java.util.ArrayList<>(profile.getDefaultStrings());
            oldDefaults.add("thorns=3");
            var current = profile.getStrings().stream().map(QuickCraftAnvilEnchantments::canonical).toList();
            // 只升级旧默认组合；玩家增删过的自定义目标继续保留。
            if (current.size() == oldDefaults.size()
                    && new java.util.HashSet<>(current).equals(new java.util.HashSet<>(oldDefaults))) {
                profile.setStrings(profile.getDefaultStrings());
            }
        }
    }

    public static ConfigStringList forItem(String id, boolean fortune) {
        if (!id.startsWith("minecraft:")) return null;
        String path = id.substring("minecraft:".length());
        if (path.endsWith("_pickaxe")) return fortune ? PICKAXE_FORTUNE : PICKAXE_SILK;
        if (path.endsWith("_sword")) return SWORD;
        if (path.endsWith("_axe")) return AXE;
        if (path.endsWith("_shovel")) return SHOVEL;
        if (path.endsWith("_hoe")) return HOE;
        if (path.endsWith("_helmet") || path.equals("turtle_helmet")) return HELMET;
        if (path.endsWith("_chestplate")) return CHESTPLATE;
        if (path.endsWith("_leggings")) return LEGGINGS;
        if (path.endsWith("_boots")) return BOOTS;
        if (path.endsWith("_spear")) return SPEAR;
        return switch (path) {
            case "elytra" -> ELYTRA;
            case "bow" -> BOW;
            case "crossbow" -> CROSSBOW;
            case "trident" -> TRIDENT;
            case "mace" -> MACE;
            case "fishing_rod" -> FISHING_ROD;
            case "shield" -> SHIELD;
            case "shears" -> SHEARS;
            default -> null;
        };
    }
}
