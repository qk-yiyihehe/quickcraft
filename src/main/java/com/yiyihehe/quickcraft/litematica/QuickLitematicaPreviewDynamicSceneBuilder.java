package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.block.BlockState;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.BlockStateData;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.BlockEntityData;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.EntityData;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.DummyWorld;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.DynamicScene;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.RenderedEntity;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 主线程按预算建立完整假世界；发布前不向原生后端暴露部分场景。 */
final class QuickLitematicaPreviewDynamicSceneBuilder {
    private final List<BlockStateData> states;
    private final List<BlockEntityData> blocks;
    private final List<EntityData> entities;
    private final Map<BlockPos, BlockState> blockStates = new HashMap<>();
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private final List<RenderedEntity> renderedEntities = new ArrayList<>();
    @Nullable private DummyWorld world;
    @Nullable private DynamicScene scene;
    @Nullable private QuickLitematicaPreviewBeacons.Initializer beacons;
    private int stage;
    private int stateIndex;
    private int blockIndex;
    private int entityIndex;

    QuickLitematicaPreviewDynamicSceneBuilder(List<BlockStateData> states, List<BlockEntityData> blocks, List<EntityData> entities) {
        this.states = states;
        this.blocks = blocks;
        this.entities = entities;
        if (states.isEmpty() && blocks.isEmpty() && entities.isEmpty()) {
            this.scene = new DynamicScene(null, Map.of(), List.of());
        }
        QuickLitematicaPreviewLog.LOGGER.info("dynamic-scene-sliced-v1：方块状态={}，方块实体={}，实体={}，单次预算={} us",
                states.size(), blocks.size(), entities.size(), QuickLitematicaPreviewBatchBudget.TARGET_NANOS / 1_000L);
    }

    boolean advance(long budgetNanos) {
        long start = System.nanoTime();
        try (var creation = QuickLitematicaPreviewLog.phase("动态场景创建")) {
            do {
                if (this.scene != null) return true;
                switch (this.stage) {
                    case 0 -> {
                        var client = MinecraftClient.getInstance();
                        if (client.world == null) {
                            this.scene = new DynamicScene(null, Map.of(), List.of());
                            return true;
                        }
                        try (var phase = QuickLitematicaPreviewLog.phase("动态假世界创建")) {
                            this.world = DummyWorld.fromWorld(client.world);
                        }
                        this.stage++;
                    }
                    case 1 -> {
                        try (var phase = QuickLitematicaPreviewLog.phase("动态方块状态载入")) {
                            while (this.stateIndex < this.states.size()) {
                                var data = this.states.get(this.stateIndex++);
                                this.blockStates.put(new BlockPos(data.x(), data.y(), data.z()), data.state(this.world.getRegistryManager()));
                                if (System.nanoTime() - start >= budgetNanos) return false;
                            }
                        }
                        // 表由本构建器独占，此后不再写入；直接移交只读视图，避免主线程整表复制。
                        this.world.blockStates = Collections.unmodifiableMap(this.blockStates);
                        this.stage++;
                    }
                    case 2 -> {
                        try (var phase = QuickLitematicaPreviewLog.phase("动态方块实体实例化")) {
                            while (this.blockIndex < this.blocks.size()) {
                                var entity = this.blocks.get(this.blockIndex++).instantiate(this.world);
                                if (entity != null) this.blockEntities.put(entity.getPos(), entity);
                                if (System.nanoTime() - start >= budgetNanos) return false;
                            }
                        }
                        this.world.blockEntities = Collections.unmodifiableMap(this.blockEntities);
                        this.stage++;
                    }
                    case 3 -> {
                        if (this.beacons == null) {
                            this.beacons = new QuickLitematicaPreviewBeacons.Initializer(this.world, this.world.blockStates, this.world.blockEntities);
                        }
                        if (!this.beacons.advance(start, budgetNanos)) return false;
                        this.beacons = null;
                        this.stage++;
                    }
                    case 4 -> {
                        try (var phase = QuickLitematicaPreviewLog.phase("动态实体实例化")) {
                            while (this.entityIndex < this.entities.size()) {
                                var entity = this.entities.get(this.entityIndex++).instantiate(this.world);
                                if (entity != null) this.renderedEntities.add(entity);
                                if (System.nanoTime() - start >= budgetNanos) return false;
                            }
                        }
                        this.scene = new DynamicScene(this.world, this.world.blockEntities, Collections.unmodifiableList(this.renderedEntities));
                        QuickLitematicaPreviewLog.LOGGER.info("动态场景创建完成：方块状态={}，方块实体={}/{}，实体={}/{}，按预算分帧完成",
                                this.blockStates.size(), this.blockEntities.size(), this.blocks.size(), this.renderedEntities.size(), this.entities.size());
                        return true;
                    }
                    default -> throw new IllegalStateException("Unexpected preview scene stage");
                }
            } while (System.nanoTime() - start < budgetNanos);
            return false;
        }
    }

    static void positionEntity(Entity entity, double x, double y, double z) {
        // 已正确载入的位置不能再取整设置，否则画等实体的挂载点偏移会丢失。
        if (entity.getX() != x || entity.getY() != y || entity.getZ() != z) {
            entity.setPosition(x, y, z);
        }
    }

    float progress() {
        long total = (long) this.states.size() + this.blocks.size() + this.entities.size() + 2L;
        return this.scene != null ? 1.0F : Math.min(0.99F,
                (this.stateIndex + (long) this.blockIndex + this.entityIndex + (this.stage > 0 ? 1 : 0)) / (float) total);
    }

    DynamicScene scene() {
        if (this.scene == null) throw new IllegalStateException("Preview scene is not ready");
        return this.scene;
    }
}
