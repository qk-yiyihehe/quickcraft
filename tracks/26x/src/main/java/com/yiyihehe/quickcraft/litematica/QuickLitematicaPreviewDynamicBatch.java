package com.yiyihehe.quickcraft.litematica;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 按原顺序遍历动态记录；完整帧收集与兼容的不透明批次均在全部完成后发布。 */
final class QuickLitematicaPreviewDynamicBatch<L> {
    private final QuickLitematicaPreview3D.DynamicScene scene;
    private final Iterator<Map.Entry<BlockPos, BlockEntity>> blocks;
    private final Iterator<QuickLitematicaPreview3D.RenderedEntity> entities;
    private final long total;
    private L layer;
    private long completed;
    private int records;
    private int size = QuickLitematicaPreviewBatchBudget.INITIAL_SIZE;
    long allocatedBytes;
    int batches;

    QuickLitematicaPreviewDynamicBatch(QuickLitematicaPreview3D.DynamicScene scene) {
        this(scene, QuickLitematicaPreviewBatchBudget.INITIAL_SIZE);
    }

    QuickLitematicaPreviewDynamicBatch(QuickLitematicaPreview3D.DynamicScene scene, int initialSize) {
        this.scene = scene;
        this.size = initialSize;
        this.blocks = scene.blockEntities().entrySet().iterator();
        this.entities = scene.entities().iterator();
        this.total = (long) scene.blockEntities().size() + scene.entities().size();
    }

    QuickLitematicaPreview3D.DynamicScene nextScene() {
        Map<BlockPos, BlockEntity> blockBatch = new LinkedHashMap<>();
        var entityBatch = new ArrayList<QuickLitematicaPreview3D.RenderedEntity>();
        this.records = 0;
        while (this.records < this.size && (this.blocks.hasNext() || this.entities.hasNext())) {
            if (this.blocks.hasNext()) {
                var entry = this.blocks.next();
                blockBatch.put(entry.getKey(), entry.getValue());
            } else entityBatch.add(this.entities.next());
            this.records++;
        }
        return new QuickLitematicaPreview3D.DynamicScene(this.scene.world(), blockBatch, entityBatch);
    }

    void checkLayer(L requested, boolean safe) {
        if (!safe || this.layer != null && this.layer != requested) throw new IncompatibleException();
        this.layer = requested;
    }

    boolean finish(long bytes, long elapsed) {
        this.allocatedBytes = bytes;
        boolean complete = this.finishStep(elapsed);
        QuickLitematicaPreviewLog.LOGGER.info("动态分批上传完成：批次={}，本批记录={}，完成={}/{}，单批={} us，CPU 暂存已释放，累计顶点={} B，下批记录预算={}",
                this.batches, this.records, this.completed, this.total, elapsed / 1_000L, bytes, this.size);
        return complete;
    }

    boolean finishCollected(long elapsed) {
        boolean complete = this.finishStep(elapsed);
        QuickLitematicaPreviewLog.LOGGER.info("dynamic-full-collect-v1：CPU 切片={}，记录={}/{}，本次={} us，尚未上传，保留完整帧顺序",
                this.batches, this.completed, this.total, elapsed / 1_000L);
        return complete;
    }

    private boolean finishStep(long elapsed) {
        this.completed += this.records;
        this.batches++;
        this.size = QuickLitematicaPreviewBatchBudget.nextSize(this.size, elapsed);
        return !this.blocks.hasNext() && !this.entities.hasNext();
    }

    float progress() { return Math.min(0.99F, this.total == 0 ? 0.0F : this.completed / (float) this.total); }

    static final class IncompatibleException extends RuntimeException {
        IncompatibleException() { super("Dynamic batch requires one compatible opaque material"); }
    }
}
