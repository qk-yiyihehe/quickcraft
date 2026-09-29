package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;

/** 26.1.2 的本地持久化状态访问：使用 isSingleplayer 判断单人世界。 */
final class QuickPersistentStateAccess {
    private QuickPersistentStateAccess() {
    }

    static boolean hasSingleplayerServer(Minecraft client) {
        return client.isSingleplayer();
    }
}
