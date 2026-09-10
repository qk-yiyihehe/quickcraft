package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 灵魂出窍增强开启时，按潜行键让客户端按原版规则取消方块或实体的默认交互。
 * Tweakeroo 冻本体后 {@code isSneaking()} 不会跟随潜行键；若此处失效，箱子和可骑乘实体会执行错误操作。
 */
@Mixin(PlayerEntity.class)
public abstract class QuickFreeCameraPlayerEntityMixin {
    @Inject(method = "shouldCancelInteraction", at = @At("HEAD"), cancellable = true)
    private void quickcraft$cancelInteractionFromFreeCamera(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ClientPlayerEntity
                && QuickFreeCameraInteractions.shouldCancelInteractionFromFreeCamera(MinecraftClient.getInstance())) {
            cir.setReturnValue(true);
        }
    }
}
