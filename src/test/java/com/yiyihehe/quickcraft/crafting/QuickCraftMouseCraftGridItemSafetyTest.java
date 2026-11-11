package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
//#if MC<12102
import net.minecraft.client.world.ClientWorld;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.util.Identifier;
//#endif
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
//#if MC<12102
import static org.mockito.Mockito.when;
//#endif

/**
 * BUG-31 生产路径回归：只有可核对的配方实例明确返回的物品才能被当作返还物整组丢出。
 * 无准确配方（1.21.2+ 客户端只同步配方展示）时默认返还推导不再授权 THROW/登记；
 * 合成格里的无关物品安全收回背包，无空间则明确中止。
 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickCraftMouseCraftGridItemSafetyTest {
    private static final int GRID_START = 1;
    private static final int PLAYER_MAIN_START = 10;
    private static final int HOTBAR_START = 37;

    @Test
    void unrelatedSwordReachesSafeRecoveryThroughSingleCraftEntry() throws Exception {
        // 经 handleSingleCraft 真实入口触发旧计划补料：剑收回背包，不发 THROW，不登记返还物。
        GridFixture fixture = new GridFixture(true);
        fixture.grid(2, new ItemStack(Items.IRON_SWORD));
        fixture.lockPatternOnlyAt(0, Items.OAK_PLANKS);
        fixture.handleSingleCraft();

        assertThat(fixture.handler.getSlot(GRID_START + 2).hasStack()).isFalse();
        assertThat(fixture.countInPlayerInventory(Items.IRON_SWORD)).isEqualTo(1);
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), eq(1),
                eq(SlotActionType.THROW), any());
        assertThat(fixture.knownRemainders()).isEmpty();
    }
    @Test
    void unrelatedGridItemIsReturnedToInventoryInsteadOfThrown() throws Exception {
        GridFixture fixture = new GridFixture(true);
        fixture.grid(0, new ItemStack(Items.OAK_PLANKS, 1));
        fixture.grid(2, new ItemStack(Items.IRON_SWORD));
        fixture.lockPatternOnlyAt(0, Items.OAK_PLANKS);

        int relocated = fixture.relocate();

        assertThat(relocated).isEqualTo(1);
        assertThat(fixture.handler.getSlot(GRID_START + 2).hasStack()).isFalse();
        assertThat(fixture.countInPlayerInventory(Items.IRON_SWORD)).isEqualTo(1);
        verify(fixture.interactionManager).clickSlot(anyInt(), eq(GRID_START + 2), eq(0),
                eq(SlotActionType.QUICK_MOVE), any());
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), eq(1),
                eq(SlotActionType.THROW), any());
        assertThat(fixture.knownRemainders()).isEmpty();
    }

    @Test
    void defaultRemainderWithoutAccurateRecipeIsRecoveredNotThrown() throws Exception {
        // 无准确配方时，奶桶“默认会返桶”不能作为真实返还证据：不得 THROW，也不得登记。
        GridFixture fixture = new GridFixture(true);
        fixture.grid(0, new ItemStack(Items.BUCKET));
        fixture.lockPatternOnlyAt(0, Items.MILK_BUCKET);

        int relocated = fixture.relocate();

        assertThat(relocated).isEqualTo(1);
        assertThat(fixture.handler.getSlot(GRID_START).hasStack()).isFalse();
        assertThat(fixture.countInPlayerInventory(Items.BUCKET)).isEqualTo(1);
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), eq(1),
                eq(SlotActionType.THROW), any());
        assertThat(fixture.knownRemainders()).isEmpty();
    }

    @Test
    void unrelatedGridItemWithoutInventoryRoomAbortsWithoutThrowing() throws Exception {
        GridFixture fixture = new GridFixture(false);
        fixture.grid(2, new ItemStack(Items.IRON_SWORD));
        fixture.lockPatternOnlyAt(0, Items.OAK_PLANKS);

        int first = fixture.relocate();
        int second = fixture.relocate();

        assertThat(first).isZero();
        assertThat(second).isZero();
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(),
                eq(SlotActionType.THROW), any());
        verify(fixture.player, atLeastOnce()).sendMessage(any(Text.class), anyBoolean());
        assertThat((Boolean) fixture.field("unrelatedGridItemNotified")).isTrue();
        verify(fixture.player).sendMessage(any(Text.class), anyBoolean());
        assertThat(fixture.handler.getSlot(GRID_START + 2).getStack().isOf(Items.IRON_SWORD)).isTrue();
        assertThat(fixture.knownRemainders()).isEmpty();
    }

    @Test
    void sameItemDifferentComponentsIsNotTreatedAsRecipeRemainder() throws Exception {
        GridFixture fixture = new GridFixture(true);
        ItemStack customBucket = new ItemStack(Items.BUCKET);
        customBucket.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, Text.literal("user bucket"));
        fixture.grid(0, customBucket);
        fixture.lockPatternOnlyAt(0, Items.MILK_BUCKET);

        int relocated = fixture.relocate();

        assertThat(relocated).isEqualTo(1);
        assertThat(fixture.countInPlayerInventory(Items.BUCKET)).isEqualTo(1);
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(),
                eq(SlotActionType.THROW), any());
        assertThat(fixture.knownRemainders()).isEmpty();
    }

//#if MC<12102
    @Test
    void confirmedRecipeRemainderIsThrownAndPlanRetained() throws Exception {
        GridFixture fixture = new GridFixture(true);
        fixture.installWorldRecipe(new ItemStack(Items.BUCKET));
        fixture.grid(0, new ItemStack(Items.BUCKET));
        fixture.lockPatternOnlyAt(0, Items.MILK_BUCKET);

        int relocated = fixture.relocate();

        assertThat(relocated).isEqualTo(1);
        assertThat(fixture.handler.getSlot(GRID_START).hasStack()).isFalse();
        verify(fixture.interactionManager).clickSlot(anyInt(), eq(GRID_START), eq(1),
                eq(SlotActionType.THROW), any());
        assertThat(fixture.knownRemainders()).hasSize(1);
        assertThat(fixture.knownRemainders().get(0).isOf(Items.BUCKET)).isTrue();
        @SuppressWarnings("unchecked")
        List<ItemStack> plan = (List<ItemStack>) fixture.field("lockedCraftingPattern");
        assertThat(plan).hasSize(9);
        assertThat(plan.get(0).isOf(Items.MILK_BUCKET)).isTrue();
    }

    @Test
    void recipeOverridingDefaultRemainderCannotAuthorizeBucketThrow() throws Exception {
        // 可核对配方明确不返还任何物品：奶桶默认返桶也不能作为 THROW/登记依据。
        GridFixture fixture = new GridFixture(true);
        fixture.installWorldRecipe(ItemStack.EMPTY);
        fixture.grid(0, new ItemStack(Items.BUCKET));
        fixture.lockPatternOnlyAt(0, Items.MILK_BUCKET);

        int relocated = fixture.relocate();

        assertThat(relocated).isEqualTo(1);
        assertThat(fixture.countInPlayerInventory(Items.BUCKET)).isEqualTo(1);
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), eq(1),
                eq(SlotActionType.THROW), any());
        assertThat(fixture.knownRemainders()).isEmpty();
    }

    @Test
    void recipeReturningDifferentItemDoesNotThrowDefaultBucket() throws Exception {
        GridFixture fixture = new GridFixture(true);
        fixture.installWorldRecipe(new ItemStack(Items.GLASS_BOTTLE));
        fixture.grid(0, new ItemStack(Items.BUCKET));
        fixture.lockPatternOnlyAt(0, Items.MILK_BUCKET);

        int relocated = fixture.relocate();

        assertThat(relocated).isEqualTo(1);
        assertThat(fixture.countInPlayerInventory(Items.BUCKET)).isEqualTo(1);
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), eq(1),
                eq(SlotActionType.THROW), any());
        assertThat(fixture.knownRemainders()).isEmpty();
    }

    @Test
    void accurateRecipeWithComponentDifferenceIsRecoveredNotThrown() throws Exception {
        GridFixture fixture = new GridFixture(true);
        fixture.installWorldRecipe(new ItemStack(Items.BUCKET));
        ItemStack customBucket = new ItemStack(Items.BUCKET);
        customBucket.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, Text.literal("user bucket"));
        fixture.grid(0, customBucket);
        fixture.lockPatternOnlyAt(0, Items.MILK_BUCKET);

        int relocated = fixture.relocate();

        assertThat(relocated).isEqualTo(1);
        assertThat(fixture.countInPlayerInventory(Items.BUCKET)).isEqualTo(1);
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), anyInt(), anyInt(),
                eq(SlotActionType.THROW), any());
        assertThat(fixture.knownRemainders()).isEmpty();
    }

    @Test
    void offsetPatternRemainderDoesNotMisclassifyOtherSlots() throws Exception {
        GridFixture fixture = new GridFixture(true);
        fixture.installWorldRecipe(new ItemStack(Items.BUCKET));
        fixture.grid(0, new ItemStack(Items.BUCKET));
        fixture.grid(4, new ItemStack(Items.BUCKET));
        fixture.lockPatternOnlyAt(4, Items.MILK_BUCKET);

        fixture.relocate();

        verify(fixture.interactionManager).clickSlot(anyInt(), eq(GRID_START + 4), eq(1),
                eq(SlotActionType.THROW), any());
        verify(fixture.interactionManager, never()).clickSlot(anyInt(), eq(GRID_START), eq(1),
                eq(SlotActionType.THROW), any());
        assertThat(fixture.countInPlayerInventory(Items.BUCKET)).isEqualTo(1);
        assertThat(fixture.handler.getSlot(GRID_START + 4).hasStack()).isFalse();
    }
//#endif

    private static final class GridFixture {
        private final MinecraftClient client = mock(MinecraftClient.class);
        private final ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        private final ClientPlayerInteractionManager interactionManager =
                mock(ClientPlayerInteractionManager.class);
        private final CraftingScreenHandler handler;
        private final QuickCraftMouseCrafting crafting;
        private final List<ItemStack> patchedPattern = new ArrayList<>();

        private GridFixture(boolean inventoryHasRoom) throws Exception {
            PlayerInventory playerInventory = newPlayerInventory(player);
            handler = new CraftingScreenHandler(0, playerInventory);
            doNothing().when(player).sendMessage(any(Text.class), anyBoolean());
            setField(player, "currentScreenHandler", handler);
            installClicks();
            setField(client, "player", player);
            setField(client, "interactionManager", interactionManager);

            for (int i = 0; i < 9; i++) {
                patchedPattern.add(ItemStack.EMPTY);
            }
            crafting = new QuickCraftMouseCrafting(
                    QuickCraftMouseCraftLayout.WORKBENCH, () -> true, () -> { });
            setField(crafting, "lockedCraftingPattern", new ArrayList<>(patchedPattern));
            setField(crafting, "lockedResultTemplate", new ItemStack(Items.CRAFTING_TABLE));

            for (int slot = PLAYER_MAIN_START; slot < HOTBAR_START + 9; slot++) {
                if (!inventoryHasRoom) {
                    handler.getSlot(slot).setStack(new ItemStack(Items.DIRT, 64));
                }
            }
        }

        private void grid(int logicalIndex, ItemStack stack) {
            handler.getSlot(GRID_START + logicalIndex).setStack(stack);
        }

        private void lockPatternOnlyAt(int logicalIndex, Item item) {
            patchedPattern.set(logicalIndex, new ItemStack(item, 1));
            try {
                setField(crafting, "lockedCraftingPattern", new ArrayList<>(patchedPattern));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

//#if MC<12102
        /**
         * 只安装客户端能真正确认的配方实例：匹配唯一、产物与锁定产物一致。
         * 剩余物由该实例的 getRemainder 明确给出，用于验证“实例语义”而非默认推导。
         */
        private void installWorldRecipe(ItemStack remainderTemplate) throws Exception {
            CraftingRecipe recipe = mock(CraftingRecipe.class, invocation -> {
                String name = invocation.getMethod().getName();
                if (name.equals("getRemainder") || name.equals("getRecipeRemainders")) {
                    net.minecraft.recipe.input.CraftingRecipeInput input = invocation.getArgument(0);
                    return net.minecraft.util.collection.DefaultedList.ofSize(
                            input.getSize(), remainderTemplate);
                }
                return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
            when(recipe.craft(any(CraftingRecipeInput.class), any()))
                    .thenReturn(((ItemStack) field("lockedResultTemplate")).copy());
            RecipeEntry<CraftingRecipe> entry = new RecipeEntry<>(
                    Identifier.of("quickcraft", "test_remainder"), recipe);
            RecipeManager manager = mock(RecipeManager.class);
            when(manager.getAllMatches(any(RecipeType.class), any(CraftingRecipeInput.class), any()))
                    .thenReturn(List.of(entry));
            ClientWorld world = mock(ClientWorld.class);
            when(world.getRecipeManager()).thenReturn(manager);
            setField(client, "world", world);
        }
//#endif

        @SuppressWarnings("unchecked")
        private List<ItemStack> knownRemainders() throws Exception {
            return (List<ItemStack>) field("knownRecipeRemainders");
        }

        private void handleSingleCraft() throws Exception {
            setField(client, "world", mock(net.minecraft.client.world.ClientWorld.class));
            Method method = QuickCraftMouseCrafting.class.getDeclaredMethod(
                    "handleSingleCraft", MinecraftClient.class, ScreenHandler.class);
            method.setAccessible(true);
            method.invoke(crafting, client, handler);
        }
        private int relocate() throws Exception {
            try (MockedStatic<QuickContainerLock> locks = mockStatic(QuickContainerLock.class);
                 MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class)) {
                locks.when(() -> QuickContainerLock.isLockedSlot(any(ScreenHandler.class), anyInt()))
                        .thenReturn(false);
                configs.when(QuickCraftConfigs::isMouseCraftAckDebugEnabled).thenReturn(false);
                Method relocate = QuickCraftMouseCrafting.class.getDeclaredMethod(
                        "relocateMismatchedGridItems", MinecraftClient.class, ScreenHandler.class);
                relocate.setAccessible(true);
                return (Integer) relocate.invoke(crafting, client, handler);
            }
        }

        private int countInPlayerInventory(Item item) {
            int total = 0;
            for (int slot = PLAYER_MAIN_START; slot < HOTBAR_START + 9; slot++) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (stack.isOf(item)) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        private Object field(String name) throws Exception {
            Field field = findField(QuickCraftMouseCrafting.class, name);
            field.setAccessible(true);
            return field.get(crafting);
        }

        private void installClicks() {
            doAnswer(invocation -> {
                int slotId = invocation.getArgument(1);
                SlotActionType action = invocation.getArgument(3);
                if (action == SlotActionType.QUICK_MOVE) {
                    moveToFirstEmptyInventorySlot(handler, slotId);
                } else if (action == SlotActionType.THROW) {
                    handler.getSlot(slotId).setStack(ItemStack.EMPTY);
                }
                return null;
            }).when(interactionManager).clickSlot(anyInt(), anyInt(), anyInt(),
                    any(SlotActionType.class), any(PlayerEntity.class));
        }
    }

    private static PlayerInventory newPlayerInventory(ClientPlayerEntity player) throws Exception {
        for (java.lang.reflect.Constructor<?> constructor : PlayerInventory.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(player)) {
                return (PlayerInventory) constructor.newInstance(player);
            }
            if (parameters.length == 2 && parameters[0].isInstance(player)
                    && parameters[1].getSimpleName().equals("EntityEquipment")) {
                Object equipment = parameters[1].getConstructor().newInstance();
                return (PlayerInventory) constructor.newInstance(player, equipment);
            }
        }
        throw new AssertionError("unsupported PlayerInventory constructor");
    }

    private static void moveToFirstEmptyInventorySlot(ScreenHandler handler, int sourceSlotId) {
        ItemStack moving = handler.getSlot(sourceSlotId).getStack().copy();
        if (moving.isEmpty()) {
            return;
        }
        for (int slot = PLAYER_MAIN_START; slot < HOTBAR_START + 9; slot++) {
            if (handler.getSlot(slot).getStack().isEmpty()) {
                handler.getSlot(slot).setStack(moving);
                handler.getSlot(sourceSlotId).setStack(ItemStack.EMPTY);
                return;
            }
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
