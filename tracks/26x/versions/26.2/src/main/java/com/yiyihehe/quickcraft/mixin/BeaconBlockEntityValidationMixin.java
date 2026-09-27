package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 允许未发布的 26.2 单人存档继续保存生命恢复 II 信标组合。
 * 多人连接仍遵循原版校验，避免非法效果包导致服务端主动断开连接。
 */
@Mixin(BeaconBlockEntity.class)
public abstract class BeaconBlockEntityValidationMixin {
    @Inject(method = "validateEffects", at = @At("HEAD"), cancellable = true)
    private static void quickcraft$allowSingleplayerRegenerationTwo(
            Holder<MobEffect> primary,
            Holder<MobEffect> secondary,
            int levels,
            CallbackInfoReturnable<Boolean> cir
    ) {
        Minecraft client = Minecraft.getInstance();
        if (client.hasSingleplayerServer()
                && !client.isMultiplayerServer()
                && levels >= 4
                && MobEffects.REGENERATION.equals(primary)
                && MobEffects.REGENERATION.equals(secondary)) {
            cir.setReturnValue(true);
        }
    }
}
