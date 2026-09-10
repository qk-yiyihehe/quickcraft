package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweakeroo 0.21.x 会在调用原版方块交互前读取真实玩家的水平朝向。
 * 这里提前套用灵魂相机朝向；失效时侦测器等六向方块的水平朝向会继续跟随本体。
 */
@Mixin(targets = "fi.dy.masa.tweakeroo.tweaks.PlacementTweaks", remap = false)
public abstract class TweakerooPlacementTweaksFreeCameraMixin {
    @Inject(method = "onProcessRightClickBlock", at = @At("HEAD"), remap = false)
    private static void quickcraft$beginBlockUseFromFreeCamera(
            ClientPlayerInteractionManager controller,
            ClientPlayerEntity player,
            ClientWorld world,
            Hand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<?> cir
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        QuickFreeCameraInteractions.beginBlockUseFromFreeCamera(client, hand, hitResult);
    }

    @Inject(method = "onProcessRightClickBlock", at = @At("RETURN"), remap = false)
    private static void quickcraft$endBlockUseFromFreeCamera(CallbackInfoReturnable<?> cir) {
        QuickFreeCameraInteractions.endBlockUseFromFreeCamera(MinecraftClient.getInstance());
    }
}
