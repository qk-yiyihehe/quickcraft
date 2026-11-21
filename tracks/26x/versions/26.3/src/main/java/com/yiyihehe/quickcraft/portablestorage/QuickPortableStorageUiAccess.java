package com.yiyihehe.quickcraft.portablestorage;

import com.mojang.blaze3d.platform.InputConstants;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

final class QuickPortableStorageUiAccess {
    private QuickPortableStorageUiAccess() {}

    static EditBox searchBox(Font font, Component label) {
        EditBox search = new EditBox(font, label);
        search.setHeight(14);
        return search;
    }

    static boolean spaceDown() {
        return InputConstants.isKeyDown(InputConstants.KEY_SPACE);
    }

    static void cancelQuickCraft(AbstractContainerScreen<?> screen) {
        ((HandledScreenAccessor) screen).quickcraft$setPortableStorageQuickCrafting(false);
        screen.setDragging(false);
    }
}
