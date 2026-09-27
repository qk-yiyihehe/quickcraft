package com.yiyihehe.quickcraft.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * 可选 Mod Menu 配置入口。
 */
public class QuickCraftModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            QuickCraftConfigScreen screen = new QuickCraftConfigScreen(parent);
            screen.setParent(parent);
            return screen;
        };
    }
}
