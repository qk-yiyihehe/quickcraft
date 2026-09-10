package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 将灵魂视角放置后的服务端朝向恢复延迟到后续客户端 tick。
 * 若与放置包同帧恢复，服务端可能先按本体朝向落块，表现为方向回弹。
 */
@Mixin(MinecraftClient.class)
public abstract class QuickFreeCameraMinecraftClientMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void quickcraft$flushFreeCameraFacingRestore(CallbackInfo ci) {
        QuickFreeCameraInteractions.tick(MinecraftClient.getInstance());
    }
}
