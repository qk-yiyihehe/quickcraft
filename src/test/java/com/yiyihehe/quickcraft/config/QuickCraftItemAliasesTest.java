package com.yiyihehe.quickcraft.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftItemAliasesTest {
    @Test
    @DisplayName("中文默认列表使用中文附魔短名")
    void defaultAliasesUseChineseNames() {
        List<String> aliases = QuickCraftItemAliases.getDefaultPriorityAliases("zh_cn");

        assertThat(aliases).contains("附魔鞘翅", "附魔钻石剑", "附魔下界合金镐");
    }

    @Test
    @DisplayName("英文别名和中文别名解析到同一物品")
    void aliasesMatchSameItem() {
        assertThat(QuickCraftItemAliases.matches("附魔钻石剑", "minecraft:diamond_sword", "diamond_sword", "钻石剑", true))
                .isTrue();
        assertThat(QuickCraftItemAliases.matches("Enchanted Diamond Sword", "minecraft:diamond_sword", "diamond_sword", "钻石剑", true))
                .isTrue();
    }

    @Test
    @DisplayName("映射中的装备只匹配附魔物品")
    void mappedGearRequiresEnchantment() {
        assertThat(QuickCraftItemAliases.matches("附魔钻石剑", "minecraft:diamond_sword", "diamond_sword", "钻石剑", false))
                .isFalse();
        assertThat(QuickCraftItemAliases.matches("附魔钻石剑", "minecraft:diamond_sword", "diamond_sword", "钻石剑", true))
                .isTrue();
    }

    @Test
    @DisplayName("普通游戏名称不附带附魔限制")
    void ordinaryDisplayNameMatchesBothEnchantedStates() {
        assertThat(QuickCraftItemAliases.matches("鞘翅", "minecraft:elytra", "elytra", "鞘翅", false))
                .isTrue();
        assertThat(QuickCraftItemAliases.matches("鞘翅", "minecraft:elytra", "elytra", "鞘翅", true))
                .isTrue();
    }

    @Test
    @DisplayName("完整 ID 和简写 ID 不附带附魔限制")
    void directIdsMatchWithoutEnchantmentRestriction() {
        assertThat(QuickCraftItemAliases.matches("minecraft:diamond_sword", "minecraft:diamond_sword", "diamond_sword", "钻石剑", false))
                .isTrue();
        assertThat(QuickCraftItemAliases.matches("diamond_sword", "minecraft:diamond_sword", "diamond_sword", "钻石剑", false))
                .isTrue();
    }

    @Test
    @DisplayName("未映射的显示名不附带附魔限制")
    void unmappedDisplayNameMatchesWithoutEnchantmentRestriction() {
        assertThat(QuickCraftItemAliases.matches("草方块", "minecraft:grass_block", "grass_block", "草方块", false))
                .isTrue();
        assertThat(QuickCraftItemAliases.matches("不存在的物品", "minecraft:grass_block", "grass_block", "草方块", false))
                .isFalse();
    }

    @Test
    @DisplayName("兼容旧的 enchanted 前缀配置")
    void legacyEnchantedPrefixStillWorks() {
        assertThat(QuickCraftItemAliases.matches("enchanted:minecraft:diamond_sword", "minecraft:diamond_sword", "diamond_sword", "钻石剑", false))
                .isFalse();
        assertThat(QuickCraftItemAliases.matches("enchanted:minecraft:diamond_sword", "minecraft:diamond_sword", "diamond_sword", "钻石剑", true))
                .isTrue();
    }
}
