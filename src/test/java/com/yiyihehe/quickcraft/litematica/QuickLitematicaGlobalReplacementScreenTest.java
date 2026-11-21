package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaGlobalReplacementScreenTest {
    @Test
    void partialLastPageDoesNotShiftBackAndRepeatPreviousRows() {
        assertThat(QuickLitematicaGlobalReplacementScreen.pageStart(7, 8, 7)).isEqualTo(7);
        assertThat(QuickLitematicaGlobalReplacementScreen.pageStart(14, 15, 7)).isEqualTo(14);
        assertThat(QuickLitematicaGlobalReplacementScreen.pageStart(7, 7, 7)).isZero();
        assertThat(QuickLitematicaGlobalReplacementScreen.pageStart(-7, 8, 7)).isZero();
        assertThat(QuickLitematicaGlobalReplacementScreen.pageStart(14, 0, 7)).isZero();
    }

    @Test
    void incompatibleSourceChangeRemovesTheOldRuleAndClearsTheDraftTarget() {
        var config = QuickCraftConfigs.ProjectionTools.GLOBAL_PROJECTION_MATERIAL_REPLACEMENTS;
        List<String> old = List.copyOf(config.getStrings());
        try (var configs = mockStatic(QuickCraftConfigs.class, CALLS_REAL_METHODS)) {
            configs.when(QuickCraftConfigs::saveToFile).thenAnswer(invocation -> null);
            config.setStrings(List.of("minecraft:stone->minecraft:glass"));
            var draft = new QuickLitematicaGlobalReplacementScreen.Draft(
                    new ItemStack(Items.STONE), new ItemStack(Items.GLASS));

            assertThat(QuickLitematicaGlobalReplacementScreen.applyChoice(
                    draft, true, new ItemStack(Items.STONE_SLAB), QuickLitematicaContainerReplacements.Scope.PROJECTION))
                    .isTrue();
            assertThat(draft.source.isOf(Items.STONE_SLAB)).isTrue();
            assertThat(draft.target).isNull();
            assertThat(config.getStrings()).isEmpty();
            assertThat(QuickLitematicaGlobalReplacements.rules(QuickLitematicaContainerReplacements.Scope.PROJECTION)).isEmpty();
        } finally {
            config.setStrings(old);
        }
    }

    @Test
    void choosingEmptyBeforeTheSourceSavesAnExplicitEmptyTargetInBothScopes() {
        var source = new ItemStack(Items.STONE);
        try (var configs = mockStatic(QuickCraftConfigs.class, CALLS_REAL_METHODS)) {
            configs.when(QuickCraftConfigs::saveToFile).thenAnswer(invocation -> null);
            for (var scope : QuickLitematicaContainerReplacements.Scope.values()) {
                var config = QuickLitematicaGlobalReplacements.config(scope);
                List<String> old = List.copyOf(config.getStrings());
                try {
                    config.setStrings(List.of());
                    var draft = new QuickLitematicaGlobalReplacementScreen.Draft(ItemStack.EMPTY, null);
                    assertThat(QuickLitematicaGlobalReplacementScreen.applyChoice(draft, false, ItemStack.EMPTY, scope)).isFalse();
                    assertThat(draft.target).isSameAs(ItemStack.EMPTY);
                    assertThat(config.getStrings()).isEmpty();
                    assertThat(QuickLitematicaGlobalReplacementScreen.applyChoice(draft, true, source, scope)).isTrue();
                    assertThat(QuickLitematicaGlobalReplacements.rules(scope)).singleElement().satisfies(rule -> {
                        assertThat(QuickLitematicaContainerReplacements.matchesSource(source, rule)).isTrue();
                        assertThat(rule.target().isEmpty()).isTrue();
                    });
                } finally {
                    config.setStrings(old);
                }
            }
        }
    }

    @Test
    void sourceAndTargetReselectionSavesOneConsistentRule() {
        var config = QuickCraftConfigs.ProjectionTools.GLOBAL_PROJECTION_MATERIAL_REPLACEMENTS;
        List<String> old = List.copyOf(config.getStrings());
        try (var configs = mockStatic(QuickCraftConfigs.class, CALLS_REAL_METHODS)) {
            configs.when(QuickCraftConfigs::saveToFile).thenAnswer(invocation -> null);
            config.setStrings(List.of("minecraft:stone->minecraft:glass"));
            var draft = new QuickLitematicaGlobalReplacementScreen.Draft(
                    new ItemStack(Items.STONE), new ItemStack(Items.GLASS));

            QuickLitematicaGlobalReplacementScreen.applyChoice(
                    draft, true, new ItemStack(Items.STONE_SLAB), QuickLitematicaContainerReplacements.Scope.PROJECTION);
            assertThat(QuickLitematicaGlobalReplacementScreen.applyChoice(
                    draft, false, new ItemStack(Items.OAK_SLAB), QuickLitematicaContainerReplacements.Scope.PROJECTION))
                    .isTrue();
            var rules = QuickLitematicaGlobalReplacements.rules(QuickLitematicaContainerReplacements.Scope.PROJECTION);
            assertThat(rules).hasSize(1);
            assertThat(QuickLitematicaGlobalReplacements.source(rules.getFirst()).isOf(Items.STONE_SLAB)).isTrue();
            assertThat(rules.getFirst().target().isOf(Items.OAK_SLAB)).isTrue();
            assertThat(draft.source.isOf(Items.STONE_SLAB)).isTrue();
            assertThat(draft.target.isOf(Items.OAK_SLAB)).isTrue();
        } finally {
            config.setStrings(old);
        }
    }

    @Test
    void invalidTargetKeepsTheSavedRuleAndCompatibleSourceEditReplacesOnlyItsSource() {
        var config = QuickCraftConfigs.ProjectionTools.GLOBAL_PROJECTION_MATERIAL_REPLACEMENTS;
        List<String> old = List.copyOf(config.getStrings());
        try (var configs = mockStatic(QuickCraftConfigs.class, CALLS_REAL_METHODS)) {
            configs.when(QuickCraftConfigs::saveToFile).thenAnswer(invocation -> null);
            List<String> initial = List.of("minecraft:stone->minecraft:glass", "minecraft:dirt->minecraft:oak_planks");
            config.setStrings(initial);
            var draft = new QuickLitematicaGlobalReplacementScreen.Draft(
                    new ItemStack(Items.STONE), new ItemStack(Items.GLASS));
            var scope = QuickLitematicaContainerReplacements.Scope.PROJECTION;
            assertThat(QuickLitematicaGlobalReplacementScreen.applyChoice(
                    draft, false, new ItemStack(Items.OAK_SLAB), scope)).isFalse();
            assertThat(draft.source.isOf(Items.STONE)).isTrue();
            assertThat(draft.target.isOf(Items.GLASS)).isTrue();
            assertThat(config.getStrings()).containsExactlyElementsOf(initial);
            configs.verify(QuickCraftConfigs::saveToFile, org.mockito.Mockito.never());

            assertThat(QuickLitematicaGlobalReplacementScreen.applyChoice(
                    draft, true, new ItemStack(Items.COBBLESTONE), scope)).isTrue();
            var rules = QuickLitematicaGlobalReplacements.rules(scope);
            assertThat(rules).hasSize(2);
            assertThat(rules).anySatisfy(rule -> {
                assertThat(QuickLitematicaGlobalReplacements.source(rule).isOf(Items.COBBLESTONE)).isTrue();
                assertThat(rule.target().isOf(Items.GLASS)).isTrue();
            });
            assertThat(rules).anySatisfy(rule -> {
                assertThat(QuickLitematicaGlobalReplacements.source(rule).isOf(Items.DIRT)).isTrue();
                assertThat(rule.target().isOf(Items.OAK_PLANKS)).isTrue();
            });
            assertThat(draft.source.isOf(Items.COBBLESTONE)).isTrue();
            assertThat(draft.target.isOf(Items.GLASS)).isTrue();
        } finally {
            config.setStrings(old);
        }
    }
}
