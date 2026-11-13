package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickCraftClientRecipeMatcherStateTest {

    @Test
    void productionQueryKeepsNativeTwoByTwoBookStateOnMatchAndRejection() {
        var client = org.mockito.Mockito.mock(net.minecraft.client.Minecraft.class);
        client.player = org.mockito.Mockito.mock(net.minecraft.client.player.LocalPlayer.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        client.level = org.mockito.Mockito.mock(net.minecraft.client.multiplayer.ClientLevel.class);
        var menu = org.mockito.Mockito.mock(net.minecraft.world.inventory.InventoryMenu.class);
        var inventory = new net.minecraft.world.SimpleContainer(5);
        inventory.setItem(1, new ItemStack(Items.OAK_LOG));
        org.mockito.Mockito.when(menu.getSlot(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(call ->
                new net.minecraft.world.inventory.Slot(inventory, call.getArgument(0), 0, 0));
        var playerInventory = client.player.getInventory();
        org.mockito.Mockito.doAnswer(call -> {
            ((StackedItemContents) call.getArgument(0)).accountStack(new ItemStack(Items.OAK_LOG));
            return null;
        }).when(menu).fillCraftSlotsStackedContents(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> {
            ((StackedItemContents) call.getArgument(0)).accountStack(new ItemStack(Items.OAK_PLANKS, 8));
            return null;
        }).when(playerInventory).fillStackedContents(org.mockito.ArgumentMatchers.any());
        var planksId = new RecipeDisplayId(20);
        var doorId = new RecipeDisplayId(21);
        var planks = collection(entry(planksId, 1, 1, Items.OAK_LOG, Items.OAK_PLANKS,
                List.of(Ingredient.of(Items.OAK_LOG))));
        var door = collection(entry(doorId, 2, 3, Items.OAK_PLANKS, Items.OAK_DOOR,
                java.util.Collections.nCopies(6, Ingredient.of(Items.OAK_PLANKS))));
        var baseline = new StackedItemContents();
        baseline.accountStack(new ItemStack(Items.OAK_LOG));
        baseline.accountStack(new ItemStack(Items.OAK_PLANKS, 8));
        planks.selectRecipes(baseline, display -> true);
        door.selectRecipes(baseline, display -> false);
        org.mockito.Mockito.when(client.player.getRecipeBook().getCollections())
                .thenReturn(List.of(planks, door));
        var context = emptyContext();
        try (var contexts = org.mockito.Mockito.mockStatic(SlotDisplayContext.class)) {
            contexts.when(() -> SlotDisplayContext.fromLevel(client.level)).thenReturn(context);
            assertThat(QuickCraftClientRecipeMatcher.findUniqueRecipeId(
                    client, menu, new ItemStack(Items.OAK_PLANKS), 2, 2)).isEqualTo(planksId);
            assertThat(QuickCraftClientRecipeMatcher.findUniqueRecipeId(
                    client, menu, new ItemStack(Items.OAK_DOOR), 2, 2)).isNull();
            assertThat(planks.isCraftable(planksId)).isTrue();
            assertThat(door.isCraftable(doorId)).isFalse();
        }
    }
    @Test
    void queryDoesNotPopulateSharedRecipeBookCollections() {
        RecipeDisplayId planksId = new RecipeDisplayId(1);
        RecipeDisplayId doorId = new RecipeDisplayId(2);
        RecipeCollection planks = collection(entry(
                planksId, 1, 1, Items.OAK_LOG, Items.OAK_PLANKS,
                List.of(Ingredient.of(Items.OAK_LOG))
        ));
        RecipeCollection door = collection(entry(
                doorId, 2, 3, Items.OAK_PLANKS, Items.OAK_DOOR,
                List.of(
                        Ingredient.of(Items.OAK_PLANKS),
                        Ingredient.of(Items.OAK_PLANKS),
                        Ingredient.of(Items.OAK_PLANKS),
                        Ingredient.of(Items.OAK_PLANKS),
                        Ingredient.of(Items.OAK_PLANKS),
                        Ingredient.of(Items.OAK_PLANKS)
                )
        ));
        StackedItemContents finder = new StackedItemContents();
        finder.accountStack(new ItemStack(Items.OAK_LOG));
        finder.accountStack(new ItemStack(Items.OAK_PLANKS, 8));

        RecipeDisplayId matched = QuickCraftClientRecipeMatcher.findUniqueRecipeIdInCollections(
                List.of(planks, door), finder,
                List.of(new ItemStack(Items.OAK_LOG), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY),
                new ItemStack(Items.OAK_PLANKS), 2, 2, emptyContext()
        );

        assertThat(matched).isEqualTo(planksId);
        assertThat(planks.isCraftable(planksId)).isFalse();
        assertThat(door.isCraftable(doorId)).isFalse();
    }

    @Test
    void matchingStillWorksForPlayer2x2AndCraftingTable3x3() {
        RecipeDisplayId planksId = new RecipeDisplayId(3);
        RecipeCollection planks = collection(entry(
                planksId, 1, 1, Items.OAK_LOG, Items.OAK_PLANKS,
                List.of(Ingredient.of(Items.OAK_LOG))
        ));
        StackedItemContents finder = new StackedItemContents();
        finder.accountStack(new ItemStack(Items.OAK_LOG));

        List<ItemStack> playerGrid = List.of(
                new ItemStack(Items.OAK_LOG), ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY
        );
        assertThat(QuickCraftClientRecipeMatcher.findUniqueRecipeIdInCollections(
                List.of(planks), finder, playerGrid, new ItemStack(Items.OAK_PLANKS), 2, 2, emptyContext()
        )).isEqualTo(planksId);

        List<ItemStack> tableGrid = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            tableGrid.add(i == 0 ? new ItemStack(Items.OAK_LOG) : ItemStack.EMPTY);
        }
        assertThat(QuickCraftClientRecipeMatcher.findUniqueRecipeIdInCollections(
                List.of(planks), finder, tableGrid, new ItemStack(Items.OAK_PLANKS), 3, 3, emptyContext()
        )).isEqualTo(planksId);
    }

    private static ContextMap emptyContext() {
        return SlotDisplayContext.fromLevel(mock(Level.class));
    }

    private static RecipeCollection collection(RecipeDisplayEntry entry) {
        return new RecipeCollection(List.of(entry));
    }

    private static RecipeDisplayEntry entry(RecipeDisplayId id,
                                            int width,
                                            int height,
                                            Item ingredient,
                                            Item result,
                                            List<Ingredient> requirements) {
        List<SlotDisplay> ingredients = new ArrayList<>();
        for (int i = 0; i < width * height; i++) {
            ingredients.add(new SlotDisplay.ItemSlotDisplay(ingredient));
        }
        RecipeDisplay display = new ShapedCraftingRecipeDisplay(
                width, height, ingredients,
                new SlotDisplay.ItemSlotDisplay(result),
                new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)
        );
        return new RecipeDisplayEntry(id, display, OptionalInt.empty(), null, Optional.of(requirements));
    }
}

