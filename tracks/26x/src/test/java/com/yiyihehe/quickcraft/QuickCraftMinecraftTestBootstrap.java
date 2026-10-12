package com.yiyihehe.quickcraft;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class QuickCraftMinecraftTestBootstrap implements BeforeAllCallback {
    private static boolean initialized;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!initialized) {
            QuickCraftTestRegistries.initialize();
            fi.dy.masa.litematica.world.SchematicWorldHandler.INSTANCE.setDynamicRegistryManager(
                    net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
            initialized = true;
        }
    }
}
