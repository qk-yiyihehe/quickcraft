package com.yiyihehe.quickcraft.litematica;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

/**
 * 1.21.4 起新增苍白橡木，补齐原版斧头可去皮方块。
 */
final class QuickLitematicaEasyPlaceBlocks {
    private QuickLitematicaEasyPlaceBlocks() {
    }

    static boolean isAdditionalStrippable(Block block) {
        return block == Blocks.PALE_OAK_LOG || block == Blocks.PALE_OAK_WOOD;
    }
}
