package com.yiyihehe.quickcraft.litematica;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.LayerKey;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.RecordingVertexConsumer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewAccess.QUANTIZED_VERTEX_BYTES;

final class QuickLitematicaPreviewMeshBatches {
    private QuickLitematicaPreviewMeshBatches() {
    }

    static boolean shouldPublish(Map<LayerKey, RecordingVertexConsumer> consumers) {
        int opaqueVertices = 0;
        for (var entry : consumers.entrySet()) {
            int count = entry.getValue().vertices.vertexCount();
            if (entry.getKey().isTranslucent()) {
                if (count >= STATIC_BATCH_TARGET_VERTICES) return true;
            } else {
                opaqueVertices += count;
            }
        }
        return opaqueVertices >= STATIC_BATCH_TARGET_VERTICES;
    }

    static List<LayerMesh> drain(Map<LayerKey, RecordingVertexConsumer> consumers, boolean finalBatch) {
        int opaqueVertices = 0;
        for (var entry : consumers.entrySet()) {
            if (!entry.getKey().isTranslucent()) opaqueVertices += entry.getValue().vertices.vertexCount();
        }
        List<LayerMesh> meshes = new ArrayList<>();
        for (LayerKey layer : LayerKey.DRAW_ORDER) {
            RecordingVertexConsumer consumer = consumers.get(layer);
            if (consumer == null || consumer.vertices.isEmpty()) continue;
            int vertices = layer.isTranslucent() ? consumer.vertices.vertexCount() : opaqueVertices;
            if (!finalBatch && vertices < STATIC_BATCH_TARGET_VERTICES) continue;
            meshes.add(new LayerMesh(layer, consumer.vertices.takeBytes()));
            // Fabric/CTM 可能保存 consumer 引用；保留记录器，只移交已完成方块的顶点。
        }
        return List.copyOf(meshes);
    }

    static List<LayerMesh> split(LayerMesh mesh, AtomicBoolean cancelled) {
        checkCancelled(cancelled);
        if (!mesh.layer().isTranslucent() || mesh.vertexCount() <= STATIC_BATCH_TARGET_VERTICES) {
            return List.of(mesh);
        }
        byte[] vertices = mesh.quantizedVertices();
        int quadBytes = QUANTIZED_VERTEX_BYTES * 4;
        if (vertices.length % quadBytes != 0) {
            throw new IllegalArgumentException("Translucent preview mesh contains an incomplete quad");
        }
        // 排序由后端根据当前视角完成；这里只按完整四边形切批，避免固定相机的重复排序。
        int batchBytes = STATIC_BATCH_TARGET_VERTICES / 4 * quadBytes;
        List<LayerMesh> batches = new ArrayList<>();
        for (int offset = 0; offset < vertices.length; ) {
            checkCancelled(cancelled);
            int end = (int) Math.min((long) vertices.length, (long) offset + batchBytes);
            batches.add(new LayerMesh(mesh.layer(), Arrays.copyOfRange(vertices, offset, end)));
            offset = end;
        }
        return batches;
    }

    private static void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
