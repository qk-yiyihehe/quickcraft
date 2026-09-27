package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeFinder;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.context.ContextParameterMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * 1.21.2+ 客户端只会收到配方展示和 NetworkRecipeId，不能再从 ClientRecipeManager 反查原始配方。
 * 只有展示的输入格与当前合成格唯一匹配时，才允许用配方书点击自动补料。
 */
final class QuickCraftClientRecipeMatcher {
    private static final int FIRST_CRAFTING_GRID_SLOT = 1;

    private QuickCraftClientRecipeMatcher() {
    }

    @Nullable
    static NetworkRecipeId findUniqueRecipeId(
            MinecraftClient client,
            ScreenHandler handler,
            ItemStack resultTemplate,
            int gridWidth,
            int gridHeight
    ) {
        if (client == null
                || client.player == null
                || client.world == null
                || resultTemplate.isEmpty()) {
            return null;
        }

        List<ItemStack> grid = getGrid(handler, gridWidth * gridHeight);
        RecipeFinder finder = new RecipeFinder();
        client.player.getInventory().populateRecipeFinder(finder);
        if (handler instanceof CraftingScreenHandler craftingHandler) {
            craftingHandler.populateRecipeFinder(finder);
        } else if (handler instanceof PlayerScreenHandler playerHandler) {
            playerHandler.populateRecipeFinder(finder);
        } else {
            return null;
        }
        ContextParameterMap context = SlotDisplayContexts.createParameters(client.world);
        NetworkRecipeId matchedId = null;

        for (RecipeResultCollection collection : client.player.getRecipeBook().getOrderedResults()) {
            collection.populateRecipes(finder, display -> true);

            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                if (!collection.isCraftable(entry.id())
                        || !matchesResult(entry, resultTemplate, context)
                        || !matchesCraftingGrid(entry.display(), grid, gridWidth, gridHeight, context)) {
                    continue;
                }

                // 展示数据无法区分时必须走手动补料，不能点击一条碰巧同产物的配方。
                if (matchedId != null) {
                    return null;
                }

                matchedId = entry.id();
            }
        }

        return matchedId;
    }

    static <T> boolean matchesShapedGrid(
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

    static <T> boolean matchesShapelessGrid(
            List<T> inputs,
            List<List<T>> ingredients,
            BiPredicate<T, List<T>> slotMatcher
    ) {
        if (inputs.size() != ingredients.size()) {
            return false;
        }

        return matchesShapelessInputs(inputs, ingredients, 0, new boolean[ingredients.size()], slotMatcher);
    }

    private static boolean matchesResult(
            RecipeDisplayEntry entry,
            ItemStack resultTemplate,
            ContextParameterMap context
    ) {
        return entry.getStacks(context).stream()
                .anyMatch(displayed -> ItemStack.areItemsAndComponentsEqual(displayed, resultTemplate));
    }

    private static boolean matchesCraftingGrid(
            RecipeDisplay display,
            List<ItemStack> grid,
            int gridWidth,
            int gridHeight,
            ContextParameterMap context
    ) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            return matchesShapedGrid(
                    grid,
                    gridWidth,
                    gridHeight,
                    getCandidateStacks(shaped.ingredients(), context),
                    shaped.width(),
                    shaped.height(),
                    QuickCraftClientRecipeMatcher::matchesSlot
            );
        }

        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            List<ItemStack> inputs = grid.stream().filter(stack -> !stack.isEmpty()).toList();
            return matchesShapelessGrid(inputs, getCandidateStacks(shapeless.ingredients(), context), QuickCraftClientRecipeMatcher::matchesSlot);
        }

        return false;
    }

    private static List<ItemStack> getGrid(ScreenHandler handler, int gridSize) {
        List<ItemStack> stacks = new ArrayList<>(gridSize);
        for (int index = 0; index < gridSize; index++) {
            stacks.add(handler.getSlot(FIRST_CRAFTING_GRID_SLOT + index).getStack().copy());
        }
        return stacks;
    }

    private static List<List<ItemStack>> getCandidateStacks(
            List<SlotDisplay> displays,
            ContextParameterMap context
    ) {
        return displays.stream().map(display -> display.getStacks(context)).toList();
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

    private static boolean matchesSlot(ItemStack stack, List<ItemStack> candidates) {
        if (stack.isEmpty()) {
            return candidates.isEmpty();
        }

        return candidates.stream().anyMatch(candidate -> ItemStack.areItemsAndComponentsEqual(stack, candidate));
    }
}
