package com.yiyihehe.quickcraft.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * 可选 Mod Menu 配置入口。
 *
 * <p>这个类只会在 Mod Menu 存在时由它加载，主入口不依赖 Mod Menu API。</p>
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
