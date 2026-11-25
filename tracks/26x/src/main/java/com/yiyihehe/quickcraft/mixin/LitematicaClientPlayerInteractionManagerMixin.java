package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
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
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void quickcraft$clearPendingContainerUses(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        quickcraft$clearPendingContainerUsesStatic();
    }

    @Inject(method = "interact", at = @At("HEAD"))
    private void quickcraft$clearPendingContainerUsesOnEntity(
            Player player,
            Entity entity,
            EntityHitResult hitResult,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        quickcraft$clearPendingContainerUsesStatic();
    }

    private static void quickcraft$clearPendingContainerUsesStatic() {
        QuickLitematicaContainerVerifier.clearPendingContainerUses();
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void quickcraft$rememberOpenedContainer(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        InteractionResult result = cir.getReturnValue();

        if (result != null && result.consumesAction()) {
            com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerFillState.rememberContainerUse(
                    Minecraft.getInstance(),
                    hitResult
            );
            com.yiyihehe.quickcraft.litematica.QuickLitematicaEnderChestMaterials.rememberContainerUse(
                    Minecraft.getInstance(),
                    hitResult
            );
        }

        if (result != null && result.consumesAction() && !QuickContainerCopy
                .shouldSuppressContainerVerifierRemember()) {
            QuickLitematicaContainerVerifier.rememberContainerUse(Minecraft.getInstance(), hitResult);
        }
    }
}
