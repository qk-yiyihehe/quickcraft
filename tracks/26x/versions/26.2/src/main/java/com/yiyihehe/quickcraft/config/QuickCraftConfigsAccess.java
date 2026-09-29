package com.yiyihehe.quickcraft.config;

import com.google.common.collect.ImmutableList;

/** 26.2+ 配置版本差异适配。 */
final class QuickCraftConfigsAccess {
    private QuickCraftConfigsAccess() {
    }

    static ImmutableList<String> defaultBeaconEffectOrder() {
        return ImmutableList.of(
                "急迫2",
                "力量2",
                "生命恢复1",
                "跳跃提升2",
                "迅捷2",
                "抗性提升2"
        );
    }

    static void postLoadMigrations() {
        QuickCraftConfigs.migrateBeaconRegenerationLevelName();
    }
}
