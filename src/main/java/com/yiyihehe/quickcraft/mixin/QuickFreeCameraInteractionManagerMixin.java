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
 * 在灵魂相机交互产生客户端预测前复用服务端的本体距离判定。
 * 如果此处失效，超距放置或破坏会先在客户端生效，再被服务端回滚成幽灵方块。
 * 潜行放置和朝向同步必须包在 {@code useItemOn} 外：内部逻辑返回后才发送使用包，若先还原 SHIFT/朝向，服务端仍会开箱或按本体朝向放置。
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
        Minecraft client = Minecraft.getInstance();
        QuickFreeCameraInteractions.beginBlockUseFromFreeCamera(client);
        if (QuickFreeCameraInteractions.isBlockOutsideServerInteractionRange(client, hitResult.getBlockPos())) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void quickcraft$endBlockUseFromFreeCamera(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        QuickFreeCameraInteractions.endBlockUseFromFreeCamera(Minecraft.getInstance());
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
