package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.util.BufferAllocator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QuickLitematicaPreviewIndicesTest {
    @Test
    void preservesTriangleWindingAndUnsignedShortBoundary() {
        int count = 16_384;
        int[] order = new int[count];
        for (int i = 0; i < count; i++) order[i] = count - 1 - i;
        for (int indexBytes : new int[]{2, 4}) {
            try (var allocator = new BufferAllocator(count * 6 * indexBytes)) {
                for (int round = 0; round < 2; round++) {
                    try (var indices = QuickLitematicaPreviewIndices.build(allocator, order, indexBytes)) {
                        ByteBuffer bytes = indices.getBuffer().order(ByteOrder.nativeOrder());
                        assertThat(bytes.remaining()).isEqualTo(count * 6 * indexBytes);
                        assertThat(face(bytes, indexBytes)).containsExactly(65532, 65533, 65534, 65534, 65535, 65532);
                        bytes.position(bytes.limit() - 6 * indexBytes);
                        assertThat(face(bytes, indexBytes)).containsExactly(0, 1, 2, 2, 3, 0);
                    }
                    allocator.clear();
                }
            }
        }
    }

    @Test void initialPlaceholderKeepsQuadToVertexPairing() {
        assertThat(QuickLitematicaPreviewIndices.initialOrder(0)).isEmpty();
        try (var allocator = new BufferAllocator(256);
             var indices = QuickLitematicaPreviewIndices.build(allocator, QuickLitematicaPreviewIndices.initialOrder(3), 4)) {
            ByteBuffer bytes = indices.getBuffer().order(ByteOrder.nativeOrder());
            assertThat(face(bytes, 4)).containsExactly(0, 1, 2, 2, 3, 0);
            bytes.position(48);
            assertThat(face(bytes, 4)).containsExactly(8, 9, 10, 10, 11, 8);
        }
    }

    private static int[] face(ByteBuffer bytes, int indexBytes) {
        int[] face = new int[6];
        for (int i = 0; i < face.length; i++) face[i] = indexBytes == 2 ? Short.toUnsignedInt(bytes.getShort()) : bytes.getInt();
        return face;
    }
}
