package com.yiyihehe.quickcraft.crafting;

import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeFinder;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.util.context.ContextParameterMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickCraftClientRecipeMatcherStateTest {

    @Test
    void productionQueryKeepsNativeTwoByTwoBookStateOnMatchAndRejection() {
        var client = org.mockito.Mockito.mock(net.minecraft.client.MinecraftClient.class);
        client.player = org.mockito.Mockito.mock(
                net.minecraft.client.network.ClientPlayerEntity.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS
        );
        client.world = org.mockito.Mockito.mock(net.minecraft.client.world.ClientWorld.class);
        var menu = org.mockito.Mockito.mock(net.minecraft.screen.PlayerScreenHandler.class);
        var inventory = new net.minecraft.inventory.SimpleInventory(5);
        inventory.setStack(1, new ItemStack(Items.OAK_LOG));
        org.mockito.Mockito.when(menu.getSlot(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(
                call -> new net.minecraft.screen.slot.Slot(inventory, call.getArgument(0), 0, 0)
        );
        var playerInventory = client.player.getInventory();
        org.mockito.Mockito.doAnswer(call -> {
            ((RecipeFinder) call.getArgument(0)).addInput(new ItemStack(Items.OAK_LOG));
            return null;
        }).when(menu).populateRecipeFinder(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> {
            ((RecipeFinder) call.getArgument(0)).addInput(new ItemStack(Items.OAK_PLANKS, 8));
            return null;
        }).when(playerInventory).populateRecipeFinder(org.mockito.ArgumentMatchers.any());
        var planksId = new NetworkRecipeId(20);
        var doorId = new NetworkRecipeId(21);
        var planks = collection(
                entry(planksId, 1, 1, Items.OAK_LOG, Items.OAK_PLANKS, List.of(Ingredient.ofItems(Items.OAK_LOG)))
        );
        var door = collection(
                entry(
                        doorId,
                        2,
                        3,
                        Items.OAK_PLANKS,
                        Items.OAK_DOOR,
                        java.util.Collections.nCopies(6, Ingredient.ofItems(Items.OAK_PLANKS))
                )
        );
        var baseline = new RecipeFinder();
        baseline.addInput(new ItemStack(Items.OAK_LOG));
        baseline.addInput(new ItemStack(Items.OAK_PLANKS, 8));
        planks.populateRecipes(baseline, display -> true);
        door.populateRecipes(baseline, display -> false);
        org.mockito.Mockito.when(client.player.getRecipeBook().getOrderedResults()).thenReturn(List.of(planks, door));
        var context = emptyContext();
        try (var contexts = org.mockito.Mockito.mockStatic(SlotDisplayContexts.class)) {
            contexts.when(() -> SlotDisplayContexts.createParameters(client.world)).thenReturn(context);
            assertThat(
                    QuickCraftClientRecipeMatcher.findUniqueRecipeId(
                            client,
                            menu,
                            new ItemStack(Items.OAK_PLANKS),
                            2,
                            2
                    )
            ).isEqualTo(planksId);
            assertThat(
                    QuickCraftClientRecipeMatcher.findUniqueRecipeId(client, menu, new ItemStack(Items.OAK_DOOR), 2, 2)
            ).isNull();
            assertThat(planks.isCraftable(planksId)).isTrue();
            assertThat(door.isCraftable(doorId)).isFalse();
        }
    }

    @Test
    void queryDoesNotPopulateSharedRecipeBookCollections() {
        NetworkRecipeId planksId = new NetworkRecipeId(1);
        NetworkRecipeId doorId = new NetworkRecipeId(2);
        RecipeResultCollection planks = collection(
                entry(planksId, 1, 1, Items.OAK_LOG, Items.OAK_PLANKS, List.of(Ingredient.ofItems(Items.OAK_LOG)))
        );
        RecipeResultCollection door = collection(
                entry(
                        doorId,
                        2,
                        3,
                        Items.OAK_PLANKS,
                        Items.OAK_DOOR,
                        List.of(
                                Ingredient.ofItems(Items.OAK_PLANKS),
                                Ingredient.ofItems(Items.OAK_PLANKS),
                                Ingredient.ofItems(Items.OAK_PLANKS),
                                Ingredient.ofItems(Items.OAK_PLANKS),
                                Ingredient.ofItems(Items.OAK_PLANKS),
                                Ingredient.ofItems(Items.OAK_PLANKS)
                        )
                )
        );
        RecipeFinder finder = new RecipeFinder();
        finder.addInput(new ItemStack(Items.OAK_LOG));
        finder.addInput(new ItemStack(Items.OAK_PLANKS, 8));

        NetworkRecipeId matched = QuickCraftClientRecipeMatcher.findUniqueRecipeIdInCollections(
                List.of(planks, door),
                finder,
                List.of(new ItemStack(Items.OAK_LOG), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY),
                new ItemStack(Items.OAK_PLANKS),
                2,
                2,
                emptyContext()
        );

        assertThat(matched).isEqualTo(planksId);
        assertThat(planks.isCraftable(planksId)).isFalse();
        assertThat(door.isCraftable(doorId)).isFalse();
    }

    @Test
    void matchingStillWorksForPlayer2x2AndCraftingTable3x3() {
        NetworkRecipeId planksId = new NetworkRecipeId(3);
        RecipeResultCollection planks = collection(
                entry(planksId, 1, 1, Items.OAK_LOG, Items.OAK_PLANKS, List.of(Ingredient.ofItems(Items.OAK_LOG)))
        );
        RecipeFinder finder = new RecipeFinder();
        finder.addInput(new ItemStack(Items.OAK_LOG));

        List<ItemStack> playerGrid = List.of(
                new ItemStack(Items.OAK_LOG),
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY
        );
        assertThat(
                QuickCraftClientRecipeMatcher.findUniqueRecipeIdInCollections(
                        List.of(planks),
                        finder,
                        playerGrid,
                        new ItemStack(Items.OAK_PLANKS),
                        2,
                        2,
                        emptyContext()
                )
        ).isEqualTo(planksId);

        List<ItemStack> tableGrid = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            tableGrid.add(i == 0 ? new ItemStack(Items.OAK_LOG) : ItemStack.EMPTY);
        }
        assertThat(
                QuickCraftClientRecipeMatcher.findUniqueRecipeIdInCollections(
                        List.of(planks),
                        finder,
                        tableGrid,
                        new ItemStack(Items.OAK_PLANKS),
                        3,
                        3,
                        emptyContext()
                )
        ).isEqualTo(planksId);
    }

    private static ContextParameterMap emptyContext() {
        return new ContextParameterMap.Builder().build(SlotDisplayContexts.CONTEXT_TYPE);
    }

    private static RecipeResultCollection collection(RecipeDisplayEntry entry) {
        return new RecipeResultCollection(List.of(entry));
    }

    private static RecipeDisplayEntry entry(
            NetworkRecipeId id,
            int width,
            int height,
            Item ingredient,
            Item result,
            List<Ingredient> requirements
    ) {
        List<SlotDisplay> ingredients = new ArrayList<>();
        for (int i = 0; i < width * height; i++) {
            ingredients.add(new SlotDisplay.ItemSlotDisplay(ingredient));
        }
        RecipeDisplay display = new ShapedCraftingRecipeDisplay(
                width,
                height,
                ingredients,
                new SlotDisplay.ItemSlotDisplay(result),
                new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)
        );
        return new RecipeDisplayEntry(id, display, OptionalInt.empty(), null, Optional.of(requirements));
    }
}
