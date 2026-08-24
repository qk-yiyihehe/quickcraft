package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweakeroo 允许玩家输入时会把准星来源改回玩家；这里在其后恢复为灵魂相机射线。
 * 若目标方法失效，普通交互仍安全，但准星会重新落回玩家本体视角。
 */
@Mixin(GameRenderer.class)
public abstract class QuickFreeCameraGameRendererMixin {
    @Shadow
    @Final
    private MinecraftClient client;

    @Inject(method = "updateCrosshairTarget", at = @At("TAIL"))
    private void quickcraft$useFreeCameraCrosshair(float tickDelta, CallbackInfo ci) {
        if (!QuickFreeCameraInteractions.shouldOverrideCrosshair(this.client)) {
            return;
        }

        Entity camera = this.client.getCameraEntity();
        HitResult target = this.client.player.getCrosshairTarget(tickDelta, camera);
        target = QuickFreeCameraInteractions.filterCrosshairTarget(this.client, camera, target);
        this.client.crosshairTarget = target;
        this.client.targetedEntity = target instanceof EntityHitResult entityHitResult
                ? entityHitResult.getEntity()
                : null;
    }
}
