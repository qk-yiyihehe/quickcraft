package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerAutofill;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaEntityPlacement;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaShulkerMaterialRestock;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 在确认 Litematica 存在后再加载专属客户端入口，保持建议依赖缺失时可启动。
 */
public final class QuickLitematicaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            return;
        }

        new QuickLitematicaContainerAutofill().onInitializeClient();
        QuickLitematicaEntityPlacement.initializeClient();
        new QuickLitematicaShulkerMaterialRestock().onInitializeClient();
    }
}
