package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.mixin.LitematicaPreviewIndigoBlockInfoAccessor;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AbstractBlockRenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderInfo;

import net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.AoCalculator;
import net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.AoLuminanceFix;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashReportSection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.function.Function;

/**
 * 1.21.4 离屏世界 mesh 的 Indigo 方块模型上下文。
 * Indigo 未公开按 RenderLayer 分流且保留 AO 的离屏入口；内部依赖只留在此适配器，字段通过 Mixin 访问。
 */
@SuppressWarnings("UnstableApiUsage")
public final class QuickLitematicaPreviewIndigoContext extends AbstractBlockRenderContext {
    private BlockRenderView blockView;
    private final LitematicaPreviewIndigoBlockInfoAccessor blockInfoAccess;
    private final Function<RenderLayer, VertexConsumer> bufferFunc;

    public QuickLitematicaPreviewIndigoContext(BlockRenderView blockView, Function<RenderLayer, VertexConsumer> bufferFunc) {
        if (!(this.blockInfo instanceof LitematicaPreviewIndigoBlockInfoAccessor access)) {
            throw new IllegalStateException("QuickCraft Indigo preview accessor was not applied");
        }
        this.blockInfoAccess = access;
        this.blockView = blockView;
        this.bufferFunc = bufferFunc;
        this.overlay = OverlayTexture.DEFAULT_UV;

        this.blockInfo.prepareForWorld(blockView, true);
        this.blockInfoAccess.quickcraft$setRandom(Random.create());
    }

    public void tessellateBlock(BlockRenderView blockView, BlockState blockState, BlockPos blockPos, BakedModel model, MatrixStack matrixStack) {
        try {
            this.blockView = blockView;
            this.blockInfo.prepareForWorld(blockView, true);

            Vec3d offset = blockState.getModelOffset(blockPos);
            matrixStack.translate(offset.x, offset.y, offset.z);

            this.matrix = matrixStack.peek().getPositionMatrix();
            this.normalMatrix = matrixStack.peek().getNormalMatrix();
            this.overlay = OverlayTexture.DEFAULT_UV;

            this.blockInfoAccess.quickcraft$setRecomputeSeed(true);

            this.aoCalc.clear();
            this.blockInfo.prepareForBlock(blockState, blockPos, model.useAmbientOcclusion());
            model.emitBlockQuads(this.getEmitter(), this.blockInfo.blockView, this.blockInfo.blockState,
                    this.blockInfo.blockPos, this.blockInfo.randomSupplier, this.blockInfoAccess::quickcraft$shouldCullSide);
        } catch (Throwable throwable) {
            CrashReport report = CrashReport.create(throwable, "Tessellating block in QuickCraft Litematica preview mesh");
            CrashReportSection section = report.addElement("Block being tessellated");
            CrashReportSection.addBlockInfo(section, blockView, blockPos, blockState);
            throw new CrashException(report);
        }
    }

    @Override
    protected AoCalculator createAoCalc(BlockRenderInfo blockInfo) {
        return new AoCalculator(blockInfo) {
            @Override
            public int light(BlockPos pos, BlockState state) {
                return WorldRenderer.getLightmapCoordinates(QuickLitematicaPreviewIndigoContext.this.blockView, state, pos);
            }

            @Override
            public float ao(BlockPos pos, BlockState state) {
                return AoLuminanceFix.INSTANCE.apply(QuickLitematicaPreviewIndigoContext.this.blockView, pos, state);
            }
        };
    }

    @Override
    protected VertexConsumer getVertexConsumer(RenderLayer layer) {
        return this.bufferFunc.apply(layer);
    }
}
