package com.yiyihehe.quickcraft.mixin;

import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Indigo 内部状态仅用于旧版离屏网格构建，不改动世界渲染流程。 */
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderInfo", remap = false)
public interface LitematicaPreviewIndigoBlockInfoAccessor {
    @Accessor("random") void quickcraft$setRandom(Random random);
    @Accessor("recomputeSeed") void quickcraft$setRecomputeSeed(boolean recomputeSeed);
    @Invoker("shouldCullSide") boolean quickcraft$shouldCullSide(Direction side);
}
