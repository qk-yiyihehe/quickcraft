package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaEntityPlacement;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** 延迟初始化依赖 Litematica 的实体放置客户端协议。 */
public final class QuickEntityPlacementClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (FabricLoader.getInstance().isModLoaded("litematica")) {
            QuickLitematicaEntityPlacement.initializeClient();
        }
    }
}
