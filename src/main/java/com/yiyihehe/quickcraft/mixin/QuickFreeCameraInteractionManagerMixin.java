package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在灵魂相机交互产生客户端预测前复用 1.21 服务端的本体距离判定。
 * 如果此处失效，超距放置或破坏会先在客户端生效，再被服务端回滚成幽灵方块。
 * 潜行放置和朝向同步必须包在 {@code interactBlock} 外，不能包 {@code interactBlockInternal}：
 * 内部方法返回后才发送 {@code PlayerInteractBlockC2SPacket}，若先还原 SHIFT/朝向，服务端仍会开箱或按本体朝向放置。
 */
@Mixin(ClientPlayerInteractionManager.class)
public class QuickFreeCameraInteractionManagerMixin {
    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeBlockUse(
            ClientPlayerEntity player,
            Hand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        QuickFreeCameraInteractions.beginBlockUseFromFreeCamera(client);
        if (QuickFreeCameraInteractions.isBlockOutsideServerInteractionRange(client, hitResult.getBlockPos())) {
            cir.setReturnValue(ActionResult.FAIL);
        }
    }

    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void quickcraft$endBlockUseFromFreeCamera(
            ClientPlayerEntity player,
            Hand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        QuickFreeCameraInteractions.endBlockUseFromFreeCamera(MinecraftClient.getInstance());
    }

    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeBlockAttack(
            BlockPos position,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (QuickFreeCameraInteractions.isBlockOutsideServerInteractionRange(
                MinecraftClient.getInstance(),
                position
        )) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeBlockBreaking(
            BlockPos position,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (QuickFreeCameraInteractions.isBlockOutsideServerInteractionRange(
                MinecraftClient.getInstance(),
                position
        )) {
            ((ClientPlayerInteractionManager) (Object) this).cancelBlockBreaking();
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "interactEntity", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeEntityUse(
            PlayerEntity player,
            Entity entity,
            Hand hand,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        if (QuickFreeCameraInteractions.isEntityOutsideServerInteractionRange(
                MinecraftClient.getInstance(),
                entity
        )) {
            cir.setReturnValue(ActionResult.FAIL);
        }
    }

    @Inject(method = "interactEntityAtLocation", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeEntityUseAtLocation(
            PlayerEntity player,
            Entity entity,
            EntityHitResult hitResult,
            Hand hand,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        if (QuickFreeCameraInteractions.isEntityOutsideServerInteractionRange(
                MinecraftClient.getInstance(),
                entity
        )) {
            cir.setReturnValue(ActionResult.FAIL);
        }
    }

    @Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeEntityAttack(
            PlayerEntity player,
            Entity entity,
            CallbackInfo ci
    ) {
        if (QuickFreeCameraInteractions.isEntityOutsideServerInteractionRange(
                MinecraftClient.getInstance(),
                entity
        )) {
            ci.cancel();
        }
    }
}
