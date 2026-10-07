package com.yiyihehe.quickcraft;

import net.minecraft.text.ClickEvent;

import java.net.URI;

final class QuickCraftUpdateNoticeAccess {
    private QuickCraftUpdateNoticeAccess() {
    }

    static ClickEvent openUrl(URI uri) {
        return new ClickEvent(ClickEvent.Action.OPEN_URL, uri.toString());
    }
}
