package com.yiyihehe.quickcraft.litematica;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.*;

final class QuickLitematicaPreviewMeshBatches {
    private QuickLitematicaPreviewMeshBatches() {
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
