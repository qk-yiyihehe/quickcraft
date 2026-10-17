package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreviewLog;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaSelectionPreview;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaAreaClone;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaEntityPlacement;
import com.yiyihehe.quickcraft.malilib.QuickCraftMalilibInit;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.util.InfoUtils;
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
        QuickLitematicaEntityPlacement.initializeCommon();
        QuickCraftUpdateNotice.initialize();
    }

    public static void bindOptionalHotkeys() {
        QuickCraftConfigs.Developer.LITEMATICA_PREVIEW_3D_LOGS.setValueChangeCallback(config -> {
            boolean enabled = config.getBooleanValue();
            QuickLitematicaPreviewLog.setEnabled(enabled);
            InfoUtils.printActionbarMessage(!enabled
                    ? "quickcraft.message.developer.preview_logs_disabled"
                    : QuickLitematicaPreviewLog.logPath() == null
                            ? "quickcraft.message.developer.preview_logs_failed"
                            : "quickcraft.message.developer.preview_logs_enabled", QuickLitematicaPreviewLog.logPath());
        });
        if (FabricLoader.getInstance().isModLoaded("litematica")) {
            QuickCraftConfigs.ProjectionTools.PERSIST_PROJECTION_FEATURES.setValueChangeCallback(config ->
                    com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionPersistence.settingsChanged());
            QuickCraftConfigs.ProjectionTools.PERSIST_SCHEMATIC_VERIFIER.setValueChangeCallback(config ->
                    com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionPersistence.settingsChanged());
            QuickCraftConfigs.ProjectionTools.ALLOW_PERSISTENT_MATERIAL_IGNORES.setValueChangeCallback(config ->
                    com.yiyihehe.quickcraft.litematica.QuickLitematicaMaterialIgnores.settingsChanged());
            QuickCraftConfigs.ProjectionTools.ALLOW_PERSISTENT_VERIFIER_IGNORES.setValueChangeCallback(config ->
                    com.yiyihehe.quickcraft.litematica.QuickLitematicaVerifierIgnores.settingsChanged());

            QuickCraftConfigs.ProjectionTools.SHOW_LITEMATICA_MATERIAL_REPLACEMENTS.setValueChangeCallback(config ->
                    com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements.changed());
            QuickCraftConfigs.ProjectionTools.GLOBAL_CONTAINER_REPLACEMENTS.setValueChangeCallback(config ->
                    com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements.changed());
            QuickCraftConfigs.ProjectionTools.GLOBAL_PROJECTION_MATERIAL_REPLACEMENTS.setValueChangeCallback(config ->
                    com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements.changed());
            QuickLitematicaSelectionPreview.bindHotkey();
            QuickLitematicaAreaClone.bindHotkey();
        }
    }

    public static void refreshProjectionReplacements() {
        if (FabricLoader.getInstance().isModLoaded("litematica")) {
            com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacements.changed();
        }
    }

    public static void openGlobalProjectionReplacements(net.minecraft.client.gui.screens.Screen parent, boolean container) {
        if (FabricLoader.getInstance().isModLoaded("litematica")) {
            fi.dy.masa.malilib.gui.GuiBase.openGui(new com.yiyihehe.quickcraft.litematica.QuickLitematicaGlobalReplacementScreen(parent,
                    container ? com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerReplacements.Scope.CONTAINER
                            : com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerReplacements.Scope.PROJECTION));
        }
    }

    public static boolean openEasyPlaceEntitySelector(Minecraft client) {
        return FabricLoader.getInstance().isModLoaded("litematica")
                && QuickLitematicaEntityPlacement.openSelector(client);
    }
}
