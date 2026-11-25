package com.yiyihehe.quickcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialLists;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** BUG-40：空潜影盒作为需求时按本体组件精确匹配，普通材料仍按物品类型。 */
@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickMaterialCollectorShulkerDemandTest {
    @Test
    void emptyShulkerDemandRequiresExactComponents() {
        ItemStack empty = new ItemStack(Items.SHULKER_BOX);
        ItemStack filled = new ItemStack(Items.SHULKER_BOX);
        filled.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(List.of(new ItemStack(Items.STONE))));

        assertThat(QuickMaterialCollectorShulkerSelection.matchesDemandTemplate(empty, empty)).isTrue();
        assertThat(QuickMaterialCollectorShulkerSelection.matchesDemandTemplate(filled, empty)).isFalse();
        assertThat(
                QuickMaterialCollectorShulkerSelection.matchesDemandTemplate(
                        new ItemStack(Items.RED_SHULKER_BOX),
                        empty
                )
        ).isFalse();
    }

    @Test
    void normalMaterialDemandStillMatchesByItemType() {
        assertThat(
                QuickMaterialCollectorShulkerSelection.matchesDemandTemplate(
                        new ItemStack(Items.DIRT, 1),
                        new ItemStack(Items.DIRT, 64)
                )
        ).isTrue();
        assertThat(
                QuickMaterialCollectorShulkerSelection.matchesDemandTemplate(
                        new ItemStack(Items.STONE),
                        new ItemStack(Items.DIRT)
                )
        ).isFalse();
    }

    @Test
    void planningKeepsComponentDistinctTargetsAndSubtractsOnlyMatchingBoxes() throws Exception {
        try (Fixture f = new Fixture()) {
            ItemStack a = namedBox("A");
            ItemStack b = namedBox("B");
            f.inventory.setStack(0, b.copy());
            ItemStack filled = a.copy();
            filled.set(
                    DataComponentTypes.CONTAINER,
                    ContainerComponent.fromStacks(List.of(new ItemStack(Items.STONE)))
            );
            f.inventory.setStack(1, filled);
            Plan plan = f.plan(a, b, new ItemStack(Items.RED_SHULKER_BOX));
            assertThat(plan.targets()).hasSize(3);
            assertThat(plan.demands()).hasSize(2);
            assertThat(plan.demands()).allSatisfy(d -> assertThat(d.remaining()).isEqualTo(1));
            assertThat(plan.demands()).anySatisfy(
                    d -> assertThat(ItemStack.areItemsAndComponentsEqual(d.template(), a)).isTrue()
            );
            assertThat(plan.demands()).noneSatisfy(
                    d -> assertThat(ItemStack.areItemsAndComponentsEqual(d.template(), b)).isTrue()
            );
        }
    }

    @Test
    void productionPlanAndCollectionMoveOnlyMatchingEmptyBox() throws Exception {
        collectMatchingBox("collectToPlayer");
    }

    @Test
    void packingCollectionStillMovesDemandedBoxDirectlyToPlayer() throws Exception {
        collectMatchingBox("collectToShulkersOrPlayer");
    }

    private static void collectMatchingBox(String entry) throws Exception {
        try (Fixture f = new Fixture()) {
            ItemStack target = namedBox("A");
            ItemStack filled = target.copy();
            filled.set(
                    DataComponentTypes.CONTAINER,
                    ContainerComponent.fromStacks(List.of(new ItemStack(Items.STONE)))
            );
            f.inventory.setStack(0, namedBox("B"));
            f.menu.getSlot(0).setStack(filled.copy());
            f.menu.getSlot(1).setStack(namedBox("B"));
            f.menu.getSlot(2).setStack(new ItemStack(Items.RED_SHULKER_BOX));
            f.menu.getSlot(3).setStack(target.copy());
            Plan plan = f.plan(target);
            assertThat(plan.demands()).hasSize(1);
            f.collect(entry, plan);
            assertThat(f.menu.getSlot(0).getStack().getCount()).isEqualTo(1);
            assertThat(f.menu.getSlot(1).hasStack()).isTrue();
            assertThat(f.menu.getSlot(2).hasStack()).isTrue();
            assertThat(f.menu.getSlot(3).hasStack()).isFalse();
            assertThat(f.count(target)).isEqualTo(1);
            assertThat(f.count(namedBox("B"))).isEqualTo(1);
            assertThat(plan.demands().getFirst().collected()).isEqualTo(1);
            assertThat(plan.demands().getFirst().remaining()).isZero();
            verify(f.interaction).clickSlot(7, 3, 0, SlotActionType.QUICK_MOVE, f.player);
            verifyNoMoreInteractions(f.interaction);
        }
    }

    @Test
    void lockedSourceAndFullInventoryDoNotConsumeDemandOrSendClicks() throws Exception {
        try (Fixture f = new Fixture()) {
            ItemStack target = namedBox("A");
            f.menu.getSlot(0).setStack(target.copy());
            JsonObject slots = new JsonObject();
            JsonArray locked = new JsonArray();
            locked.add(0);
            slots.add("test:source", locked);
            JsonObject state = new JsonObject();
            state.add("lockedContainerSlots", slots);
            JsonObject root = new JsonObject();
            root.add("containerLock", state);
            QuickContainerLock.loadPersistentState(root);
            invoke(
                    QuickContainerLock.class,
                    null,
                    "rememberHandlerKey",
                    new Class<?>[] { ScreenHandler.class, String.class },
                    f.menu,
                    "test:source"
            );
            assertThat(QuickContainerLock.isLockedSlot(f.menu, 0)).isTrue();
            Plan plan = f.plan(target);
            f.collect("collectToPlayer", plan);
            assertThat(plan.demands().getFirst().remaining()).isEqualTo(1);
            assertThat(f.menu.getSlot(0).hasStack()).isTrue();
            verifyNoInteractions(f.interaction);

            QuickContainerLock.clearPersistentState();
            for (int i = 0; i < 36; i++) {
                f.inventory.setStack(i, new ItemStack(Items.DIRT, 64));
            }
            f.collect("collectToPlayer", plan);
            assertThat(plan.demands().getFirst().remaining()).isEqualTo(1);
            assertThat(f.menu.getSlot(0).hasStack()).isTrue();
            verifyNoInteractions(f.interaction);
        }
    }

    @Test
    void normalMaterialPlanningStillAggregatesAndCountsByItem() throws Exception {
        try (Fixture f = new Fixture()) {
            ItemStack named = new ItemStack(Items.DIRT);
            named.set(DataComponentTypes.CUSTOM_NAME, Text.literal("named"));
            f.inventory.setStack(0, named.copy());
            Plan plan = f.plan(named, new ItemStack(Items.DIRT));
            assertThat(plan.targets()).hasSize(1);
            assertThat(plan.demands()).hasSize(1);
            assertThat(plan.demands().getFirst().missing()).isEqualTo(2);
            assertThat(plan.demands().getFirst().remaining()).isEqualTo(1);
        }
    }

    private static ItemStack namedBox(String name) {
        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    private record Plan(List<QuickMaterialCollector.Demand> demands, List<ItemStack> targets) {
    }

    private static Object invoke(Class<?> owner, Object receiver, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(receiver, args);
    }

    private static PlayerInventory newInventory(ClientPlayerEntity player) throws Exception {
        try {
            return PlayerInventory.class.getConstructor(PlayerEntity.class).newInstance(player);
        } catch (NoSuchMethodException ignored) {
            Class<?> equipment = Class.forName("net.minecraft.entity.EntityEquipment");
            return PlayerInventory.class.getConstructor(PlayerEntity.class, equipment).newInstance(
                    player,
                    equipment.getConstructor().newInstance()
            );
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ClientPlayerEntity player = mock(ClientPlayerEntity.class);
        final PlayerInventory inventory;
        final GenericContainerScreenHandler menu;
        final ClientPlayerInteractionManager interaction = mock(ClientPlayerInteractionManager.class);
        final QuickMaterialCollector collector = new QuickMaterialCollector();
        final MockedStatic<MinecraftClient> minecraft = mockStatic(MinecraftClient.class);
        final MockedStatic<FabricLoader> fabric = mockStatic(FabricLoader.class);
        final MockedStatic<QuickLitematicaMaterialLists> lists = mockStatic(QuickLitematicaMaterialLists.class);
        final MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class);

        Fixture() throws Exception {
            QuickContainerLock.clearPersistentState();
            inventory = newInventory(player);
            when(player.getInventory()).thenReturn(inventory);
            menu = GenericContainerScreenHandler.createGeneric9x3(7, inventory);
            QuickTradeTestSupport.setField(player, "currentScreenHandler", menu);
            MinecraftClient client = mock(MinecraftClient.class);
            QuickTradeTestSupport.setField(client, "player", player);
            QuickTradeTestSupport.setField(client, "interactionManager", interaction);
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            FabricLoader loader = mock(FabricLoader.class);
            when(loader.isModLoaded("litematica")).thenReturn(true);
            fabric.when(FabricLoader::getInstance).thenReturn(loader);
            // 网络边界采用真实菜单的 quickMove；不模拟服务端接受或替换搬运/容量判断。
            doAnswer(call -> {
                assertThat((SlotActionType) call.getArgument(3)).isEqualTo(SlotActionType.QUICK_MOVE);
                menu.quickMove(player, call.getArgument(1));
                return null;
            }).when(interaction).clickSlot(
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    any(SlotActionType.class),
                    any(PlayerEntity.class)
            );
        }

        @SuppressWarnings("unchecked")
        Plan plan(ItemStack... templates) throws Exception {
            var requests = java.util.Arrays.stream(templates).map(
                    stack -> new QuickMaterialCollector.MaterialRequest(stack, 1)
            ).toList();
            lists.when(() -> QuickLitematicaMaterialLists.getVisibleMaterialRequests(player)).thenReturn(requests);
            Object plan = invoke(
                    QuickMaterialCollector.class,
                    collector,
                    "buildMaterialPlan",
                    new Class<?>[] { PlayerEntity.class },
                    player
            );
            return new Plan(
                    (List<QuickMaterialCollector.Demand>) invoke(plan.getClass(), plan, "demands", new Class<?>[0]),
                    (List<ItemStack>) invoke(plan.getClass(), plan, "targetTemplates", new Class<?>[0])
            );
        }

        void collect(String entry, Plan plan) throws Exception {
            invoke(
                    QuickMaterialCollector.class,
                    collector,
                    entry,
                    new Class<?>[] { ScreenHandler.class, List.class, List.class },
                    menu,
                    plan.demands(),
                    plan.targets()
            );
        }

        int count(ItemStack template) {
            int count = 0;
            for (int i = 0; i < 36; i++) {
                if (ItemStack.areItemsAndComponentsEqual(inventory.getStack(i), template)) {
                    count += inventory.getStack(i).getCount();
                }
            }
            return count;
        }

        @Override
        public void close() {
            configs.close();
            lists.close();
            fabric.close();
            minecraft.close();
            QuickContainerLock.clearPersistentState();
        }
    }
}
