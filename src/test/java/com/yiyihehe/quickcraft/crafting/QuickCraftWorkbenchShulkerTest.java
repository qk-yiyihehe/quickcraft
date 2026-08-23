package com.yiyihehe.quickcraft.crafting;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftWorkbenchShulkerTest {
    @Test
    @DisplayName("流水线模式使用稳定默认值并支持持久化解析")
    void pipelineMode_defaultsToResponseStableAndParsesValues() {
        assertThat(QuickCraftConfigs.Crafting.WORKBENCH_QUICK_SHULKER_PIPELINE_MODE
                .getOptionListValue())
                .isEqualTo(QuickCraftConfigs.WorkbenchShulkerPipelineMode.RESPONSE_STABLE);
        assertThat(QuickCraftConfigs.WorkbenchShulkerPipelineMode.RESPONSE_STABLE
                .fromString("balanced"))
                .isEqualTo(QuickCraftConfigs.WorkbenchShulkerPipelineMode.BALANCED);
        assertThat(QuickCraftConfigs.WorkbenchShulkerPipelineMode.RESPONSE_STABLE
                .fromString("combined_ultra"))
                .isEqualTo(QuickCraftConfigs.WorkbenchShulkerPipelineMode.COMBINED_ULTRA);
        assertThat(QuickCraftConfigs.WorkbenchShulkerPipelineMode.RESPONSE_STABLE
                .fromString("unknown"))
                .isEqualTo(QuickCraftConfigs.WorkbenchShulkerPipelineMode.RESPONSE_STABLE);
    }

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
    @DisplayName("三种流水线使用固定且可解释的来源盒确认边界")
    void sourceBatchesPerAck_followsPipelineMode() {
        assertThat(QuickCraftWorkbenchShulker.sourceBatchesPerAck(
                QuickCraftConfigs.WorkbenchShulkerPipelineMode.RESPONSE_STABLE)).isEqualTo(1);
        assertThat(QuickCraftWorkbenchShulker.sourceBatchesPerAck(
                QuickCraftConfigs.WorkbenchShulkerPipelineMode.BALANCED)).isEqualTo(3);
        assertThat(QuickCraftWorkbenchShulker.sourceBatchesPerAck(
                QuickCraftConfigs.WorkbenchShulkerPipelineMode.COMBINED_ULTRA)).isEqualTo(3);
    }

    @Test
    @DisplayName("直填接受所有已识别且无返还物的配方")
    void directFillRecipePolicy_excludesUnknownAndRemainderRecipes() {
        assertThat(QuickCraftWorkbenchShulkerCraft.canDirectFillRecipe(true, false)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.canDirectFillRecipe(false, false)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.canDirectFillRecipe(true, true)).isFalse();
    }

    @Test
    @DisplayName("仅组合极速把补货和输出放进同一个确认批次")
    void pipelineMode_onlyCombinedUltraMergesRefillAndOutput() {
        assertThat(QuickCraftWorkbenchShulkerCraft.combinesRefillAndOutput(
                QuickCraftConfigs.WorkbenchShulkerPipelineMode.RESPONSE_STABLE)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.combinesRefillAndOutput(
                QuickCraftConfigs.WorkbenchShulkerPipelineMode.BALANCED)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.combinesRefillAndOutput(
                QuickCraftConfigs.WorkbenchShulkerPipelineMode.COMBINED_ULTRA)).isTrue();
    }

    @Test
    @DisplayName("服务端光标恢复遵守可配置等待阈值")
    void cursorRecovery_honorsConfiguredSettleTicks() {
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRecoverShulkerCursor(1, 2)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRecoverShulkerCursor(2, 2)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRecoverShulkerCursor(3, 4)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRecoverShulkerCursor(4, 4)).isTrue();
    }

    @Test
    @DisplayName("未知光标物品遵守可配置超时阈值")
    void cursorTimeout_honorsConfiguredTimeoutTicks() {
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldStopForOccupiedCursor(9, 10)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldStopForOccupiedCursor(10, 10)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldStopForOccupiedCursor(19, 20)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldStopForOccupiedCursor(20, 20)).isTrue();
    }

    @Test
    @DisplayName("确认驱动流水按批次风险选择精确状态或服务端安全边界")
    void ackPipeline_usesExactOutputAndSafePreparationBoundaries() {
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmAckBatch(
                4, false, true, true, false)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmAckBatch(
                4, true, false, true, false)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmAckBatch(
                4, true, false, true, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmAckBatch(
                4, true, true, false, true)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmAckBatch(
                1, false, false, true, false)).isTrue();
    }

    @Test
    @DisplayName("补货与输出按同一确认批次分类")
    void ackPipeline_classifiesCombinedRefillOutputBatches() {
        assertThat(QuickCraftWorkbenchShulkerCraft.classifyAckBatch(0, 0))
                .isEqualTo(QuickCraftWorkbenchShulkerCraft.AckBatchKind.PREPARATION);
        assertThat(QuickCraftWorkbenchShulkerCraft.classifyAckBatch(0, 3))
                .isEqualTo(QuickCraftWorkbenchShulkerCraft.AckBatchKind.REFILL);
        assertThat(QuickCraftWorkbenchShulkerCraft.classifyAckBatch(48, 0))
                .isEqualTo(QuickCraftWorkbenchShulkerCraft.AckBatchKind.OUTPUT);
        assertThat(QuickCraftWorkbenchShulkerCraft.classifyAckBatch(16, 3))
                .isEqualTo(QuickCraftWorkbenchShulkerCraft.AckBatchKind.REFILL_OUTPUT);
    }

    @Test
    @DisplayName("所有补货与输出批次必须等待完整服务端终态")
    void ackPipeline_combinedBatchesRequireCompleteState() {
        assertThat(QuickCraftWorkbenchShulkerCraft.requiresCompleteAckState(
                QuickCraftWorkbenchShulkerCraft.AckBatchKind.OUTPUT, 0)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.requiresCompleteAckState(
                QuickCraftWorkbenchShulkerCraft.AckBatchKind.REFILL_OUTPUT, 3)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.requiresCompleteAckState(
                QuickCraftWorkbenchShulkerCraft.AckBatchKind.REFILL, 3)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.requiresCompleteAckState(
                QuickCraftWorkbenchShulkerCraft.AckBatchKind.REFILL, 1)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.requiresCompleteAckState(
                QuickCraftWorkbenchShulkerCraft.AckBatchKind.PREPARATION, 0)).isFalse();
    }

    @Test
    @DisplayName("完整确认批次按全量包后的精确最终状态确认而不猜包数量")
    void ackPipeline_completeBatchUsesExactFinalStateAfterFullInventory() {
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmCompleteAckBatch(
                0, true, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmCompleteAckBatch(
                9, false, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmCompleteAckBatch(
                9, true, false)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmCompleteAckBatch(
                1, true, true)).isTrue();
    }

    @Test
    @DisplayName("确认驱动接受工作台、光标和玩家库存同步 ID")
    void ackPipeline_acceptsVanillaContainerSyncIds() {
        assertThat(QuickCraftWorkbenchShulkerCraft.isAckRelevantSyncId(7, 7)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.isAckRelevantSyncId(-1, 7)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.isAckRelevantSyncId(-2, 7)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.isAckRelevantSyncId(8, 7)).isFalse();
    }

    @Test
    @DisplayName("单次输出按安全全量回包确认，多次输出要求精确边界")
    void ackPipeline_outputUsesFullInventoryBoundaries() {
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                1, 1, 0, false, false, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                1, 1, 1, true, false, true)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                1, 1, 1, true, true, true)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                64, 64, 62, true, true, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                64, 64, 63, true, false, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                64, 64, 63, true, true, false)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                64, 64, 63, true, true, true)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                66, 64, 63, true, true, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                66, 64, 64, false, true, true)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                66, 64, 64, true, false, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                3, 1, 1, false, true, true)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmOutputAckBatch(
                2, 3, 2, true, true, true)).isFalse();
    }

    @Test
    @DisplayName("停止请求会等待已发送或正在记录的点击完成确认")
    void ackPipeline_defersStopOnlyWhenClicksAreInFlight() {
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldDeferStopForAck(
                true, true, false, 0)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldDeferStopForAck(
                true, false, true, 2)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldDeferStopForAck(
                true, false, true, 0)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldDeferStopForAck(
                false, true, true, 2)).isFalse();
    }

    @Test
    @DisplayName("单次输出在 revision 推进并安静五毫秒后按最终状态确认")
    void ackPipeline_singleOutputConfirmsAfterSlotUpdatesSettle() {
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmSettledSingleOutput(
                1, true, true, true, 4_999_999L)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmSettledSingleOutput(
                1, true, true, true, 5_000_000L)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmSettledSingleOutput(
                1, false, true, true, 10_000_000L)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmSettledSingleOutput(
                2, true, true, true, 10_000_000L)).isFalse();
    }

    @Test
    @DisplayName("无补货的准备批次在服务端安全状态收敛后继续")
    void ackPipeline_preparationBatchConfirmsAfterSafeStateSettles() {
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmSettledNonOutputBatch(
                true, true, 4_999_999L)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmSettledNonOutputBatch(
                true, true, 5_000_000L)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmSettledNonOutputBatch(
                false, true, 10_000_000L)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmSettledNonOutputBatch(
                true, false, 10_000_000L)).isFalse();
    }

    @Test
    @DisplayName("锁定图案尚未补完整时忽略中间配方输出并继续补料")
    void intermediateOutput_onlyContinuesForCompatibleIncompleteGrid() {
        assertThat(QuickCraftWorkbenchShulkerCraft.canContinuePastIntermediateOutput(
                false, true)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.canContinuePastIntermediateOutput(
                true, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.canContinuePastIntermediateOutput(
                false, false)).isFalse();
    }

    @Test
    @DisplayName("确认驱动只按服务端停止推进计算停滞超时")
    void ackPipeline_stallTimeoutAdaptsWithoutSlowingNormalBatches() {
        assertThat(QuickCraftWorkbenchShulkerCraft.ackStallTimeoutMillis(20, 72L))
                .isEqualTo(3_000L);
        assertThat(QuickCraftWorkbenchShulkerCraft.ackStallTimeoutMillis(100, 72L))
                .isEqualTo(5_000L);
        assertThat(QuickCraftWorkbenchShulkerCraft.ackStallTimeoutMillis(20, 800L))
                .isEqualTo(6_400L);
    }

    @Test
    @DisplayName("静默探针只在完整终态批次超时且统计基线可用时发送")
    void ackStatsProbe_onlyStartsAtSafeAdaptiveBoundary() {
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRequestAckStatsProbe(
                QuickCraftWorkbenchShulkerCraft.AckBatchKind.REFILL_OUTPUT,
                3, false, true, false, 3_000L, 3_000L)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRequestAckStatsProbe(
                QuickCraftWorkbenchShulkerCraft.AckBatchKind.REFILL,
                2, false, true, false, 3_000L, 3_000L)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRequestAckStatsProbe(
                QuickCraftWorkbenchShulkerCraft.AckBatchKind.OUTPUT,
                0, false, true, false, 2_999L, 3_000L)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRequestAckStatsProbe(
                QuickCraftWorkbenchShulkerCraft.AckBatchKind.REFILL_OUTPUT,
                3, true, true, false, 3_000L, 3_000L)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRequestAckStatsProbe(
                QuickCraftWorkbenchShulkerCraft.AckBatchKind.REFILL_OUTPUT,
                3, false, false, false, 3_000L, 3_000L)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldRequestAckStatsProbe(
                QuickCraftWorkbenchShulkerCraft.AckBatchKind.PREPARATION,
                0, false, true, false, 3_000L, 3_000L)).isFalse();
    }

    @Test
    @DisplayName("静默探针回包必须属于当前唯一在途批次")
    void ackStatsProbe_rejectsStaleOrDifferentBatchResponses() {
        assertThat(QuickCraftWorkbenchShulkerCraft.isCurrentAckStatsProbe(
                true, 65L, 65L, true, true)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.isCurrentAckStatsProbe(
                true, 65L, 66L, true, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.isCurrentAckStatsProbe(
                true, 65L, 65L, false, true)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.isCurrentAckStatsProbe(
                true, 65L, 65L, true, false)).isFalse();
    }

    @Test
    @DisplayName("统计屏障只确认当前批次的安全终态")
    void ackStatsProbe_requiresSafeTerminalState() {
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmAckStatsProbe(
                true, true)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmAckStatsProbe(
                true, false)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.shouldConfirmAckStatsProbe(
                false, true)).isFalse();
    }

    @Test
    @DisplayName("静默探针独立等待十秒后才判定连接失效")
    void ackStatsProbe_usesIndependentResponseTimeout() {
        assertThat(QuickCraftWorkbenchShulkerCraft.hasAckStatsProbeTimedOut(
                9_999_999_999L)).isFalse();
        assertThat(QuickCraftWorkbenchShulkerCraft.hasAckStatsProbeTimedOut(
                10_000_000_000L)).isTrue();
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
    @DisplayName("1.21.3 客户端只要产物槽已显示配方即可建立快照")
    void recipeStart_acceptsVisibleOutputWithoutReusableSnapshot() {
        assertThat(QuickCraftWorkbenchShulkerCraft.canStartWithRecipeState(true, false)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.canStartWithRecipeState(false, true)).isTrue();
        assertThat(QuickCraftWorkbenchShulkerCraft.canStartWithRecipeState(false, false)).isFalse();
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
    @DisplayName("不可堆叠产物按每个空槽一个计算容量")
    void outputCapacity_nonStackableOutputUsesOneItemPerSlot() {
        assertThat(QuickCraftWorkbenchShulkerOutput.calculateCapacity(1, 0, 0)).isEqualTo(27);
        assertThat(QuickCraftWorkbenchShulkerOutput.calculateCapacity(1, 26, 0)).isOne();
    }

    @Test
    @DisplayName("产物盒优先同类纯盒，其次空盒并排除杂盒和满盒")
    void outputBoxContentPriority_prefersMatchingBoxThenEmptyBox() {
        assertThat(QuickCraftWorkbenchShulkerOutput.outputBoxContentPriority(
                false, true, true)).isZero();
        assertThat(QuickCraftWorkbenchShulkerOutput.outputBoxContentPriority(
                true, true, true)).isOne();
        assertThat(QuickCraftWorkbenchShulkerOutput.outputBoxContentPriority(
                false, false, true)).isNegative();
        assertThat(QuickCraftWorkbenchShulkerOutput.outputBoxContentPriority(
                false, true, false)).isNegative();
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
