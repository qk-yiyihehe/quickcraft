package com.yiyihehe.quickcraft.mixin;

import com.google.common.collect.ImmutableList;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerMaterials;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import java.util.List;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier;
import fi.dy.masa.litematica.materials.MaterialListHudRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
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
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/materials/MaterialListBase;getMaterialsAll()Lcom/google/common/collect/ImmutableList;"))
    private ImmutableList<MaterialListEntry> quickcraft$targetHudMaterials(MaterialListBase list) {
        return list instanceof QuickLitematicaContainerMaterials.ContainerMaterialRequestSource source
                ? ImmutableList.copyOf(source.quickcraft$getHudMaterialsAll(true)) : ImmutableList.copyOf(com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionMaterials.hud(list, false));
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/materials/MaterialListBase;getMaterialsMissingOnly(Z)Ljava/util/List;"))
    private List<MaterialListEntry> quickcraft$targetHudMissing(MaterialListBase list, boolean refresh) {
        return list instanceof QuickLitematicaContainerMaterials.ContainerMaterialRequestSource source
                ? source.quickcraft$getHudMaterialsMissing() : com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionMaterials.hud(list, true);
    }

    @Inject(method = "renderLookedAtBlockInInventory", at = @At("HEAD"), cancellable = true)
    private static void quickcraft$skipContainerMaterialSlotHighlights(
            DrawContext drawContext, HandledScreen<?> gui, MinecraftClient mc, CallbackInfo ci) {
        if (QuickLitematicaContainerVerifier.shouldSuppressInventorySlotHighlights()) {
            ci.cancel();
        }
    }
}
