package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 仅预览构建期间复用材质状态；原生阶段仍决定哪些几何允许重排和合并。 */
public final class QuickLitematicaPreviewRenderTypes implements AutoCloseable {
    @Nullable private static QuickLitematicaPreviewRenderTypes capture;
    @Nullable private static QuickLitematicaPreviewRenderTypes replay;
    private final Thread owner = Thread.currentThread();
    @Nullable private final QuickLitematicaPreviewRenderTypes previousCapture;
    private final Map<MaterialKey, PreparedRenderType> materials = new HashMap<>();
    private final Map<PreparedRenderType, PreparedRenderType> refreshed = new IdentityHashMap<>();
    private int reused;

    private QuickLitematicaPreviewRenderTypes(@Nullable QuickLitematicaPreviewRenderTypes previousCapture) {
        this.previousCapture = previousCapture;
    }

    public static QuickLitematicaPreviewRenderTypes beginCapture() {
        var scope = new QuickLitematicaPreviewRenderTypes(capture);
        capture = scope;
        return scope;
    }

    public static PreparedRenderType capture(RenderType source, PreparedRenderType prepared) {
        var scope = capture;
        return scope == null || scope.owner != Thread.currentThread() ? prepared
                : scope.canonicalize(source, prepared, RenderSystem.getModelViewMatrixCopy());
    }

    PreparedRenderType canonicalize(RenderType source, PreparedRenderType prepared, Matrix4f modelView) {
        var scissor = prepared.scissorState();
        MaterialKey key = new MaterialKey(source, new Matrix4f(modelView), List.copyOf(prepared.textures()),
                scissor.enabled(), scissor.enabled() ? scissor.x() : 0, scissor.enabled() ? scissor.y() : 0,
                scissor.enabled() ? scissor.width() : 0, scissor.enabled() ? scissor.height() : 0);
        PreparedRenderType existing = this.materials.putIfAbsent(key, prepared);
        if (existing == null) return prepared;
        this.reused++;
        return existing;
    }

    void refresh(Matrix4f modelView) {
        // RenderPearl 禁止在 pass 内上传 UBO；所有材质都在打开 pass 前刷新。
        this.refreshed.clear();
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        var previous = capture;
        capture = null;
        try {
            stack.pushMatrix();
            try {
                this.materials.forEach((key, prepared) -> {
                    stack.set(modelView).mul(key.modelView());
                    // 重新执行原材质的层偏移和纹理变换，不能统一替换为单位矩阵。
                    this.refreshed.put(prepared, key.source().prepare());
                });
            } finally {
                stack.popMatrix();
            }
        } finally {
            capture = previous;
        }
    }

    void record(Runnable action) {
        var previous = capture;
        capture = this;
        try { action.run(); }
        finally { capture = previous; }
    }

    void execute(Runnable draw) {
        var previous = replay;
        replay = this;
        try {
            draw.run();
        } finally {
            replay = previous;
        }
    }

    @Nullable
    public static PreparedRenderType refreshed(PreparedRenderType prepared) {
        var scope = replay;
        return scope == null || scope.owner != Thread.currentThread() ? null : scope.refreshed.get(prepared);
    }

    int size() { return this.materials.size(); }
    int reused() { return this.reused; }

    void release() {
        this.materials.clear();
        this.refreshed.clear();
    }

    @Override
    public void close() {
        // 结束记录窗口；材质表仍由缓存帧持有，随缓存帧 release。
        if (capture == this) capture = this.previousCapture;
    }

    private record MaterialKey(RenderType source, Matrix4f modelView, List<PreparedRenderType.Texture> textures,
                               boolean scissorEnabled, int x, int y, int width, int height) { }
}
