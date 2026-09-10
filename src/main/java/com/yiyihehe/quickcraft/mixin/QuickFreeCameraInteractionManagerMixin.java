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
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在灵魂相机交互产生客户端预测前复用 1.21 服务端的本体距离判定。
 * 如果此处失效，超距放置或破坏会先在客户端生效，再被服务端回滚成幽灵方块。
 * 潜行放置和朝向同步必须包在 {@code interactBlock} 外，不能包 {@code interactBlockInternal}：
 * 内部方法返回后才发送 {@code PlayerInteractBlockC2SPacket}，若先还原 SHIFT/朝向，服务端仍会开箱或按本体朝向放置。
 * 实体交互包自身携带潜行标志，因此需要在包构造处替换该参数，并在发送后恢复服务端潜行状态。
 */
@Mixin(ClientPlayerInteractionManager.class)
public class QuickFreeCameraInteractionManagerMixin {
    @ModifyVariable(method = "interactBlock", at = @At("HEAD"), argsOnly = true)
    private BlockHitResult quickcraft$encodeFreeCameraObserverDirection(BlockHitResult hitResult) {
        return QuickFreeCameraInteractions.encodeObserverPlacementDirection(
                MinecraftClient.getInstance(),
                hitResult
        );
    }

    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void quickcraft$rejectOutOfRangeBlockUse(
            ClientPlayerEntity player,
            Hand hand,
            BlockHitResult hitResult,
        CallbackInfoReturnable<ActionResult> cir
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (QuickFreeCameraInteractions.isBlockOutsideServerInteractionRange(client, hitResult.getBlockPos())) {
            cir.setReturnValue(ActionResult.FAIL);
            return;
        }
        QuickFreeCameraInteractions.beginBlockUseFromFreeCamera(client);
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

    @Inject(method = "interactItem", at = @At("HEAD"))
    private void quickcraft$beginItemUseFromFreeCamera(
            PlayerEntity player,
            Hand hand,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        QuickFreeCameraInteractions.beginItemUseFromFreeCamera(MinecraftClient.getInstance());
    }

    @Inject(method = "interactItem", at = @At("RETURN"))
    private void quickcraft$endItemUseFromFreeCamera(
            PlayerEntity player,
            Hand hand,
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

    @ModifyArg(
            method = "interactEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/packet/c2s/play/PlayerInteractEntityC2SPacket;interact(Lnet/minecraft/entity/Entity;ZLnet/minecraft/util/Hand;)Lnet/minecraft/network/packet/c2s/play/PlayerInteractEntityC2SPacket;"
            ),
            index = 1
    )
    private boolean quickcraft$useFreeCameraSneakingForEntityUse(boolean originalSneaking) {
        return QuickFreeCameraInteractions.resolveEntitySneaking(
                MinecraftClient.getInstance(),
                originalSneaking
        );
    }

    @Inject(method = "interactEntity", at = @At("RETURN"))
    private void quickcraft$endEntityUseFromFreeCamera(
            PlayerEntity player,
            Entity entity,
            Hand hand,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        QuickFreeCameraInteractions.endEntityUseFromFreeCamera(MinecraftClient.getInstance());
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

    @ModifyArg(
            method = "interactEntityAtLocation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/packet/c2s/play/PlayerInteractEntityC2SPacket;interactAt(Lnet/minecraft/entity/Entity;ZLnet/minecraft/util/Hand;Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/network/packet/c2s/play/PlayerInteractEntityC2SPacket;"
            ),
            index = 1
    )
    private boolean quickcraft$useFreeCameraSneakingForEntityUseAtLocation(boolean originalSneaking) {
        return QuickFreeCameraInteractions.resolveEntitySneaking(
                MinecraftClient.getInstance(),
                originalSneaking
        );
    }

    @Inject(method = "interactEntityAtLocation", at = @At("RETURN"))
    private void quickcraft$endEntityUseAtLocationFromFreeCamera(
            PlayerEntity player,
            Entity entity,
            EntityHitResult hitResult,
            Hand hand,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        QuickFreeCameraInteractions.endEntityUseFromFreeCamera(MinecraftClient.getInstance());
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

    @ModifyArg(
            method = "attackEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/packet/c2s/play/PlayerInteractEntityC2SPacket;attack(Lnet/minecraft/entity/Entity;Z)Lnet/minecraft/network/packet/c2s/play/PlayerInteractEntityC2SPacket;"
            ),
            index = 1
    )
    private boolean quickcraft$useFreeCameraSneakingForEntityAttack(boolean originalSneaking) {
        return QuickFreeCameraInteractions.resolveEntitySneaking(
                MinecraftClient.getInstance(),
                originalSneaking
        );
    }

    @Inject(method = "attackEntity", at = @At("RETURN"))
    private void quickcraft$endEntityAttackFromFreeCamera(
            PlayerEntity player,
            Entity entity,
            CallbackInfo ci
    ) {
        QuickFreeCameraInteractions.endEntityUseFromFreeCamera(MinecraftClient.getInstance());
    }
}
