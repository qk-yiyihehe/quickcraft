package com.yiyihehe.quickcraft.crafting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftClientRecipeMatcherTest {
    @Test
    void shapedGrid_matchesOffsetAndMirroredPatterns() {
        List<String> grid = List.of(
                "", "", "",
                "", "stick", "plank",
                "", "", ""
        );
        List<List<String>> ingredients = List.of(
                List.of("plank"),
                List.of("stick")
        );

        assertThat(QuickCraftClientRecipeMatcher.matchesShapedGrid(grid, 3, 3, ingredients, 2, 1, this::matchesSlot)).isTrue();
    }

    @Test
    void shapedGrid_rejectsUnexpectedInputOutsideThePattern() {
        List<String> grid = List.of(
                "cobblestone", "",
                "", "stick"
        );
        List<List<String>> ingredients = List.of(List.of("stick"));

        assertThat(QuickCraftClientRecipeMatcher.matchesShapedGrid(grid, 2, 2, ingredients, 1, 1, this::matchesSlot)).isFalse();
    }

    @Test
    void shapelessGrid_backtracksOverOverlappingIngredientCandidates() {
        List<String> inputs = List.of("plank", "stick");
        List<List<String>> ingredients = List.of(
                List.of("plank", "stick"),
                List.of("plank")
        );

        assertThat(QuickCraftClientRecipeMatcher.matchesShapelessGrid(inputs, ingredients, this::matchesSlot)).isTrue();
    }

    @Test
    void shapelessGrid_rejectsExtraInputStacks() {
        List<String> inputs = List.of("plank", "stick");
        List<List<String>> ingredients = List.of(List.of("plank"));

        assertThat(QuickCraftClientRecipeMatcher.matchesShapelessGrid(inputs, ingredients, this::matchesSlot)).isFalse();
    }

    private boolean matchesSlot(String stack, List<String> candidates) {
        return stack.isEmpty() ? candidates.isEmpty() : candidates.contains(stack);
    }
}
