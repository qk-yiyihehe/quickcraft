package com.yiyihehe.quickcraft;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;

public final class QuickCraftTestBlocks {
    private QuickCraftTestBlocks() { }

    public static AbstractBlock.Settings settings(Block block) {
        return AbstractBlock.Settings.copy(block);
    }
}
