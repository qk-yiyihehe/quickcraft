package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaSelectionPreview;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaAreaClone;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaEntityPlacement;
import com.yiyihehe.quickcraft.malilib.QuickCraftMalilibInit;
import fi.dy.masa.malilib.event.InitializationHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * QuickCraft 的 Fabric 主入口。
 * 这里只负责把 malilib 的配置和热键初始化挂到模组加载流程里。
 */
public class QuickCraft implements ModInitializer {
    public static final String MOD_ID = "quickcraft";

    @Override
    public void onInitialize() {
        InitializationHandler.getInstance().registerInitializationHandler(new QuickCraftMalilibInit());
    }

    public static void bindOptionalHotkeys() {
        if (FabricLoader.getInstance().isModLoaded("litematica")) {
            QuickLitematicaSelectionPreview.bindHotkey();
            QuickLitematicaAreaClone.bindHotkey();
        }
    }

    public static boolean openEasyPlaceEntitySelector(Minecraft client) {
        return FabricLoader.getInstance().isModLoaded("litematica")
                && QuickLitematicaEntityPlacement.openSelector(client);
    }
}
