package com.yiyihehe.quickcraft;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;

final class QuickCraftTestRegistries {
    private QuickCraftTestRegistries() {}

    static void initialize() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup()).forEach(
                pending -> pending.apply()
        );
        // Fabric 将原版注册表冻结推迟到模组初始化之后；单元测试没有游戏启动的后半段。
        BuiltInRegistries.bootStrap();
    }
}
