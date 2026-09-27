package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.1 的准星更新已移到 Minecraft.pick；这里在其返回后恢复为灵魂相机射线。
 * 若目标方法失效，普通交互仍安全，但准星会重新落回玩家本体视角。
 */
@Mixin(Minecraft.class)
public abstract class QuickFreeCameraGameRendererMixin {
    @Inject(method = "pick", at = @At("TAIL"))
    private void quickcraft$useFreeCameraCrosshair(float tickDelta, CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;
        if (!QuickFreeCameraInteractions.shouldOverrideCrosshair(client)) {
            return;
        }

        Entity camera = client.getCameraEntity();
        HitResult target = client.player.raycastHitResult(tickDelta, camera);
        target = QuickFreeCameraInteractions.filterCrosshairTarget(client, camera, target);
        client.hitResult = target;
        client.crosshairPickEntity = target instanceof EntityHitResult entityHitResult
                ? entityHitResult.getEntity()
                : null;
    }
}
