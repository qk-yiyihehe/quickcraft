package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;

/**
 * 26.1.2 的本地持久化状态访问：以本地集成服务端是否存在判断单人世界。
 * isSingleplayer 在“对局域网开放”后会变为 false，不能用于档案身份。
 */
final class QuickPersistentStateAccess {
    private QuickPersistentStateAccess() {}

    static boolean hasSingleplayerServer(Minecraft client) {
        return client.hasSingleplayerServer();
    }
}
