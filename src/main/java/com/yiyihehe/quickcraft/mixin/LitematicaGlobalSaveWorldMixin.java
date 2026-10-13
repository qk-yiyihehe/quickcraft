package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaSaveContext;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class LitematicaGlobalSaveWorldMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void quickcraft$readBaselineState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if ((Object) this instanceof WorldSchematic world && QuickLitematicaSaveContext.contains(world, pos)) {
            cir.setReturnValue(QuickLitematicaGlobalReplacements.baselineWorldState(pos));
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void quickcraft$readBaselineEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if ((Object) this instanceof WorldSchematic world && QuickLitematicaSaveContext.contains(world, pos)) {
            cir.setReturnValue(QuickLitematicaGlobalReplacements.baselineWorldEntity(pos, world));
        }
    }
}
