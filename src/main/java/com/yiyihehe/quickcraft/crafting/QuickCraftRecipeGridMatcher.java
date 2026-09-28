package com.yiyihehe.quickcraft.crafting;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * Matches the logical contents of a crafting grid without depending on Minecraft recipe APIs.
 */
final class QuickCraftRecipeGridMatcher {
    private QuickCraftRecipeGridMatcher() {
    }

    static <T> boolean matchesShaped(
            List<T> grid,
            int gridWidth,
            int gridHeight,
            List<List<T>> ingredients,
            int recipeWidth,
            int recipeHeight,
            BiPredicate<T, List<T>> slotMatcher
    ) {
        if (grid.size() != gridWidth * gridHeight
                || ingredients.size() != recipeWidth * recipeHeight
                || recipeWidth > gridWidth
                || recipeHeight > gridHeight) {
            return false;
        }

        for (int offsetY = 0; offsetY <= gridHeight - recipeHeight; offsetY++) {
            for (int offsetX = 0; offsetX <= gridWidth - recipeWidth; offsetX++) {
                if (matchesShapedAt(grid, gridWidth, gridHeight, ingredients, recipeWidth, recipeHeight, offsetX, offsetY, false, slotMatcher)
                        || matchesShapedAt(grid, gridWidth, gridHeight, ingredients, recipeWidth, recipeHeight, offsetX, offsetY, true, slotMatcher)) {
                    return true;
                }
            }
        }

        return false;
    }

    static <T> boolean matchesShapeless(
            List<T> inputs,
            List<List<T>> ingredients,
            BiPredicate<T, List<T>> slotMatcher
    ) {
        if (inputs.size() != ingredients.size()) {
            return false;
        }

        return matchesShapelessInputs(inputs, ingredients, 0, new boolean[ingredients.size()], slotMatcher);
    }

    private static <T> boolean matchesShapedAt(
            List<T> grid,
            int gridWidth,
            int gridHeight,
            List<List<T>> ingredients,
            int recipeWidth,
            int recipeHeight,
            int offsetX,
            int offsetY,
            boolean mirrored,
            BiPredicate<T, List<T>> slotMatcher
    ) {
        for (int gridY = 0; gridY < gridHeight; gridY++) {
            for (int gridX = 0; gridX < gridWidth; gridX++) {
                int recipeX = gridX - offsetX;
                int recipeY = gridY - offsetY;
                List<T> candidates = List.of();

                if (recipeX >= 0 && recipeX < recipeWidth && recipeY >= 0 && recipeY < recipeHeight) {
                    int ingredientX = mirrored ? recipeWidth - recipeX - 1 : recipeX;
                    candidates = ingredients.get(recipeY * recipeWidth + ingredientX);
                }

                if (!slotMatcher.test(grid.get(gridY * gridWidth + gridX), candidates)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static <T> boolean matchesShapelessInputs(
            List<T> inputs,
            List<List<T>> ingredients,
            int inputIndex,
            boolean[] usedIngredients,
            BiPredicate<T, List<T>> slotMatcher
    ) {
        if (inputIndex == inputs.size()) {
            return true;
        }

        T input = inputs.get(inputIndex);
        for (int ingredientIndex = 0; ingredientIndex < ingredients.size(); ingredientIndex++) {
            if (usedIngredients[ingredientIndex] || !slotMatcher.test(input, ingredients.get(ingredientIndex))) {
                continue;
            }

            usedIngredients[ingredientIndex] = true;
            if (matchesShapelessInputs(inputs, ingredients, inputIndex + 1, usedIngredients, slotMatcher)) {
                return true;
            }
            usedIngredients[ingredientIndex] = false;
        }

        return false;
    }
}
