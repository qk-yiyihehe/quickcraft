package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BrewingStandBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.DropperBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * 轻松放置遇到真实容器时的放行判断。
 */
public final class QuickLitematicaEasyPlaceContainers {
    private QuickLitematicaEasyPlaceContainers() {
    }

    public static boolean shouldAllowVanillaContainerUse(MinecraftClient client) {
        if (!QuickCraftConfigs.isEasyPlaceOpenContainersAllowed()
                || client == null
                || client.player == null
                || client.world == null
                || client.currentScreen != null) {
            return false;
        }

        HitResult hitResult = client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || blockHitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        Block block = client.world.getBlockState(blockHitResult.getBlockPos()).getBlock();
        return block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof EnderChestBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof DispenserBlock
                || block instanceof DropperBlock
                || block instanceof HopperBlock
                || block instanceof CrafterBlock
                || block instanceof AbstractFurnaceBlock
                || block instanceof BrewingStandBlock;
    }
}
