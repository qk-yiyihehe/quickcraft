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
        // 26.x 默认物品组件在加载注册表后才绑定；单独 bootstrap 不能构造有效 ItemStack。
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(
                pending -> pending.apply()
        );
        // Fabric 将原版注册表冻结推迟到模组初始化之后；单元测试没有游戏启动的后半段。
        BuiltInRegistries.bootStrap();
    }
}
