package com.yiyihehe.quickcraft.malilib;

import fi.dy.masa.malilib.config.IConfigBoolean;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;

/**
 * 覆盖 malilib 默认的英文切换提示，改用当前配置项的显示名。
 */
public final class QuickCraftLocalizedToggleCallback implements IHotkeyCallback {
    private final IConfigBoolean config;

    public QuickCraftLocalizedToggleCallback(IConfigBoolean config) {
        this.config = config;
    }

    @Override
    public boolean onKeyAction(KeyAction action, IKeybind key) {
        if (action != KeyAction.PRESS) {
            return false;
        }

        this.config.toggleBooleanValue();
        String displayName = this.config.getConfigGuiDisplayName();
        InfoUtils.printBooleanConfigToggleMessage(displayName, this.config.getBooleanValue());
        return true;
    }
}
