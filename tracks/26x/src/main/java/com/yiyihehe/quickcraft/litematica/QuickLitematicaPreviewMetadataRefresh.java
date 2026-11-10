package com.yiyihehe.quickcraft.litematica;

/** 浏览器页面提供给预览管理器的弱回调契约，避免 Manager 强引用 GUI。 */
public interface QuickLitematicaPreviewMetadataRefresh {
    void quickcraft$clearPreviewMetadataCache();
}
