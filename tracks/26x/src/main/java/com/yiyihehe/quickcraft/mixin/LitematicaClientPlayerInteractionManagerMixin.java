package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 记录玩家最近一次成功打开的容器位置。
 * 让容器校验能在真正打开容器后回填实时库存内容。
 */
@Mixin(MultiPlayerGameMode.class)
public class LitematicaClientPlayerInteractionManagerMixin {
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void quickcraft$rememberOpenedContainer(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        InteractionResult result = cir.getReturnValue();

        if (result != null && result.consumesAction()
                && !QuickContainerCopy.shouldSuppressContainerVerifierRemember()) {
            QuickLitematicaContainerVerifier.rememberContainerUse(Minecraft.getInstance(), hitResult);
        }
    }
}
