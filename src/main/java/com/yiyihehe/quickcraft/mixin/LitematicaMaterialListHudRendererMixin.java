package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import fi.dy.masa.litematica.materials.MaterialListHudRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 容器校验覆盖层对 Litematica 材料 HUD 高亮的让行。
 *
 * <p>材料 HUD 的库存槽位提示和 QuickCraft 容器校验都会在同一个
 * {@link HandledScreen} 上画高亮。校验界面接管时取消原版材料提示，
 * 避免两套颜色叠加后看不清缺失/错填状态。</p>
 */
@Mixin(value = MaterialListHudRenderer.class, remap = false)
public class LitematicaMaterialListHudRendererMixin {
    @Inject(method = "renderLookedAtBlockInInventory", at = @At("HEAD"), cancellable = true)
    private static void quickcraft$skipContainerMaterialSlotHighlights(HandledScreen<?> gui, MinecraftClient mc, CallbackInfo ci) {
        if (QuickLitematicaContainerVerifier.shouldSuppressInventorySlotHighlights()) {
            ci.cancel();
        }
    }
}
