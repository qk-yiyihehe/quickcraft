package com.yiyihehe.quickcraft.litematica;

import net.minecraft.block.Block;

/**
 * 按实际游戏版本提供轻松放置工具交互的额外可去皮方块。
 */
final class QuickLitematicaEasyPlaceBlocks {
    private QuickLitematicaEasyPlaceBlocks() {}

    static boolean isAdditionalStrippable(Block block) {
        return false;
    }
}
