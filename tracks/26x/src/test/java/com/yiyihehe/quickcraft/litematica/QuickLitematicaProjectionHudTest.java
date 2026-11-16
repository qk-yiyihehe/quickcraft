package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaProjectionHudTest {
    @Test
    void hiddenSnapshotOnlyChangesOnRefreshAndKeepsHistory() {
        MaterialListBase list = list(true, 1);
        MaterialListEntry stone = entry(new ItemStack(Items.STONE), 16, 16, 16);

        Set<String> hidden = QuickLitematicaProjectionMaterials.hiddenSnapshot(
                list, List.of(stone), Set.of(), true);
        assertThat(hidden).hasSize(1);

        stone.setCountAvailable(0);
        assertThat(QuickLitematicaProjectionMaterials.hiddenSnapshot(
                list, List.of(stone), hidden, false))
                .withFailMessage("放进暂存箱后不能按当前 available 重新出现")
                .containsExactlyInAnyOrderElementsOf(hidden);
        assertThat(QuickLitematicaProjectionMaterials.buildMissingEntries(list, List.of(stone), hidden)).isEmpty();
    }

    @Test
    void reachingTargetBetweenRefreshesWaitsForNextRefresh() {
        MaterialListBase list = list(true, 1);
        MaterialListEntry stone = entry(new ItemStack(Items.STONE), 16, 16, 0);

        assertThat(QuickLitematicaProjectionMaterials.hiddenSnapshot(
                list, List.of(stone), Set.of(), false)).isEmpty();
        assertThat(QuickLitematicaProjectionMaterials.buildMissingEntries(list, List.of(stone), Set.of()))
                .extracting(entry -> entry.getStack().getItem()).containsExactly(Items.STONE);

        stone.setCountAvailable(16);
        assertThat(QuickLitematicaProjectionMaterials.hiddenSnapshot(
                list, List.of(stone), Set.of(), false))
                .withFailMessage("两次原版刷新之间不能按当前 available 立即隐藏")
                .isEmpty();
        assertThat(QuickLitematicaProjectionMaterials.hiddenSnapshot(
                list, List.of(stone), Set.of(), true)).hasSize(1);
    }

    @Test
    void multiplierControlsWhenAnEntryBecomesHidden() {
        MaterialListBase list = list(true, 2);
        MaterialListEntry stone = entry(new ItemStack(Items.STONE), 10, 10, 20);
        assertThat(QuickLitematicaProjectionMaterials.hiddenSnapshot(
                list, List.of(stone), Set.of(), true)).hasSize(1);

        stone.setCountAvailable(19);
        assertThat(QuickLitematicaProjectionMaterials.hiddenSnapshot(
                list, List.of(stone), Set.of(), true)).isEmpty();
        assertThat(QuickLitematicaProjectionMaterials.buildMissingEntries(list, List.of(stone), Set.of()))
                .extracting(entry -> entry.getStack().getItem()).containsExactly(Items.STONE);
    }

    @Test
    void componentIdentityKeepsOnlyTheCompletedStackHidden() {
        MaterialListBase list = list(true, 1);
        ItemStack namedA = new ItemStack(Items.STONE);
        namedA.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
        ItemStack namedB = new ItemStack(Items.STONE);
        namedB.set(DataComponents.CUSTOM_NAME, Component.literal("B"));
        MaterialListEntry a = entry(namedA, 16, 16, 16);
        MaterialListEntry b = entry(namedB, 16, 16, 0);

        Set<String> hidden = QuickLitematicaProjectionMaterials.hiddenSnapshot(
                list, List.of(a, b), Set.of(), true);
        List<MaterialListEntry> missing = QuickLitematicaProjectionMaterials.buildMissingEntries(
                list, List.of(a, b), hidden);
        assertThat(hidden).hasSize(1);
        assertThat(missing).extracting(entry -> entry.getStack().getHoverName().getString()).containsExactly("B");
    }

    @Test
    void disabledHideReturnsToMissingRows() {
        MaterialListBase list = list(false, 1);
        MaterialListEntry stone = entry(new ItemStack(Items.STONE), 16, 16, 0);
        Set<String> hidden = QuickLitematicaProjectionMaterials.hiddenSnapshot(
                list, List.of(stone), Set.of("stone"), false);
        assertThat(hidden).isEmpty();
        assertThat(QuickLitematicaProjectionMaterials.buildMissingEntries(list, List.of(stone), hidden))
                .extracting(entry -> entry.getStack().getItem()).containsExactly(Items.STONE);
    }


    @Test
    void containerHudDoesNotKeepHistoryWhenHideIsDisabled() {
        var list = containerList();
        var stone = entry(new ItemStack(Items.STONE), 16, 16, 16);
        list.hudEntries = new java.util.ArrayList<>(List.of(stone));
        list.hudRevision = QuickLitematicaContainerMemory.revision();
        list.setHideAvailable(false);
        assertThat(list.quickcraft$getHudMaterialsMissing(true)).isEmpty();
        stone.setCountAvailable(0);
        assertThat(list.quickcraft$getHudMaterialsMissing(true)).hasSize(1);
    }

    @Test
    void containerHudExplicitResetRestoresHiddenRows() {
        var list = containerList();
        var stone = entry(new ItemStack(Items.STONE), 16, 16, 16);
        list.hudEntries = new java.util.ArrayList<>(List.of(stone));
        list.hudRevision = QuickLitematicaContainerMemory.revision();
        list.setHideAvailable(true);
        assertThat(list.quickcraft$getHudMaterialsMissing(true)).isEmpty();
        stone.setCountAvailable(0);
        assertThat(list.quickcraft$getHudMaterialsMissing(true)).isEmpty();
        list.refreshPreFilteredList();
        assertThat(list.quickcraft$getHudMaterialsMissing(true)).hasSize(1);
    }


    @Test
    void summaryTargetAndLabelShareOneGroupWalk() {
        var list = containerList("perf-summary");
        var stone = new ItemStack(Items.STONE, 5);
        list.data.groups = List.of(new QuickLitematicaContainerMaterials.ContainerGroup(
                new ItemStack(Items.CHEST), "chest", null, 1, List.of(stone, stone), List.of(),
                false, "test-group"));
        var config = com.yiyihehe.quickcraft.config.QuickCraftConfigs.ProjectionTools.CONTAINER_FILL_REPLACEMENTS;
        var previous = List.copyOf(config.getStrings());
        var enabled = com.yiyihehe.quickcraft.config.QuickCraftConfigs.ProjectionTools.SHOW_LITEMATICA_MATERIAL_REPLACEMENTS;
        boolean previousEnabled = enabled.getBooleanValue();
        try (var replacements = org.mockito.Mockito.mockStatic(
                QuickLitematicaContainerReplacements.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            enabled.setBooleanValue(true);
            config.setStrings(List.of(list.data.replacementContextKey + "||test-group||-1||minecraft:stone->minecraft:dirt"));
            var oldTarget = list.quickcraft$getSummaryReplacementTarget(stone);
            var oldLabel = list.quickcraft$getSummaryReplacementLabel(stone);
            replacements.clearInvocations();
            var summary = list.quickcraft$getSummaryReplacement(stone);
            assertThat(summary.target()).isPresent();
            assertThat(summary.target().orElseThrow().getItem()).isEqualTo(Items.DIRT);
            assertThat(summary.target().orElseThrow().getCount()).isEqualTo(oldTarget.orElseThrow().getCount());
            assertThat(summary.label()).isEqualTo(oldLabel);
            replacements.verify(() -> QuickLitematicaContainerReplacements.getReplacementTarget(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt()), org.mockito.Mockito.times(2));
            replacements.verify(() -> QuickLitematicaContainerReplacements.applyToStack(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt()), org.mockito.Mockito.never());
            config.setStrings(List.of(
                    list.data.replacementContextKey + "||test-group||0||minecraft:stone->minecraft:dirt",
                    list.data.replacementContextKey + "||test-group||1||minecraft:stone->minecraft:glass"));
            var multiple = list.quickcraft$getSummaryReplacement(stone);
            assertThat(multiple.label()).isEqualTo(list.quickcraft$getSummaryReplacementLabel(stone));
            assertThat(multiple.target().orElseThrow().getItem()).isEqualTo(Items.DIRT);
            var lookup = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            config.setStrings(List.of(QuickLitematicaContainerReplacements.encodeContainerRule(
                    new QuickLitematicaContainerReplacements.ContainerReplacement(stone, ItemStack.EMPTY,
                            list.data.replacementContextKey, "test-group", -1, false), lookup)));
            var emptyTarget = list.quickcraft$getSummaryReplacement(stone);
            assertThat(emptyTarget.target()).isPresent();
            assertThat(emptyTarget.target().orElseThrow().isEmpty()).isTrue();
            assertThat(emptyTarget.label()).isEqualTo(list.quickcraft$getSummaryReplacementLabel(stone));
            config.setStrings(List.of());
            var noReplacement = list.quickcraft$getSummaryReplacement(stone);
            assertThat(noReplacement.target()).isEmpty();
            assertThat(noReplacement.label()).isEmpty();
        } finally {
            config.setStrings(previous);
            enabled.setBooleanValue(previousEnabled);
        }
    }

    @Test
    void countsAllTargetsInOneInventoryWalk() {
        var client = org.mockito.Mockito.mock(net.minecraft.client.Minecraft.class);
        var player = org.mockito.Mockito.mock(net.minecraft.client.player.LocalPlayer.class);
        var inventory = org.mockito.Mockito.mock(net.minecraft.world.entity.player.Inventory.class);
        client.player = player;
        org.mockito.Mockito.when(player.getInventory()).thenReturn(inventory);
        org.mockito.Mockito.when(inventory.getContainerSize()).thenReturn(2);
        org.mockito.Mockito.when(inventory.getItem(0)).thenReturn(new ItemStack(Items.STONE, 16));
        org.mockito.Mockito.when(inventory.getItem(1)).thenReturn(new ItemStack(Items.DIRT, 32));

        try (var minecraft = org.mockito.Mockito.mockStatic(net.minecraft.client.Minecraft.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(client);
            int[] counts = QuickLitematicaMaterialInventory.countAvailableFor(
                    List.of(new ItemStack(Items.STONE), new ItemStack(Items.DIRT)));
            assertThat(counts).containsExactly(16, 32);
            org.mockito.Mockito.verify(inventory, org.mockito.Mockito.times(2))
                    .getItem(org.mockito.ArgumentMatchers.anyInt());
        }
    }

    @Test
    void batchCountsKeepComponentsMultipliersDepthAndDuplicateTargets() {
        var client = org.mockito.Mockito.mock(net.minecraft.client.Minecraft.class);
        var player = org.mockito.Mockito.mock(net.minecraft.client.player.LocalPlayer.class);
        var inventory = org.mockito.Mockito.mock(net.minecraft.world.entity.player.Inventory.class);
        client.player = player;
        org.mockito.Mockito.when(player.getInventory()).thenReturn(inventory);
        var named = new ItemStack(Items.STONE, 5);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("named"));
        // 模拟服务端允许堆叠潜影盒；26x 的真实内容解码会拒绝超过组件上限的栈。
        var inner = new ItemStack(Items.SHULKER_BOX, 3);
        inner.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64);
        inner.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 4))));
        var outer = new ItemStack(Items.SHULKER_BOX, 2);
        outer.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64);
        outer.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(inner)));
        ItemStack tooDeep = new ItemStack(Items.STONE, 7);
        for (int i = 0; i < 5; i++) {
            var box = new ItemStack(Items.SHULKER_BOX);
            box.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(tooDeep)));
            tooDeep = box;
        }
        var stacks = List.of(new ItemStack(Items.STONE, 11), named, outer, tooDeep, ItemStack.EMPTY);
        org.mockito.Mockito.when(inventory.getContainerSize()).thenReturn(stacks.size());
        org.mockito.Mockito.when(inventory.getItem(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> stacks.get(call.getArgument(0)));
        try (var minecraft = org.mockito.Mockito.mockStatic(net.minecraft.client.Minecraft.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(client);
            var stone = new ItemStack(Items.STONE);
            assertThat(outer.getItem()).isInstanceOf(net.minecraft.world.item.BlockItem.class);
            assertThat(((net.minecraft.world.item.BlockItem) outer.getItem()).getBlock())
                    .isInstanceOf(net.minecraft.world.level.block.ShulkerBoxBlock.class);
            var decodedInner = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(outer);
            assertThat(decodedInner).hasSize(1);
            var decodedStone = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(decodedInner.getFirst());
            assertThat(decodedStone).hasSize(1);
            assertThat(ItemStack.isSameItemSameComponents(stone, decodedStone.getFirst())).isTrue();
            assertThat(QuickLitematicaMaterialInventory.countAvailable(List.of(stone))).isEqualTo(35);
            var targets = List.of(stone, named.copy(), stone.copy(), ItemStack.EMPTY);
            assertThat(QuickLitematicaMaterialInventory.countAvailableFor(targets)).containsExactly(35, 5, 35, 0);
            for (int i = 0; i < targets.size(); i++) {
                assertThat(QuickLitematicaMaterialInventory.countAvailable(List.of(targets.get(i))))
                        .isEqualTo(new int[] {35, 5, 35, 0}[i]);
            }
            org.mockito.Mockito.clearInvocations(inventory);
            assertThat(QuickLitematicaMaterialInventory.countAvailableFor(List.of())).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(inventory);
        }
    }

    @Test
    void productionProjectionHudKeepsHistoryAcrossIgnoreAndResetsExplicitly() {
        var list = list(true, 1);
        var client = org.mockito.Mockito.mock(net.minecraft.client.Minecraft.class);
        client.player = org.mockito.Mockito.mock(net.minecraft.client.player.LocalPlayer.class);
        var available = new java.util.concurrent.atomic.AtomicInteger(16);
        var ignored = new java.util.concurrent.atomic.AtomicReference<Set<String>>(Set.of());
        try (var minecraft = org.mockito.Mockito.mockStatic(net.minecraft.client.Minecraft.class);
             var projection = org.mockito.Mockito.mockStatic(QuickLitematicaProjectionMaterials.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             var conversion = org.mockito.Mockito.mockStatic(QuickLitematicaMaterialCountAccess.class);
             var inventories = org.mockito.Mockito.mockStatic(QuickLitematicaMaterialInventory.class);
             var ignores = org.mockito.Mockito.mockStatic(QuickLitematicaMaterialIgnores.class);
             var contexts = org.mockito.Mockito.mockStatic(QuickLitematicaMaterialLists.class);
             var replacements = org.mockito.Mockito.mockStatic(QuickLitematicaContainerReplacements.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(client);
            projection.when(() -> QuickLitematicaProjectionMaterials.replacementSource(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new ItemStack(Items.STONE));
            conversion.when(() -> QuickLitematicaMaterialCountAccess.blocks(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(call -> List.of(entry(new ItemStack(Items.STONE), 16, 16, 0)));
            inventories.when(() -> QuickLitematicaMaterialInventory.countAvailableFor(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(call -> new int[] { available.get() });
            ignores.when(() -> QuickLitematicaMaterialIgnores.effective(list)).thenAnswer(call -> ignored.get());
            contexts.when(() -> QuickLitematicaMaterialLists.getReplacementContextKey(list)).thenReturn("test");
            var counts = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<net.minecraft.world.level.block.state.BlockState>();
            counts.put(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 16);
            QuickLitematicaProjectionMaterials.prepareEditor(list, counts, counts, counts, counts, counts, counts);
            QuickLitematicaProjectionMaterials.editorEntries(list,
                    List.of(entry(new ItemStack(Items.STONE), 16, 16, 0)));
            assertThat(QuickLitematicaProjectionMaterials.hud(list, true, true, List::of)).isEmpty();
            available.set(0);
            assertThat(QuickLitematicaProjectionMaterials.hud(list, true, false, List::of)).isEmpty();
            assertThat(QuickLitematicaProjectionMaterials.hud(list, true, true, List::of)).isEmpty();
            ignored.set(Set.of("unrelated"));
            assertThat(QuickLitematicaProjectionMaterials.hud(list, true, true, List::of)).isEmpty();
            QuickLitematicaProjectionMaterials.resetHud(list);
            assertThat(QuickLitematicaProjectionMaterials.hud(list, true, true, List::of)).hasSize(1);
        } finally {
            QuickLitematicaProjectionMaterials.resetHud(list);
        }
    }

    private static QuickLitematicaContainerMaterials.ContainerMaterialList containerList() {
        return containerList("");
    }

    private static QuickLitematicaContainerMaterials.ContainerMaterialList containerList(String context) {
        try (var memory = org.mockito.Mockito.mockStatic(QuickLitematicaContainerMemory.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             var minecraft = org.mockito.Mockito.mockStatic(net.minecraft.client.Minecraft.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(mock(net.minecraft.client.Minecraft.class));
            var schematic = mock(fi.dy.masa.litematica.schematic.LitematicaSchematic.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
            memory.when(() -> QuickLitematicaContainerMemory.original(schematic)).thenReturn(schematic);
            var data = QuickLitematicaContainerMaterials.ContainerMaterialsData.create(schematic, List.of(), context);
            return new QuickLitematicaContainerMaterials.ContainerMaterialList(data, null);
        }
    }

    private static MaterialListBase list(boolean hideAvailable, int multiplier) {
        MaterialListBase list = mock(MaterialListBase.class);
        when(list.getHideAvailable()).thenReturn(hideAvailable);
        when(list.getMultiplier()).thenReturn(multiplier);
        return list;
    }

    private static MaterialListEntry entry(ItemStack stack, int total, int missing, int available) {
        return new MaterialListEntry(stack, total, missing, 0, available);
    }
}



