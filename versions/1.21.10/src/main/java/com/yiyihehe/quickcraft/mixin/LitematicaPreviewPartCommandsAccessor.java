package com.yiyihehe.quickcraft.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.ModelPartCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ModelPartCommandRenderer.Commands.class)
public interface LitematicaPreviewPartCommandsAccessor {
    @Accessor("modelPartCommands")
    Map<RenderLayer, List<OrderedRenderCommandQueueImpl.ModelPartCommand>> quickcraft$getModelPartCommands();
}
