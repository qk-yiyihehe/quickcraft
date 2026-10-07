package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class QuickCraftUpdateNoticeAccess {
    private QuickCraftUpdateNoticeAccess() {
    }

    static void addMessage(Minecraft client, Component message) {
        client.gui.getChat().addClientSystemMessage(message);
    }
}
