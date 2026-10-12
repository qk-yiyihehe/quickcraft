package com.yiyihehe.quickcraft;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** 在任何测试读取注册表之前初始化，避免依赖测试类的执行顺序。 */
public final class QuickCraftMinecraftTestBootstrap implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        fi.dy.masa.litematica.world.SchematicWorldHandler.INSTANCE.setDynamicRegistryManager(
                net.minecraft.registry.DynamicRegistryManager.of(net.minecraft.registry.Registries.REGISTRIES));
    }
}
