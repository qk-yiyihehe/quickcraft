package com.yiyihehe.quickcraft.crafting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickCraftStonecutterTest {
    @Test
    void lateReplyMatchesOnlyItsOwnSelection() {
        assertFalse(QuickCraftStonecutter.isExpectedRecipeResult(20, 19, true, 1, 1));
        assertFalse(QuickCraftStonecutter.isExpectedRecipeResult(20, 21, true, 1, 1));
        assertTrue(QuickCraftStonecutter.isExpectedRecipeResult(20, 20, true, 1, 1));
    }

    @Test
    void correctionCannotBeMistakenForPredictedSlabs() {
        assertFalse(QuickCraftStonecutter.isExpectedRecipeResult(20, 20, false, 2, 2));
        assertFalse(QuickCraftStonecutter.isExpectedRecipeResult(20, 20, true, 2, 1));
        assertFalse(QuickCraftStonecutter.isExpectedRecipeResult(20, 20, true, 2, 0));
        assertTrue(QuickCraftStonecutter.isExpectedRecipeResult(20, 20, true, 2, 2));
    }

    @Test
    void revisionWrapMatchesTheQueuedSelection() {
        assertTrue(QuickCraftStonecutter.isExpectedRecipeResult(0, 0, true, 1, 1));
        assertFalse(QuickCraftStonecutter.isExpectedRecipeResult(0, 32767, true, 1, 1));
    }
}
