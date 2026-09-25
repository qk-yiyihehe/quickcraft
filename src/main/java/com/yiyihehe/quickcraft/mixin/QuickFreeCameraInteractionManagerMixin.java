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
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在灵魂相机交互产生客户端预测前复用服务端的本体距离判定。
 * 潜行和方向同步必须包在 useItemOn 外：它返回前才发送使用包。
 * 水桶通过 useItem 发送不含目标位置的包，需在 HEAD 对准本体可命中的相机目标，否则阻止误倒水。
 */
@Mixin(MultiPlayerGameMode.class)
public class QuickFreeCameraInteractionManagerMixin {
    @ModifyVariable(method = "useItemOn", at = @At("HEAD"), argsOnly = true)
    private BlockHitResult quickcraft$encodeFreeCameraPlacementState(BlockHitResult hitResult) {
        return QuickFreeCameraInteractions.encodeFreeCameraPlacementState(Minecraft.getInstance(), hitResult);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeBlockUse(
            LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir) {
        Minecraft client = Minecraft.getInstance();
        if (QuickFreeCameraInteractions.isBlockOutsideServerInteractionRange(client, hitResult.getBlockPos())) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        QuickFreeCameraInteractions.beginBlockUseFromFreeCamera(client, hand, hitResult);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void quickcraft$endBlockUseFromFreeCamera(
            LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir) {
        QuickFreeCameraInteractions.endBlockUseFromFreeCamera(Minecraft.getInstance());
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void quickcraft$beginItemUseFromFreeCamera(
            Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (!QuickFreeCameraInteractions.beginItemUseFromFreeCamera(Minecraft.getInstance(), hand)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void quickcraft$endItemUseFromFreeCamera(
            Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        QuickFreeCameraInteractions.endBlockUseFromFreeCamera(Minecraft.getInstance());
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeBlockAttack(
            BlockPos position, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (QuickFreeCameraInteractions.isBlockOutsideServerInteractionRange(Minecraft.getInstance(), position)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeBlockBreaking(
            BlockPos position, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (QuickFreeCameraInteractions.isBlockOutsideServerInteractionRange(Minecraft.getInstance(), position)) {
            ((MultiPlayerGameMode) (Object) this).stopDestroyBlock();
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeEntityUse(
            Player player, Entity entity, EntityHitResult hitResult, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (QuickFreeCameraInteractions.isEntityOutsideServerInteractionRange(Minecraft.getInstance(), entity)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @ModifyArg(method = "interact", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;<init>(ILnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/Vec3;Z)V"), index = 3)
    private boolean quickcraft$useFreeCameraSneakingForEntityUse(boolean originalSneaking) {
        return QuickFreeCameraInteractions.resolveEntitySneaking(Minecraft.getInstance(), originalSneaking);
    }

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeEntityAttack(Player player, Entity entity, CallbackInfo ci) {
        if (QuickFreeCameraInteractions.isEntityOutsideServerInteractionRange(Minecraft.getInstance(), entity)) {
            ci.cancel();
        }
    }
}
