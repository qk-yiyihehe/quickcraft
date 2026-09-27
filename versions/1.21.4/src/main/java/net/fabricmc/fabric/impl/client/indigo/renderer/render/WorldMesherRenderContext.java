package net.fabricmc.fabric.impl.client.indigo.renderer.render;

import net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.AoCalculator;
import net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.AoLuminanceFix;
import net.minecraft.block.BlockState;
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
 * 给 Litematica 预览用的 Indigo 方块模型上下文。
 * Fabric API 当前没有公开这个离屏世界 mesh 入口，所以这里只保留 techutils 同款最小桥接。
 */
@SuppressWarnings("UnstableApiUsage")
public class WorldMesherRenderContext extends AbstractBlockRenderContext {
    private BlockRenderView blockView;
    private final Function<RenderLayer, VertexConsumer> bufferFunc;

    public WorldMesherRenderContext(BlockRenderView blockView, Function<RenderLayer, VertexConsumer> bufferFunc) {
        this.blockView = blockView;
        this.bufferFunc = bufferFunc;

        this.blockInfo.prepareForWorld(blockView, true);
        this.blockInfo.random = Random.create();
    }

    public void tessellateBlock(BlockRenderView blockView, BlockState blockState, BlockPos blockPos, BakedModel model, MatrixStack matrixStack) {
        try {
            this.blockView = blockView;
            this.blockInfo.prepareForWorld(blockView, true);

            Vec3d offset = blockState.getModelOffset(blockPos);
            matrixStack.translate(offset.x, offset.y, offset.z);

            this.matrix = matrixStack.peek().getPositionMatrix();
            this.normalMatrix = matrixStack.peek().getNormalMatrix();

            this.blockInfo.recomputeSeed = true;

            this.aoCalc.clear();
            this.blockInfo.prepareForBlock(blockState, blockPos, model.useAmbientOcclusion());
            model.emitBlockQuads(this.getEmitter(), this.blockInfo.blockView, this.blockInfo.blockState, this.blockInfo.blockPos, this.blockInfo.randomSupplier, this.blockInfo::shouldCullSide);
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
                return WorldRenderer.getLightmapCoordinates(WorldMesherRenderContext.this.blockView, state, pos);
            }

            @Override
            public float ao(BlockPos pos, BlockState state) {
                return AoLuminanceFix.INSTANCE.apply(WorldMesherRenderContext.this.blockView, pos, state);
            }
        };
    }

    @Override
    protected VertexConsumer getVertexConsumer(RenderLayer layer) {
        return this.bufferFunc.apply(layer);
    }
}
