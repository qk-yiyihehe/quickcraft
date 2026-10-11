package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.mixin.LitematicaPreviewItemFeatureInvoker;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;

final class QuickLitematicaPreviewItemBuildAccess {
    private QuickLitematicaPreviewItemBuildAccess() {}
    static int passes() { return 2; }
    static void prepare(ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit, int pass) {
        ((LitematicaPreviewItemFeatureInvoker) (Object) renderer).quickcraft$prepareSubmit(submit, pass != 0);
    }
}
