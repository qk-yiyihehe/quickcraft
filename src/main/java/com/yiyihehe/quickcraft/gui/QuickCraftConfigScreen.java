package com.yiyihehe.quickcraft.gui;

import com.yiyihehe.quickcraft.QuickCraft;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * 基于 malilib 的配置页。
 * 顶部分成辅助合成工具、辅助容器工具、快捷键绑定三个分页。
 */
public class QuickCraftConfigScreen extends GuiConfigsBase {
    private static Tab currentTab = Tab.CRAFTING;

    public QuickCraftConfigScreen() {
        this(null);
    }

    public QuickCraftConfigScreen(Screen parent) {
        super(10, 50, QuickCraft.MOD_ID, parent, tr("screen.quickcraft.title", "QuickCraft Config"));
        this.setTitle(tr("screen.quickcraft.title", "QuickCraft Config"));
    }

    public static boolean isOpen(Minecraft client) {
        return client != null && client.gui.screen() instanceof QuickCraftConfigScreen;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();

        int x = 10;
        int y = 26;

        for (Tab tab : Tab.values()) {
            x += this.createTabButton(x, y, tab) + 2;
        }
    }

    @Override
    protected int getConfigWidth() {
        return currentTab == Tab.HOTKEYS ? 320 : 240;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        return ConfigOptionWrapper.createFor(currentTab.getOptions());
    }

    private int createTabButton(int x, int y, Tab tab) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, tab.getDisplayName());
        button.setEnabled(currentTab != tab);
        this.addButton(button, new TabButtonListener(tab, this));
        return button.getWidth();
    }

    private enum Tab {
        CRAFTING("screen.quickcraft.tab.crafting", "Crafting Tools", QuickCraftConfigs.Crafting.OPTIONS),
        CONTAINERS("screen.quickcraft.tab.containers", "Container Tools", QuickCraftConfigs.ContainerTools.OPTIONS),
        PROJECTION("screen.quickcraft.tab.projection", "Projection Tools", QuickCraftConfigs.ProjectionTools.OPTIONS),
        MOD_SUPPORT("screen.quickcraft.tab.mod_support", "Mod Support", QuickCraftConfigs.ModSupport.OPTIONS),
        HOTKEYS("screen.quickcraft.tab.hotkeys", "Hotkeys", QuickCraftConfigs.Hotkeys.OPTIONS);

        private final String translationKey;
        private final String fallback;
        private final List<IConfigBase> options;

        Tab(String translationKey, String fallback, List<IConfigBase> options) {
            this.translationKey = translationKey;
            this.fallback = fallback;
            this.options = options;
        }

        private List<IConfigBase> getOptions() {
            return options;
        }

        private String getDisplayName() {
            return tr(this.translationKey, this.fallback);
        }
    }

    private static class TabButtonListener implements IButtonActionListener {
        private final Tab tab;
        private final QuickCraftConfigScreen screen;

        private TabButtonListener(Tab tab, QuickCraftConfigScreen screen) {
            this.tab = tab;
            this.screen = screen;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            currentTab = tab;
            screen.reCreateListWidget();
            var listWidget = screen.getListWidget();
            if (listWidget != null) {
                listWidget.resetScrollbarPosition();
            }
            screen.initGui();
        }
    }

    private static String tr(String key, String fallback) {
        return StringUtils.getTranslatedOrFallback(key, fallback);
    }
}
