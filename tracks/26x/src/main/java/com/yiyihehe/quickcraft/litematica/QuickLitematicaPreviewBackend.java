package com.yiyihehe.quickcraft.litematica;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.function.Consumer;

/**
 * 3D 原理图预览渲染后端接口。
 * 将各 MC 版本的 GPU 上传、离屏 PIP 渲染、快照导出以及动态内容绘制与公共逻辑完全隔离。
 */
public interface QuickLitematicaPreviewBackend extends AutoCloseable {
    /**
     * 将解析出的分层网格上传至 GPU。
     *
     * @param layerMesh 待上传的静态分层网格
     * @return 上传是否成功；若上传被取消或数据无效则返回 false
     */
    boolean uploadLayer(QuickLitematicaPreview3D.LayerMesh layerMesh);

    /**
     * 是否已有完成上传的渲染缓冲。
     */
    boolean hasBuffers();

    /**
     * 静态网格是否已全部上传完毕。
     */
    boolean isStaticUploadComplete();

    /**
     * 设置静态网格上传完成状态。
     */
    void setStaticUploadComplete(boolean complete);

    /**
     * 清理所有已上传的渲染缓冲（例如预览取消或重置时）。
     */
    void clearBuffers();

    /**
     * 离屏捕获 3D 快照图像，供导出 PNG、复制到剪贴板或嵌入原理图文件。
     */
    void captureSnapshot(
            int resolution,
            int backgroundColor,
            QuickLitematicaPreview3D.DragState drag,
            QuickLitematicaPreview3D.MeshData data,
            boolean keepBackgroundOpaque,
            Consumer<NativeImage> callback,
            Consumer<Throwable> errorCallback
    );

    /**
     * 关闭并释放所有 GPU 资源与后台离屏缓冲。
     */
    @Override
    void close();
}
