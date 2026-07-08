package com.yiyihehe.quickcraft.malilib;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

/**
 * 向 malilib 注册 QuickCraft 的全部热键。
 *
 * <p>热键集合来自 {@link QuickCraftConfigs}，这里不维护第二份列表，避免配置页和实际注册结果不一致。</p>
 */
public final class QuickCraftHotkeyProvider implements IKeybindProvider {
    private static final QuickCraftHotkeyProvider INSTANCE = new QuickCraftHotkeyProvider();

    private QuickCraftHotkeyProvider() {
    }

    public static QuickCraftHotkeyProvider getInstance() {
        return INSTANCE;
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (IHotkey hotkey : QuickCraftConfigs.getAllHotkeys()) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(
                "QuickCraft",
                QuickCraftConfigs.getHotkeyCategory(),
                QuickCraftConfigs.getAllHotkeys()
        );
    }
}
