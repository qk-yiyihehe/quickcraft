package com.yiyihehe.quickcraft;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;

public final class QuickCraftTestBlocks {
    private QuickCraftTestBlocks() { }

    public static AbstractBlock.Settings settings(Block block) {
        return AbstractBlock.Settings.copy(block)
                .registryKey(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.BLOCK,
                        net.minecraft.util.Identifier.of("quickcraft_test", net.minecraft.registry.Registries.BLOCK.getId(block).getPath())));
    }
}
