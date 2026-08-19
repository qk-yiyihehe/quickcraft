package com.yiyihehe.quickcraft.malilib;

import com.yiyihehe.quickcraft.QuickCraft;
import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickCreativePacking;
import com.yiyihehe.quickcraft.QuickThrow;
import com.yiyihehe.quickcraft.QuickTransfer;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.gui.QuickCraftConfigScreen;
import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;

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
        QuickCraftConfigs.Hotkeys.QUICK_TRANSFER_RETAIN_ONE.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleQuickTransferRetainOne);
        QuickCraftConfigs.Hotkeys.SLOT_QUICK_TRANSFER.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleSlotQuickTransfer);
        QuickCraftConfigs.Hotkeys.SLOT_LOCK.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleSlotLock);
        QuickCraftConfigs.Hotkeys.COPY_CONTAINER_TEMPLATE.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleCopyContainerTemplate);
        QuickCraftConfigs.Hotkeys.CONTINUOUS_CONTAINER_FILL.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleContinuousContainerFill);
        QuickCraftConfigs.Hotkeys.TOGGLE_CONTAINER_TOOL_MODE.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleToggleContainerToolMode);
        QuickCraftConfigs.Hotkeys.CREATIVE_PACKING.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleCreativePacking);
        QuickCraftConfigs.Hotkeys.OPEN_EASY_PLACE_ENTITY_SELECTOR.getKeybind().setCallback(QuickCraftHotkeyCallbacks::handleEntitySelector);
        QuickCraft.bindOptionalHotkeys();
    }

    private static boolean handleOpenConfig(KeyAction action, IKeybind keybind) {
        Minecraft client = Minecraft.getInstance();
        if (action != KeyAction.PRESS
                || client == null
                || client.player == null
                || client.screen != null
                || !QuickCraftConfigs.isOpenConfigHotkeyEnabled()) {
            return false;
        }

        GuiBase.openGui(new QuickCraftConfigScreen());
        return true;
    }

    private static boolean consumeCraftHotkey(KeyAction action, IKeybind keybind) {
        return action == KeyAction.PRESS && isCraftingHotkeyContext(Minecraft.getInstance());
    }

    private static boolean consumeRapidCraftHotkey(KeyAction action, IKeybind keybind) {
        return isCraftingHotkeyContext(Minecraft.getInstance());
    }

    private static boolean handleThrow(KeyAction action, boolean matching) {
        if (action != KeyAction.PRESS) {
            return false;
        }

        return matching ? QuickThrow.handleDropMatchingHotkey() : QuickThrow.handleDropWholeStackHotkey();
    }

    private static boolean handleQuickTransfer(KeyAction action, IKeybind keybind) {
        return action == KeyAction.PRESS
                && !QuickDraggableButton.isEditGestureOverCurrentButton()
                && QuickTransfer.handleQuickTransferHotkey();
    }

    private static boolean handleQuickTransferRetainOne(KeyAction action, IKeybind keybind) {
        return action == KeyAction.PRESS
                && !QuickDraggableButton.isEditGestureOverCurrentButton()
                && QuickTransfer.handleQuickTransferRetainOneHotkey();
    }

    private static boolean handleSlotQuickTransfer(KeyAction action, IKeybind keybind) {
        return action == KeyAction.PRESS
                && !QuickDraggableButton.isEditGestureOverCurrentButton()
                && QuickTransfer.handleSlotQuickTransferHotkey();
    }

    private static boolean handleSlotLock(KeyAction action, IKeybind keybind) {
        return false;
    }

    private static boolean handleCopyContainerTemplate(KeyAction action, IKeybind keybind) {
        return action == KeyAction.PRESS && QuickContainerCopy.handleRecordHotkey(Minecraft.getInstance());
    }

    private static boolean handleContinuousContainerFill(KeyAction action, IKeybind keybind) {
        return (action == KeyAction.PRESS || action == KeyAction.RELEASE)
                && QuickContainerCopy.canHandleContinuousContainerFillHotkey(Minecraft.getInstance());
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

    private static boolean handleCreativePacking(KeyAction action, IKeybind keybind) {
        return action == KeyAction.PRESS && QuickCreativePacking.handleHotkey(Minecraft.getInstance());
    }

    private static boolean handleEntitySelector(KeyAction action, IKeybind keybind) {
        return action == KeyAction.PRESS
                && QuickCraft.openEasyPlaceEntitySelector(Minecraft.getInstance());
    }

    private static boolean isCraftingHotkeyContext(Minecraft client) {
        if (client == null || QuickCraftConfigScreen.isOpen(client)) {
            return false;
        }

        if (QuickCraftConfigs.isWorkbenchQuickCraftEnabled() && client.screen instanceof CraftingScreen) {
            return true;
        }
        if (QuickCraftConfigs.isBackpackQuickCraftEnabled() && client.screen instanceof InventoryScreen) {
            return true;
        }
        if (QuickCraftConfigs.isStonecutterQuickCraftEnabled() && client.screen instanceof StonecutterScreen) {
            return true;
        }
        return QuickCraftConfigs.isAnvilRenameQuickCraftEnabled() && client.screen instanceof AnvilScreen;
    }
}
