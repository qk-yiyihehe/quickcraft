package com.yiyihehe.quickcraft.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftAnvilPresetsTest {
    @Test
    void migratesOldArmorDefaultsAndPreservesCustomTargets() {
        var profiles = List.of(QuickCraftAnvilPresets.HELMET, QuickCraftAnvilPresets.CHESTPLATE,
                QuickCraftAnvilPresets.LEGGINGS, QuickCraftAnvilPresets.BOOTS);
        var originals = new java.util.HashMap<fi.dy.masa.malilib.config.options.ConfigStringList, List<String>>();
        profiles.forEach(profile -> originals.put(profile, List.copyOf(profile.getStrings())));
        try {
            profiles.forEach(profile -> profile.setStrings(profile.getDefaultStrings()));
            QuickCraftAnvilPresets.CHESTPLATE.setStrings(List.of("unbreaking=3", "mending=1", "protection=4", "thorns=3"));
            var custom = List.of("unbreaking=3", "mending=1", "protection=3", "thorns=2");
            QuickCraftAnvilPresets.HELMET.setStrings(custom);
            QuickCraftAnvilPresets.migrateLegacyDefaults();
            assertThat(QuickCraftAnvilPresets.CHESTPLATE.getStrings())
                    .containsExactlyElementsOf(QuickCraftAnvilPresets.CHESTPLATE.getDefaultStrings());
            assertThat(QuickCraftAnvilPresets.HELMET.getStrings()).containsExactlyElementsOf(custom);
            QuickCraftAnvilPresets.migrateLegacyDefaults();
            assertThat(QuickCraftAnvilPresets.HELMET.getStrings()).containsExactlyElementsOf(custom);
        } finally {
            originals.forEach((profile, entries) -> profile.setStrings(entries));
        }
    }
}
