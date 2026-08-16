package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaShulkerMaterialRestock;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在 Litematica 0.19.60 的放置限制判定前启动潜影盒补料。
 * 该位置早于主手为空时的限制返回；若目标调用顺序变更，症状仅为缺料时不再自动开盒。
 */
@Mixin(value = WorldUtils.class, remap = false)
public final class LitematicaWorldUtilsShulkerRestockMixin {
    @Inject(
            method = "handleEasyPlace",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/WorldUtils;doEasyPlaceAction(Lnet/minecraft/client/MinecraftClient;)Lnet/minecraft/util/ActionResult;"
            ),
            cancellable = true,
            remap = false
    )
    private static void quickcraft$restockBeforeClickEasyPlace(MinecraftClient client, CallbackInfoReturnable<Boolean> cir) {
        if (QuickLitematicaShulkerMaterialRestock.requestMaterialForEasyPlaceTarget(client)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$restockBeforeHeldEasyPlace(
            MinecraftClient client,
            CallbackInfoReturnable<ActionResult> cir
    ) {
        if (QuickLitematicaShulkerMaterialRestock.requestMaterialForEasyPlaceTarget(client)) {
            cir.setReturnValue(ActionResult.FAIL);
        }
    }
}
