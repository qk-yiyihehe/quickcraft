package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 灵魂出窍增强时，潜行键应取消方块或实体的默认交互。 */
@Mixin(Player.class)
public abstract class QuickFreeCameraPlayerEntityMixin {
    @Inject(method = "isSecondaryUseActive", at = @At("HEAD"), cancellable = true)
    private void quickcraft$cancelInteractionFromFreeCamera(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof LocalPlayer
                && QuickFreeCameraInteractions.shouldCancelInteractionFromFreeCamera(Minecraft.getInstance())) {
            cir.setReturnValue(true);
        }
    }
}
