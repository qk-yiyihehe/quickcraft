package com.yiyihehe.quickcraft.gui;

import com.google.common.collect.ImmutableList;
import com.yiyihehe.quickcraft.config.QuickCraftAnvilEnchantments;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiStringListEdit;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IConfigGui;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;

/** 保留 malilib 原生逐行编辑和重置，只把 ID 列表换成可读名称。 */
final class QuickCraftAnvilConfigButton extends ButtonGeneric {
    private final ConfigStringList config;
    private final IConfigGui host;

    QuickCraftAnvilConfigButton(int x, int y, int width, ConfigStringList config, IConfigGui host) {
        super(x, y, width, 20, "");
        this.config = config;
        this.host = host;
        updateDisplayString();
    }

    @Override
    public void updateDisplayString() {
        if (config == null) return;
        this.displayString = StringUtils.translate("quickcraft.anvil.edit_profile", config.getStrings().size());
    }

    void openEditor() {
        var profile = config;
        var defaults = ImmutableList.copyOf(profile.getDefaultStrings().stream()
                .map(QuickCraftAnvilEnchantments::display).toList());
        var localized = new ConfigStringList(profile.getName(), defaults);
        localized.setStrings(profile.getStrings().stream().map(QuickCraftAnvilEnchantments::display).toList());
        var editor = new GuiStringListEdit(localized, host, null, GuiUtils.getCurrentScreen()) {
            @Override
            public void removed() {
                if (getListWidget() != null && getListWidget().wereConfigsModified()) {
                    // 原生控件直接修改列表，须在父类通知保存前应用输入并统一回写。
                    getListWidget().applyPendingModifications();
                    profile.setStrings(localized.getStrings().stream()
                            .map(QuickCraftAnvilEnchantments::canonical).toList());
                }
                super.removed();
            }
        };
        editor.setTitle(StringUtils.translate("quickcraft.anvil.edit_title", profile.getConfigGuiDisplayName()));
        GuiBase.openGui(editor);
    }
}
