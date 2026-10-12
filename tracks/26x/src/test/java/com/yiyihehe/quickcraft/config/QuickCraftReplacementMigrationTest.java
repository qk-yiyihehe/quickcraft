package com.yiyihehe.quickcraft.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftReplacementMigrationTest {
    @Test
    void migratesOnlyUnscopedLegacyRules() {
        assertThat(QuickCraftConfigs.isUnscopedReplacement("minecraft:water_bucket->minecraft:birch_planks")).isTrue();
        assertThat(QuickCraftConfigs.isUnscopedReplacement("schematic:test||group||4||minecraft:stone->minecraft:glass")).isFalse();
        assertThat(QuickCraftConfigs.isUnscopedReplacement("{\"version\":2,\"context\":\"\",\"group\":\"\",\"slot\":-1}")).isTrue();
        assertThat(QuickCraftConfigs.isUnscopedReplacement("{\"version\":2,\"context\":\"schematic:test\",\"group\":\"\",\"slot\":-1}")).isFalse();
        assertThat(QuickCraftConfigs.isUnscopedReplacement("{broken->json")).isFalse();
    }
}
