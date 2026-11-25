package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import fi.dy.masa.litematica.data.EntitiesDataStorage;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntitiesDataStorage.class, remap = false)
public abstract class LitematicaEntityDataReceiptMixin {
    @Inject(method = "handleBlockEntityData", at = @At("RETURN"), remap = false)
    private void quickcraft$rememberReceivedData(
            BlockPos pos,
            NbtCompound data,
            Identifier type,
            CallbackInfoReturnable<BlockEntity> cir
    ) {
        if (cir.getReturnValue() != null) {
            QuickLitematicaContainerVerifier.rememberReceivedBlockEntityData(
                    MinecraftClient.getInstance().world,
                    pos,
                    data
            );
        }
    }
}
