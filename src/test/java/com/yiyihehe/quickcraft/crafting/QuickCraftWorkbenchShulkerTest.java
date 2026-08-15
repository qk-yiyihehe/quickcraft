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
    @DisplayName("零操作间隔仍限制每 Tick 一个来源盒")
    void sourceBatchesPerTick_keepsOneSourcePerTickAtZeroInterval() {
        assertThat(QuickCraftWorkbenchShulker.sourceBatchesPerTick(0)).isEqualTo(1);
        assertThat(QuickCraftWorkbenchShulker.sourceBatchesPerTick(1)).isEqualTo(1);
        assertThat(QuickCraftWorkbenchShulker.sourceBatchesPerTick(20)).isEqualTo(1);
    }

    @Test
    @DisplayName("直填接受所有已识别且无返还物的配方")
    void directFillRecipePolicy_excludesUnknownAndRemainderRecipes() {
        assertThat(QuickCraftWorkbenchShulkerCraft.canDirectFillRecipe(true, false)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.canDirectFillRecipe(false, false)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.canDirectFillRecipe(true, true)).isFalse();
    }

    @Test
    @DisplayName("潜影盒喷射操作间隔跨补货任务完整保留")
    void shulkerCraftCooldownAfterAction_keepsConfiguredInterval() {
        assertThat(QuickCraftWorkbenchShulker.shulkerCraftCooldownAfterAction(-1)).isZero();
        assertThat(QuickCraftWorkbenchShulker.shulkerCraftCooldownAfterAction(0)).isZero();
        assertThat(QuickCraftWorkbenchShulker.shulkerCraftCooldownAfterAction(1)).isEqualTo(1);
        assertThat(QuickCraftWorkbenchShulker.shulkerCraftCooldownAfterAction(20)).isEqualTo(20);
    }

    @Test
    @DisplayName("不可堆叠材料每次合成后立即补料")
    void perCraftMaterialPolicy_onlyIncludesUnstackableItems() {
        assertThat(QuickCraftWorkbenchShulkerCraft.isPerCraftMaterial(1)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.isPerCraftMaterial(16)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.isPerCraftMaterial(64)).isFalse();
    }

    @Test
    @DisplayName("配方快照只允许在同一个工作台同步 ID 内复用")
    void recipeSnapshotReuse_requiresSameWorkbenchSyncId() {
        assertThat(QuickCraftWorkbenchShulkerCraft.canReuseSnapshot(7, 7, true)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.canReuseSnapshot(8, 7, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.canReuseSnapshot(7, 7, false)).isFalse();
    }

    @Test
    @DisplayName("背包散料不足以覆盖所有空配方格时留给潜影盒直填")
    void looseItemFill_requiresAtLeastOneCompletePatternRound() {
        assertThat(QuickCraftWorkbenchShulkerCraft.canLooseItemsCompleteEmptyPatternSlots(2, 3)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.canLooseItemsCompleteEmptyPatternSlots(3, 3)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.canLooseItemsCompleteEmptyPatternSlots(0, 0)).isTrue();
    }

    @Test
    @DisplayName("重复材料部分槽耗尽时重新均分尾数")
    void repeatedPatternRebalance_requiresMixedEmptyAndOccupiedSlots() {
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRebalancePatternGroup(3, 1)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRebalancePatternGroup(3, 2)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRebalancePatternGroup(3, 0)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRebalancePatternGroup(3, 3)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRebalancePatternGroup(1, 1)).isFalse();
    }

    @Test
    @DisplayName("空潜影盒可容纳二十七组普通产物")
    void outputCapacity_emptyShulkerHasFullCapacity() {
        assertThat(QuickCraftWorkbenchShulkerOutput.calculateCapacity(64, 0, 0))
                .isEqualTo(27 * 64);
    }

    @Test
    @DisplayName("同类产物盒容量包含未满堆和空槽")
    void outputCapacity_countsMatchingStacksAndEmptySlots() {
        assertThat(QuickCraftWorkbenchShulkerOutput.calculateCapacity(64, 2, 4))
                .isEqualTo(4 + 25 * 64);
    }

    @Test
    @DisplayName("满盒不报告额外容量")
    void outputCapacity_fullShulkerHasNoCapacity() {
        assertThat(QuickCraftWorkbenchShulkerOutput.calculateCapacity(64, 27, 0)).isZero();
    }

    @Test
    @DisplayName("产物空盒优先扫描主背包而非快捷栏")
    void outputBoxScanPriority_prefersMainInventory() {
        assertThat(QuickCraftWorkbenchShulkerOutput.outputBoxScanPriority(9)).isZero();
        assertThat(QuickCraftWorkbenchShulkerOutput.outputBoxScanPriority(35)).isZero();
        assertThat(QuickCraftWorkbenchShulkerOutput.outputBoxScanPriority(0)).isEqualTo(1);
        assertThat(QuickCraftWorkbenchShulkerOutput.outputBoxScanPriority(8)).isEqualTo(1);
    }
}
