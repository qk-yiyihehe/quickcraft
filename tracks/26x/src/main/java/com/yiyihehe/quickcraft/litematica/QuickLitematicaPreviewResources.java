package com.yiyihehe.quickcraft.litematica;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

final class QuickLitematicaPreviewResources {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile String signature = "resources-not-loaded";
    private static volatile boolean reloading;

    static void initialize() {
        if (REGISTERED.compareAndSet(false, true)) QuickLitematicaPreviewResourceAccess.register();
    }

    static String signature() { return signature; }

    static boolean reloading() { return reloading; }

    static void prepared() {
        reloading = true;
        QuickLitematicaPreview3D.resourcesReloaded();
    }


    static String load(Callable<Map<String, Callable<InputStream>>> resources) {
        try {
            return hashResources(resources.call());
        } catch (Exception failure) {
            // 签名失败不能阻止游戏加载；不复用无法核验的磁盘网格。
            QuickLitematicaPreviewLog.LOGGER.warn("预览资源签名核验失败，本轮禁止复用旧资源网格", failure);
            return UUID.randomUUID().toString();
        }
    }

    static void apply(String data) {
        signature = data;
        reloading = false;
        // 即使内容相同，重载后的 atlas/GPU 资源也需要重新绑定。
        QuickLitematicaPreview3D.resourcesReloaded();
        QuickLitematicaPreviewLog.LOGGER.info("预览资源内容签名更新：resource-content-v1，签名={}", data);
    }

    static boolean relevant(String path) {
        return path.endsWith(".json") || path.endsWith(".mcmeta") || path.endsWith(".properties")
                || path.endsWith(".png") || path.endsWith(".obj") || path.endsWith(".mtl")
                || path.endsWith(".jem") || path.endsWith(".jpm") || path.endsWith(".bbmodel");
    }

    static String hashResources(Map<String, Callable<InputStream>> resources) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        MessageDigest content = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        for (var entry : new TreeMap<>(resources).entrySet()) {
            QuickLitematicaPreviewCache.updateDigest(digest, entry.getKey());
            try (InputStream input = entry.getValue().call()) {
                // 网格 UV 取决于 sprite 布局/尺寸和动画元数据，像素颜色不改变网格。
                // 只读 PNG 的完整 IHDR，避免重载时重复解压整份高分辨率纹理。
                if (entry.getKey().endsWith(".png")) content.update(input.readNBytes(33));
                else {
                    int count;
                    while ((count = input.read(buffer)) != -1) content.update(buffer, 0, count);
                }
            }
            digest.update(content.digest());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
