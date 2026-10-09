package com.yiyihehe.quickcraft.litematica;

import net.minecraft.client.util.BufferAllocator;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

final class QuickLitematicaPreviewIndices {
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    private QuickLitematicaPreviewIndices() {}

    static int[] initialOrder(int count) {
        // 加载期不画透明层；首次显示前会按实际视角重排，只需原生合法的占位索引。
        int[] order = new int[count];
        for (int i = 0; i < count; i++) order[i] = i;
        return order;
    }

    static BufferAllocator.CloseableBuffer build(BufferAllocator allocator, int[] order, int indexBytes) {
        if (indexBytes != 2 && indexBytes != 4) throw new IllegalArgumentException("Unsupported index width: " + indexBytes);
        long address = allocator.allocate(Math.multiplyExact(order.length, 6 * indexBytes));
        for (int quad : order) {
            int vertex = quad * 4;
            if (indexBytes == 2) {
                MemoryUtil.memPutShort(address, (short) vertex);
                MemoryUtil.memPutShort(address + 2, (short) (vertex + 1));
                MemoryUtil.memPutShort(address + 4, (short) (vertex + 2));
                MemoryUtil.memPutShort(address + 6, (short) (vertex + 2));
                MemoryUtil.memPutShort(address + 8, (short) (vertex + 3));
                MemoryUtil.memPutShort(address + 10, (short) vertex);
                address += 12;
            } else {
                // 保留原生的 0,1,2 / 2,3,0 三角形顺序；一次写两个索引，避开逐索引 IntConsumer 和可变地址游标。
                MemoryUtil.memPutLong(address, pair(vertex, vertex + 1));
                MemoryUtil.memPutLong(address + 8, pair(vertex + 2, vertex + 2));
                MemoryUtil.memPutLong(address + 16, pair(vertex + 3, vertex));
                address += 24;
            }
        }
        return allocator.getAllocated();
    }

    private static long pair(int first, int second) {
        return LITTLE_ENDIAN ? (first & 0xFFFFFFFFL) | ((long) second << 32)
                : ((long) first << 32) | (second & 0xFFFFFFFFL);
    }
}
