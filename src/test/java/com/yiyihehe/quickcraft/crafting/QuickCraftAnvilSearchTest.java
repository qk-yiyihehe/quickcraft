package com.yiyihehe.quickcraft.crafting;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftAnvilSearchTest {
    @Test
    void readableEnchantmentsAndLegacyIdsKeepTheirLevel() {
        var readable = com.yiyihehe.quickcraft.config.QuickCraftAnvilEnchantments.parse("锋利5");
        assertThat(readable.name()).isEqualTo("锋利");
        assertThat(readable.level()).isEqualTo(5);
        var legacy = com.yiyihehe.quickcraft.config.QuickCraftAnvilEnchantments.parse("minecraft:sharpness=5");
        assertThat(legacy.name()).isEqualTo("minecraft:sharpness");
        assertThat(legacy.level()).isEqualTo(5);
        assertThat(com.yiyihehe.quickcraft.config.QuickCraftAnvilEnchantments.canonical("Sharpness5"))
                .isEqualTo("sharpness=5");
        assertThat(com.yiyihehe.quickcraft.config.QuickCraftAnvilEnchantments.canonical("锋利abc"))
                .isEqualTo("锋利abc");
    }

    @Test
    void swordPresetContainsOnlyTheFiveRequestedEnchantments() {
        assertThat(com.yiyihehe.quickcraft.config.QuickCraftAnvilPresets.SWORD.getStrings())
                .containsExactlyInAnyOrder("unbreaking=3", "mending=1", "sharpness=5", "looting=3", "sweeping_edge=3");
        assertThat(com.yiyihehe.quickcraft.config.QuickCraftAnvilPresets.PICKAXE_SILK.getStrings()).contains("silk_touch=1");
        assertThat(com.yiyihehe.quickcraft.config.QuickCraftAnvilPresets.PICKAXE_FORTUNE.getStrings()).contains("fortune=3");
    }

    @Test
    void presetsHaveNoThornsAndOnlyTheInfinityBowOmitsMending() {
        for (var option : com.yiyihehe.quickcraft.config.QuickCraftAnvilPresets.OPTIONS) {
            var profile = (fi.dy.masa.malilib.config.options.ConfigStringList) option;
            assertThat(profile.getDefaultStrings()).as(profile.getName()).doesNotContain("thorns=3");
            if (profile == com.yiyihehe.quickcraft.config.QuickCraftAnvilPresets.BOW) {
                assertThat(profile.getDefaultStrings()).contains("infinity=1").doesNotContain("mending=1");
            } else {
                assertThat(profile.getDefaultStrings()).as(profile.getName())
                        .contains("mending=1").doesNotContain("infinity=1");
            }
        }
    }

    @Test
    void missingBooksChooseTheMostProgressThenTheCheapestRoute() {
        Map<String, List<QuickCraftAnvilSearch.Edge<String>>> graph = Map.of(
                "used equipment", List.of(new QuickCraftAnvilSearch.Edge<>("used partial", 18)),
                "fresh equipment", List.of(new QuickCraftAnvilSearch.Edge<>("cheap incomplete", 1),
                        new QuickCraftAnvilSearch.Edge<>("fresh partial", 7)),
                "cheap incomplete", List.of(new QuickCraftAnvilSearch.Edge<>("expensive partial", 20)));
        var search = new QuickCraftAnvilSearch<>(List.of("used equipment", "fresh equipment"),
                state -> state, state -> state.equals("unavailable full target"),
                state -> graph.getOrDefault(state, List.of()),
                state -> state.endsWith("partial") ? 4 : state.equals("cheap incomplete") ? 1 : -1);
        assertThat(search.advance(1)).isFalse();
        assertThat(search.result()).isNull();
        while (!search.advance(1)) {}
        assertThat(search.result().state()).isEqualTo("fresh partial");
        assertThat(search.result().cost()).isEqualTo(7);
    }

    @Test
    void comparesEquipmentRootsAndDoesNotCommitToTheCheapestFirstBook() {
        Map<String, List<QuickCraftAnvilSearch.Edge<String>>> graph = Map.of(
                "used sword", List.of(new QuickCraftAnvilSearch.Edge<>("used sword finished", 24)),
                "fresh sword", List.of(new QuickCraftAnvilSearch.Edge<>("cheap first book", 1),
                        new QuickCraftAnvilSearch.Edge<>("expensive first book", 7)),
                "cheap first book", List.of(new QuickCraftAnvilSearch.Edge<>("fresh sword finished", 20)),
                "expensive first book", List.of(new QuickCraftAnvilSearch.Edge<>("fresh sword finished", 3)));
        var search = new QuickCraftAnvilSearch<>(List.of("used sword", "fresh sword"),
                state -> state, state -> state.endsWith("finished"),
                state -> graph.getOrDefault(state, List.of()));
        while (!search.advance(1)) { /* 同一最优性条件必须适用于分帧搜索。 */ }
        assertThat(search.result().state()).isEqualTo("fresh sword finished");
        assertThat(search.result().cost()).isEqualTo(10);
    }

    @Test
    void cannotTreatAnIncompleteOrImpossibleRouteAsAResult() {
        var search = new QuickCraftAnvilSearch<>(List.of(0), state -> state,
                state -> state == 2, state -> state == 0
                        ? List.of(new QuickCraftAnvilSearch.Edge<>(1, 4)) : List.of());
        assertThat(search.advance(1)).isFalse();
        assertThat(search.result()).isNull();
        while (!search.advance(1)) {}
        assertThat(search.result()).isNull();
    }

    @Test
    void levelRemovalKeepsProgressAndXpCostTelescopesAcrossSteps() {
        double first = QuickCraftAnvilSearch.experienceAt(45, 0.5F) - QuickCraftAnvilSearch.experienceAt(38, 0.5F);
        double second = QuickCraftAnvilSearch.experienceAt(38, 0.5F) - QuickCraftAnvilSearch.experienceAt(35, 0.5F);
        assertThat(first + second).isEqualTo(QuickCraftAnvilSearch.experienceAt(45, 0.5F)
                - QuickCraftAnvilSearch.experienceAt(35, 0.5F));
        assertThat(QuickCraftAnvilSearch.experienceAt(16, 0)).isEqualTo(352);
        assertThat(QuickCraftAnvilSearch.experienceAt(31, 0)).isEqualTo(1507);
    }
}
