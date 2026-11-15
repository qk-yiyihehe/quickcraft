package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import fi.dy.masa.litematica.data.EntityDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityDataManager.class, remap = false)
public abstract class LitematicaEntityDataReceiptMixin {
    @Inject(method = "handleBlockEntityData", at = @At("RETURN"), remap = false)
    private void quickcraft$rememberReceivedData(BlockPos pos, CompoundData data,
                                                CallbackInfoReturnable<BlockEntity> cir) {
        if (cir.getReturnValue() != null) {
            QuickLitematicaContainerVerifier.rememberReceivedBlockEntityData(
                    Minecraft.getInstance().level, pos, data);
        }
    }
}
