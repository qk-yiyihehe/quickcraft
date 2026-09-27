package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.util.context.ContextMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * 1.21.2+ 客户端只会收到配方展示和 RecipeDisplayId，不能再从 ClientRecipeManager 反查原始配方。
 * 只有展示的输入格与当前合成格唯一匹配时，才允许用配方书点击自动补料。
 */
final class QuickCraftClientRecipeMatcher {
    private static final int FIRST_CRAFTING_GRID_SLOT = 1;

    private QuickCraftClientRecipeMatcher() {
    }

    @Nullable
    static RecipeDisplayId findUniqueRecipeId(
            Minecraft client,
            AbstractContainerMenu handler,
            ItemStack resultTemplate,
            int gridWidth,
            int gridHeight
    ) {
        if (client == null
                || client.player == null
                || client.level == null
                || resultTemplate.isEmpty()) {
            return null;
        }

        List<ItemStack> grid = getGrid(handler, gridWidth * gridHeight);
        StackedItemContents finder = new StackedItemContents();
        client.player.getInventory().fillStackedContents(finder);
        if (handler instanceof CraftingMenu craftingHandler) {
            craftingHandler.fillCraftSlotsStackedContents(finder);
        } else if (handler instanceof InventoryMenu playerHandler) {
            playerHandler.fillCraftSlotsStackedContents(finder);
        } else {
            return null;
        }
        ContextMap context = SlotDisplayContext.fromLevel(client.level);
        RecipeDisplayId matchedId = null;

        for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
            collection.selectRecipes(finder, display -> true);

            for (RecipeDisplayEntry entry : collection.getRecipes()) {
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
            ContextMap context
    ) {
        return entry.resultItems(context).stream()
                .anyMatch(displayed -> ItemStack.isSameItemSameComponents(displayed, resultTemplate));
    }

    private static boolean matchesCraftingGrid(
            RecipeDisplay display,
            List<ItemStack> grid,
            int gridWidth,
            int gridHeight,
            ContextMap context
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

    private static List<ItemStack> getGrid(AbstractContainerMenu handler, int gridSize) {
        List<ItemStack> stacks = new ArrayList<>(gridSize);
        for (int index = 0; index < gridSize; index++) {
            stacks.add(handler.getSlot(FIRST_CRAFTING_GRID_SLOT + index).getItem().copy());
        }
        return stacks;
    }

    private static List<List<ItemStack>> getCandidateStacks(
            List<SlotDisplay> displays,
            ContextMap context
    ) {
        return displays.stream().map(display -> display.resolveForStacks(context)).toList();
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

        return candidates.stream().anyMatch(candidate -> ItemStack.isSameItemSameComponents(stack, candidate));
    }
}
