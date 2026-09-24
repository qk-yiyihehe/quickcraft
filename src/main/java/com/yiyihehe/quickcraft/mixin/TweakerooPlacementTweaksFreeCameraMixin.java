package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweakeroo 会在调用原版方块交互前读取真实玩家的水平朝向。
 * 这里提前套用灵魂相机朝向；失效时侦测器等六向方块的水平朝向会继续跟随本体。
 */
@Mixin(targets = "fi.dy.masa.tweakeroo.tweaks.PlacementTweaks", remap = false)
public abstract class TweakerooPlacementTweaksFreeCameraMixin {
    @Inject(method = "onProcessRightClickBlock", at = @At("HEAD"), remap = false)
    private static void quickcraft$beginBlockUseFromFreeCamera(CallbackInfoReturnable<?> cir) {
        Minecraft client = Minecraft.getInstance();
        QuickFreeCameraInteractions.beginBlockUseFromFreeCamera(client);
    }

    @Inject(method = "onProcessRightClickBlock", at = @At("RETURN"), remap = false)
    private static void quickcraft$endBlockUseFromFreeCamera(CallbackInfoReturnable<?> cir) {
        QuickFreeCameraInteractions.endBlockUseFromFreeCamera(Minecraft.getInstance());
    }
}
