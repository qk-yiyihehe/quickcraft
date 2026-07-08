package com.yiyihehe.quickcraft.malilib;

import com.yiyihehe.quickcraft.QuickCraft;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.gui.QuickCraftConfigScreen;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;

/**
 * 在 malilib 初始化阶段注册 QuickCraft 的配置与热键。
 *
 * <p>这里只做注册：配置 handler、热键 provider、配置页工厂和热键回调。具体功能入口仍由各业务类处理。</p>
 */
public final class QuickCraftMalilibInit implements IInitializationHandler {
    @Override
    public void registerModHandlers() {
        ConfigManager.getInstance().registerConfigHandler(QuickCraft.MOD_ID, new QuickCraftConfigs());
        InputEventHandler.getKeybindManager().registerKeybindProvider(QuickCraftHotkeyProvider.getInstance());
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(QuickCraft.MOD_ID, "QuickCraft", QuickCraftConfigScreen::new)
        );
        QuickCraftHotkeyCallbacks.bind();
    }
}
