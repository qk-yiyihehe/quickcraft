package com.yiyihehe.quickcraft.litematica;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.LOGGER;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.SHADER_API_ERROR_LOGGED;

/** 此 API 时期的光影查询和异常回退策略。 */
final class QuickLitematicaPreviewShaderAccess {
    private QuickLitematicaPreviewShaderAccess() {
    }

    public static boolean isShaderPackActive() {
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            return (boolean) apiClass.getMethod("isShaderPackInUse").invoke(api);
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Throwable throwable) {
            if (SHADER_API_ERROR_LOGGED.compareAndSet(false, true)) {
                LOGGER.error("Iris shader state could not be queried; disabling QuickCraft 3D previews for this session", throwable);
            }
            return true;
        }
    }
}
