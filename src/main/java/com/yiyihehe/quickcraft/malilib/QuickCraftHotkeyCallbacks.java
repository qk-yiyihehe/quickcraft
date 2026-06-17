package com.yiyihehe.quickcraft.malilib;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickThrow;
import com.yiyihehe.quickcraft.QuickTransfer;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.gui.QuickCraftConfigScreen;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;

/**
 * 给 malilib 热键挂接业务回调。
 */
public final class QuickCraftHotkeyCallbacks {
    private QuickCraftHotkeyCallbacks() {
    }

    public static void bind() {
        QuickCraftConfigs.getBooleanHotkeyConfigs().forEach(config ->
                config.getKeybind().setCallback(new QuickCraftLocalizedToggleCallback(config))
        );
        QuickCraftConfigs.Hotkeys.OPEN_CONFIG.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleOpenConfig);
        QuickCraftConfigs.Hotkeys.SINGLE_CRAFT.getKeybind().setCallback(QuickCraftHotkeyCallbacks::consumeCraftHotkey);
        QuickCraftConfigs.Hotkeys.RAPID_CRAFT.getKeybind().setCallback(QuickCraftHotkeyCallbacks::consumeRapidCraftHotkey);
        QuickCraftConfigs.Hotkeys.DROP_MATCHING.getKeybind().setCallback((action, key) -> handleThrow(action, true));
        QuickCraftConfigs.Hotkeys.DROP_WHOLE_STACK.getKeybind().setCallback((action, key) -> handleThrow(action, false));
        QuickCraftConfigs.Hotkeys.QUICK_TRANSFER.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleQuickTransfer);
        QuickCraftConfigs.Hotkeys.SLOT_QUICK_TRANSFER.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleSlotQuickTransfer);
        QuickCraftConfigs.Hotkeys.SLOT_LOCK.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleSlotLock);
        QuickCraftConfigs.Hotkeys.COPY_CONTAINER_TEMPLATE.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleCopyContainerTemplate);
        QuickCraftConfigs.Hotkeys.CONTINUOUS_CONTAINER_FILL.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleContinuousContainerFill);
        QuickCraftConfigs.Hotkeys.TOGGLE_CONTAINER_TOOL_MODE.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleToggleContainerToolMode);
    }

    private static boolean handleOpenConfig(KeyAction action, IKeybind keybind) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (action != KeyAction.PRESS
                || client == null
                || client.player == null
                || client.currentScreen != null
                || !QuickCraftConfigs.isOpenConfigHotkeyEnabled()) {
            return false;
        }

        GuiBase.openGui(new QuickCraftConfigScreen());
        return true;
    }

    private static boolean consumeCraftHotkey(KeyAction action, IKeybind keybind) {
        return action == KeyAction.PRESS && isCraftingHotkeyContext(MinecraftClient.getInstance());
    }

    private static boolean consumeRapidCraftHotkey(KeyAction action, IKeybind keybind) {
        return isCraftingHotkeyContext(MinecraftClient.getInstance());
    }

    private static boolean handleThrow(KeyAction action, boolean matching) {
        if (action != KeyAction.PRESS) {
            return false;
        }

        return matching ? QuickThrow.handleDropMatchingHotkey() : QuickThrow.handleDropWholeStackHotkey();
    }

    private static boolean handleQuickTransfer(KeyAction action, IKeybind keybind) {
        return action == KeyAction.PRESS && QuickTransfer.handleQuickTransferHotkey();
    }

    private static boolean handleSlotQuickTransfer(KeyAction action, IKeybind keybind) {
        return action == KeyAction.PRESS && QuickTransfer.handleSlotQuickTransferHotkey();
    }

    private static boolean handleSlotLock(KeyAction action, IKeybind keybind) {
        return false;
    }

    private static boolean handleCopyContainerTemplate(KeyAction action, IKeybind keybind) {
        return action == KeyAction.PRESS && QuickContainerCopy.handleRecordHotkey(MinecraftClient.getInstance());
    }

    private static boolean handleContinuousContainerFill(KeyAction action, IKeybind keybind) {
        return (action == KeyAction.PRESS || action == KeyAction.RELEASE)
                && QuickContainerCopy.canHandleContinuousContainerFillHotkey(MinecraftClient.getInstance());
    }

    private static boolean handleToggleContainerToolMode(KeyAction action, IKeybind keybind) {
        if (action != KeyAction.PRESS) {
            return false;
        }

        QuickCraftConfigs.cycleContainerToolMode(true);
        InfoUtils.printActionbarMessage(
                "quickcraft.message.container_tool_mode.changed",
                QuickCraftConfigs.getContainerToolMode().getDisplayName()
        );
        return true;
    }

    private static boolean isCraftingHotkeyContext(MinecraftClient client) {
        if (client == null || QuickCraftConfigScreen.isOpen(client)) {
            return false;
        }

        if (QuickCraftConfigs.isWorkbenchQuickCraftEnabled() && client.currentScreen instanceof CraftingScreen) {
            return true;
        }
        if (QuickCraftConfigs.isBackpackQuickCraftEnabled() && client.currentScreen instanceof InventoryScreen) {
            return true;
        }
        if (QuickCraftConfigs.isStonecutterQuickCraftEnabled() && client.currentScreen instanceof StonecutterScreen) {
            return true;
        }
        return QuickCraftConfigs.isAnvilRenameQuickCraftEnabled() && client.currentScreen instanceof AnvilScreen;
    }
}
