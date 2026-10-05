package com.yiyihehe.quickcraft.gui;

import com.yiyihehe.quickcraft.QuickCraft;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;

/** 只通过隐藏快捷键进入，调试选项仅在本次游戏进程中生效。 */
public final class QuickCraftDeveloperScreen extends GuiConfigsBase {
    public QuickCraftDeveloperScreen(Screen parent) {
        super(10, 50, QuickCraft.MOD_ID, parent, "screen.quickcraft.developer.title");
        this.setParent(parent);
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        return ConfigOptionWrapper.createFor(QuickCraftConfigs.Developer.OPTIONS);
    }
}
