package com.yiyihehe.quickcraft.litematica;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

/** 让 26.3 的 PIP 渲染器在原版创建的 RenderPass 内插入预览静态网格。 */
public interface QuickLitematicaPictureInPictureRenderPass {
    void quickcraft$renderFeatures(RenderPass renderPass, FeatureRenderDispatcher.PreparedFrame frame);
}
