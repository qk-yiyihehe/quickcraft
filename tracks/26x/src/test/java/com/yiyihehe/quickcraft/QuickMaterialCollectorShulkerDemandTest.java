package com.yiyihehe.quickcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialLists;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
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
        filled.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE))));

        assertThat(QuickMaterialCollectorShulkerSelection.matchesDemandTemplate(empty, empty)).isTrue();
        assertThat(QuickMaterialCollectorShulkerSelection.matchesDemandTemplate(filled, empty)).isFalse();
        assertThat(QuickMaterialCollectorShulkerSelection.matchesDemandTemplate(
                redBox(), empty)).isFalse();
    }

    @Test
    void normalMaterialDemandStillMatchesByItemType() {
        assertThat(QuickMaterialCollectorShulkerSelection.matchesDemandTemplate(
                new ItemStack(Items.DIRT, 1), new ItemStack(Items.DIRT, 64))).isTrue();
        assertThat(QuickMaterialCollectorShulkerSelection.matchesDemandTemplate(
                new ItemStack(Items.STONE), new ItemStack(Items.DIRT))).isFalse();
    }

    @Test
    void planningKeepsComponentDistinctTargetsAndSubtractsOnlyMatchingBoxes() throws Exception {
        try (Fixture f = new Fixture()) {
            ItemStack a = namedBox("A");
            ItemStack b = namedBox("B");
            f.inventory.setItem(0, b.copy());
            ItemStack filled = a.copy();
            filled.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE))));
            f.inventory.setItem(1, filled);
            Plan plan = f.plan(a, b, redBox());
            assertThat(plan.targets()).hasSize(3);
            assertThat(plan.demands()).hasSize(2);
            assertThat(plan.demands()).allSatisfy(d -> assertThat(d.remaining()).isEqualTo(1));
            assertThat(plan.demands()).anySatisfy(d ->
                    assertThat(ItemStack.isSameItemSameComponents(d.template(), a)).isTrue());
            assertThat(plan.demands()).noneSatisfy(d ->
                    assertThat(ItemStack.isSameItemSameComponents(d.template(), b)).isTrue());
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
            filled.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE))));
            f.inventory.setItem(0, namedBox("B"));
            f.menu.getSlot(0).set(filled.copy());
            f.menu.getSlot(1).set(namedBox("B"));
            f.menu.getSlot(2).set(redBox());
            f.menu.getSlot(3).set(target.copy());
            Plan plan = f.plan(target);
            assertThat(plan.demands()).hasSize(1);
            f.collect(entry, plan);
            assertThat(f.menu.getSlot(0).getItem().getCount()).isEqualTo(1);
            assertThat(f.menu.getSlot(1).hasItem()).isTrue();
            assertThat(f.menu.getSlot(2).hasItem()).isTrue();
            assertThat(f.menu.getSlot(3).hasItem()).isFalse();
            assertThat(f.count(target)).isEqualTo(1);
            assertThat(f.count(namedBox("B"))).isEqualTo(1);
            assertThat(plan.demands().getFirst().collected()).isEqualTo(1);
            assertThat(plan.demands().getFirst().remaining()).isZero();
            verify(f.interaction).handleContainerInput(7, 3, 0, ContainerInput.QUICK_MOVE, f.player);
            verifyNoMoreInteractions(f.interaction);
        }
    }

    @Test
    void lockedSourceAndFullInventoryDoNotConsumeDemandOrSendClicks() throws Exception {
        try (Fixture f = new Fixture()) {
            ItemStack target = namedBox("A");
            f.menu.getSlot(0).set(target.copy());
            JsonObject slots = new JsonObject();
            JsonArray locked = new JsonArray();
            locked.add(0);
            slots.add("test:source", locked);
            JsonObject state = new JsonObject();
            state.add("lockedContainerSlots", slots);
            JsonObject root = new JsonObject();
            root.add("containerLock", state);
            QuickContainerLock.loadPersistentState(root);
            invoke(QuickContainerLock.class, null, "rememberHandlerKey",
                    new Class<?>[]{AbstractContainerMenu.class, String.class}, f.menu, "test:source");
            assertThat(QuickContainerLock.isLockedSlot(f.menu, 0)).isTrue();
            Plan plan = f.plan(target);
            f.collect("collectToPlayer", plan);
            assertThat(plan.demands().getFirst().remaining()).isEqualTo(1);
            assertThat(f.menu.getSlot(0).hasItem()).isTrue();
            verifyNoInteractions(f.interaction);

            QuickContainerLock.clearPersistentState();
            for (int i = 0; i < 36; i++) f.inventory.setItem(i, new ItemStack(Items.DIRT, 64));
            f.collect("collectToPlayer", plan);
            assertThat(plan.demands().getFirst().remaining()).isEqualTo(1);
            assertThat(f.menu.getSlot(0).hasItem()).isTrue();
            verifyNoInteractions(f.interaction);
        }
    }

    @Test
    void normalMaterialPlanningStillAggregatesAndCountsByItem() throws Exception {
        try (Fixture f = new Fixture()) {
            ItemStack named = new ItemStack(Items.DIRT);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
            f.inventory.setItem(0, named.copy());
            Plan plan = f.plan(named, new ItemStack(Items.DIRT));
            assertThat(plan.targets()).hasSize(1);
            assertThat(plan.demands()).hasSize(1);
            assertThat(plan.demands().getFirst().missing()).isEqualTo(2);
            assertThat(plan.demands().getFirst().remaining()).isEqualTo(1);
        }
    }

    private static ItemStack redBox() {
        return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.stream()
                .filter(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)
                        .toString().equals("minecraft:red_shulker_box"))
                .findFirst().orElseThrow());
    }

    private static ItemStack namedBox(String name) {
        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    private record Plan(List<QuickMaterialCollector.Demand> demands, List<ItemStack> targets) { }

    private static Object invoke(Class<?> owner, Object receiver, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(receiver, args);
    }

    private static Inventory newInventory(LocalPlayer player) {
        return new Inventory(player, new net.minecraft.world.entity.EntityEquipment());
    }

    private static final class Fixture implements AutoCloseable {
        final LocalPlayer player = mock(LocalPlayer.class);
        final Inventory inventory;
        final ChestMenu menu;
        final MultiPlayerGameMode interaction = mock(MultiPlayerGameMode.class);
        final QuickMaterialCollector collector = new QuickMaterialCollector();
        final MockedStatic<Minecraft> minecraft = mockStatic(Minecraft.class);
        final MockedStatic<FabricLoader> fabric = mockStatic(FabricLoader.class);
        final MockedStatic<QuickLitematicaMaterialLists> lists = mockStatic(QuickLitematicaMaterialLists.class);
        final MockedStatic<QuickCraftConfigs> configs = mockStatic(QuickCraftConfigs.class);

        Fixture() throws Exception {
            FabricLoader loader = mock(FabricLoader.class);
            when(loader.isModLoaded("litematica")).thenReturn(true);
            fabric.when(FabricLoader::getInstance).thenReturn(loader);
            QuickContainerLock.clearPersistentState();
            inventory = newInventory(player);
            when(player.getInventory()).thenReturn(inventory);
            menu = ChestMenu.threeRows(7, inventory);
            QuickTradeTestSupport.setField(player, "containerMenu", menu);
            Minecraft client = mock(Minecraft.class);
            QuickTradeTestSupport.setField(client, "player", player);
            QuickTradeTestSupport.setField(client, "gameMode", interaction);
            QuickTradeTestSupport.setField(client, "gui", mock(net.minecraft.client.gui.Gui.class));
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            // 网络边界采用真实菜单的 quickMove；不模拟服务端接受或替换搬运/容量判断。
            doAnswer(call -> {
                assertThat((ContainerInput) call.getArgument(3)).isEqualTo(ContainerInput.QUICK_MOVE);
                menu.quickMoveStack(player, call.getArgument(1));
                return null;
            }).when(interaction).handleContainerInput(anyInt(), anyInt(), anyInt(), any(ContainerInput.class), any(Player.class));
        }

        @SuppressWarnings("unchecked")
        Plan plan(ItemStack... templates) throws Exception {
            var requests = java.util.Arrays.stream(templates)
                    .map(stack -> new QuickMaterialCollector.MaterialRequest(stack, 1)).toList();
            lists.when(() -> QuickLitematicaMaterialLists.getVisibleMaterialRequests(player)).thenReturn(requests);
            Object plan = invoke(QuickMaterialCollector.class, collector, "buildMaterialPlan",
                    new Class<?>[]{Player.class}, player);
            return new Plan((List<QuickMaterialCollector.Demand>) invoke(plan.getClass(), plan, "demands", new Class<?>[0]),
                    (List<ItemStack>) invoke(plan.getClass(), plan, "targetTemplates", new Class<?>[0]));
        }

        void collect(String entry, Plan plan) throws Exception {
            invoke(QuickMaterialCollector.class, collector, entry,
                    new Class<?>[]{AbstractContainerMenu.class, List.class, List.class}, menu, plan.demands(), plan.targets());
        }

        int count(ItemStack template) {
            int count = 0;
            for (int i = 0; i < 36; i++) {
                if (ItemStack.isSameItemSameComponents(inventory.getItem(i), template)) {
                    count += inventory.getItem(i).getCount();
                }
            }
            return count;
        }

        @Override
        public void close() {
            configs.close(); lists.close(); fabric.close(); minecraft.close();
            QuickContainerLock.clearPersistentState();
        }
    }
}
