package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaEasyPlaceContainers;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Litematica 轻松放置对真实容器右键的让行层。
 *
 * <p>容器方块既可能是投影放置目标，也可能是玩家要打开的真实容器。
 * 当 QuickCraft 判断应该走原版开箱时，提前结束 {@code handleEasyPlace} 和
 * {@code easyPlaceOnUseTick}，避免持续轻松放置吞掉这次右键。</p>
 */
@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaWorldUtilsEasyPlaceMixin {
    @Inject(method = "handleEasyPlace", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$letContainerUsePassThrough(MinecraftClient mc, CallbackInfoReturnable<Boolean> cir) {
        if (QuickLitematicaEasyPlaceContainers.shouldAllowVanillaContainerUse(mc)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "easyPlaceOnUseTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$skipHoldEasyPlaceOnContainers(MinecraftClient mc, CallbackInfo ci) {
        if (QuickLitematicaEasyPlaceContainers.shouldAllowVanillaContainerUse(mc)) {
            ci.cancel();
        }
    }
}
