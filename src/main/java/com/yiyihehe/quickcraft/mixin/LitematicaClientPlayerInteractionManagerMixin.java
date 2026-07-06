package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 记录玩家最近一次成功打开的容器位置。
 * 让容器校验能在真正打开容器后回填实时库存内容。
 */
@Mixin(ClientPlayerInteractionManager.class)
public class LitematicaClientPlayerInteractionManagerMixin {
    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void quickcraft$rememberOpenedContainer(
            ClientPlayerEntity player,
            Hand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        ActionResult result = cir.getReturnValue();

        if (result != null && result.isAccepted()
                && !QuickContainerCopy.shouldSuppressContainerVerifierRemember()) {
            QuickLitematicaContainerVerifier.rememberContainerUse(MinecraftClient.getInstance(), hitResult);
        }
    }
}
