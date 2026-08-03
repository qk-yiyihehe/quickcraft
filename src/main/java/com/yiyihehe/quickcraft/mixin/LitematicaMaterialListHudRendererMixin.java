package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import fi.dy.masa.litematica.materials.MaterialListHudRenderer;
import net.minecraft.client.Minecraft;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在容器校验接管槽位高亮时，临时关闭 Litematica 原版材料 HUD 的槽位提示。
 * 避免两套蓝色高亮叠在一起。
 */
@Mixin(value = MaterialListHudRenderer.class, remap = false)
public class LitematicaMaterialListHudRendererMixin {
    @Inject(method = "renderLookedAtBlockInInventory", at = @At("HEAD"), cancellable = true)
    private static void quickcraft$skipContainerMaterialSlotHighlights(GuiContext drawContext, AbstractContainerScreen<?> gui, Minecraft mc, CallbackInfo ci) {
        if (QuickLitematicaContainerVerifier.shouldSuppressInventorySlotHighlights()) {
            ci.cancel();
        }
    }
}
