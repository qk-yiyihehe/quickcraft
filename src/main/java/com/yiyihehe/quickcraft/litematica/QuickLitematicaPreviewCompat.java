package com.yiyihehe.quickcraft.litematica;

import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.LOGGER;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.SHADER_DISABLE_ERROR_LOGGED;

final class QuickLitematicaPreviewCompat {
    private static final boolean ACTIVE = FabricLoader.getInstance().isModLoaded("continuity");
    @Nullable
    private static String cachedRuntimeToken;

    private QuickLitematicaPreviewCompat() {
    }

    static boolean tryDisableShaders() {
        try {
            // Iris 是可选依赖，通过其公开 v0 API 修改光影状态。
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object config = apiClass.getMethod("getConfig").invoke(api);
            Class<?> configClass = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig");
            configClass.getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, false);
            return true;
        } catch (Throwable failure) {
            if (SHADER_DISABLE_ERROR_LOGGED.compareAndSet(false, true)) {
                LOGGER.error("Iris shaders could not be disabled before opening a QuickCraft 3D preview", failure);
            }
            return false;
        }
    }

    static boolean isContinuityModel(Object model) {
        return ACTIVE
                && model != null
                && model.getClass().getName().startsWith("me.pepperbell.continuity.");
    }

    static String ctmRuntimeToken() {
        String token = cachedRuntimeToken;
        if (token != null) {
            return token;
        }

        token = "none";
        if (ACTIVE) {
            token = FabricLoader.getInstance().getModContainer("continuity")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("loaded-unknown");
        }
        cachedRuntimeToken = token;
        return token;
    }
}
