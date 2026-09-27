package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import fi.dy.masa.litematica.materials.MaterialListHudRenderer;
import net.minecraft.client.MinecraftClient;
//#if MC>=12111
//$$ import fi.dy.masa.malilib.render.GuiContext;
//#elseif MC>=12108
//$$ import net.minecraft.client.gui.DrawContext;
//#endif
import net.minecraft.client.gui.screen.ingame.HandledScreen;
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
    //#if MC>=12111
    //$$ private static void quickcraft$skipContainerMaterialSlotHighlights(
    //$$         GuiContext drawContext, HandledScreen<?> gui, MinecraftClient mc, CallbackInfo ci) {
    //#elseif MC>=12108
    //$$ private static void quickcraft$skipContainerMaterialSlotHighlights(
    //$$         DrawContext drawContext, HandledScreen<?> gui, MinecraftClient mc, CallbackInfo ci) {
    //#else
    private static void quickcraft$skipContainerMaterialSlotHighlights(HandledScreen<?> gui, MinecraftClient mc, CallbackInfo ci) {
    //#endif
        if (QuickLitematicaContainerVerifier.shouldSuppressInventorySlotHighlights()) {
            ci.cancel();
        }
    }
}
