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
            inventories.when(() -> QuickLitematicaMaterialInventory.countAvailable(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(call -> available.get());
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
        try (var memory = org.mockito.Mockito.mockStatic(QuickLitematicaContainerMemory.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             var minecraft = org.mockito.Mockito.mockStatic(net.minecraft.client.Minecraft.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(mock(net.minecraft.client.Minecraft.class));
            var schematic = mock(fi.dy.masa.litematica.schematic.LitematicaSchematic.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
            memory.when(() -> QuickLitematicaContainerMemory.original(schematic)).thenReturn(schematic);
            var data = QuickLitematicaContainerMaterials.ContainerMaterialsData.create(schematic, List.of(), "");
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



