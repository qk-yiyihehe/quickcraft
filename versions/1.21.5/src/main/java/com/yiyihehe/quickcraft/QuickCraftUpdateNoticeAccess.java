package com.yiyihehe.quickcraft;

import net.minecraft.text.ClickEvent;

import java.net.URI;

final class QuickCraftUpdateNoticeAccess {
    private QuickCraftUpdateNoticeAccess() {
    }

    static ClickEvent openUrl(URI uri) {
        return new ClickEvent.OpenUrl(uri);
    }
}
