package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在灵魂相机交互产生客户端预测前复用 26.1 服务端的本体距离判定。
 * 如果此处失效，超距放置或破坏会先在客户端生效，再被服务端回滚成幽灵方块。
 */
@Mixin(MultiPlayerGameMode.class)
public class QuickFreeCameraInteractionManagerMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeBlockUse(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (QuickFreeCameraInteractions.isBlockOutsideServerInteractionRange(
                Minecraft.getInstance(),
                hitResult.getBlockPos()
        )) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeBlockAttack(
            BlockPos position,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (QuickFreeCameraInteractions.isBlockOutsideServerInteractionRange(
                Minecraft.getInstance(),
                position
        )) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeBlockBreaking(
            BlockPos position,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (QuickFreeCameraInteractions.isBlockOutsideServerInteractionRange(
                Minecraft.getInstance(),
                position
        )) {
            ((MultiPlayerGameMode) (Object) this).stopDestroyBlock();
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeEntityUseAtLocation(
            Player player,
            Entity entity,
            EntityHitResult hitResult,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (QuickFreeCameraInteractions.isEntityOutsideServerInteractionRange(
                Minecraft.getInstance(),
                entity
        )) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeEntityAttack(
            Player player,
            Entity entity,
            CallbackInfo ci
    ) {
        if (QuickFreeCameraInteractions.isEntityOutsideServerInteractionRange(
                Minecraft.getInstance(),
                entity
        )) {
            ci.cancel();
        }
    }
}
