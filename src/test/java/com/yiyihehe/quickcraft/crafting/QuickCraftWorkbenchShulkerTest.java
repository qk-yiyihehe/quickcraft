package com.yiyihehe.quickcraft.crafting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftWorkbenchShulkerTest {
    @Test
    @DisplayName("来源潜影盒扫描完整三十六格玩家物品栏")
    void sourceScanCount_usesFullPlayerInventory() {
        assertThat(QuickCraftWorkbenchShulker.sourceScanCount(-1)).isZero();
        assertThat(QuickCraftWorkbenchShulker.sourceScanCount(12)).isEqualTo(12);
        assertThat(QuickCraftWorkbenchShulker.sourceScanCount(30)).isEqualTo(30);
        assertThat(QuickCraftWorkbenchShulker.sourceScanCount(36)).isEqualTo(36);
        assertThat(QuickCraftWorkbenchShulker.sourceScanCount(40)).isEqualTo(36);
    }

    @Test
    @DisplayName("零操作间隔允许同一 Tick 扫完来源盒")
    void sourceBatchesPerTick_usesFullScanBudgetAtZeroInterval() {
        assertThat(QuickCraftWorkbenchShulker.sourceBatchesPerTick(0)).isEqualTo(36);
        assertThat(QuickCraftWorkbenchShulker.sourceBatchesPerTick(1)).isEqualTo(1);
        assertThat(QuickCraftWorkbenchShulker.sourceBatchesPerTick(20)).isEqualTo(1);
    }

    @Test
    @DisplayName("直填接受所有已识别且无返还物的配方")
    void directFillRecipePolicy_excludesUnknownAndRemainderRecipes() {
        assertThat(QuickCraftWorkbench.canDirectFillRecipe(true, false)).isTrue();
        assertThat(QuickCraftWorkbench.canDirectFillRecipe(false, false)).isFalse();
        assertThat(QuickCraftWorkbench.canDirectFillRecipe(true, true)).isFalse();
    }
}
