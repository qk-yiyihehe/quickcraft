package com.yiyihehe.quickcraft.mixin;

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
 * 容器校验的真实开箱位置记录入口。
 *
 * <p>只有 {@link ClientPlayerInteractionManager#interactBlock(ClientPlayerEntity, Hand, BlockHitResult)}
 * 返回 accepted 后，才能确认这次右键真的打开或使用了目标方块。QuickCraft 用这个位置把随后打开的容器
 * 库存回填到验证器；后台填充期间会临时抑制记录，避免把自动操作误认为玩家检查。</p>
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
