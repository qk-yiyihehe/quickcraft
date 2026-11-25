package com.yiyihehe.quickcraft.litematica;

import java.lang.reflect.Method;

import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.LOGGER;
import static com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D.SHADER_API_ERROR_LOGGED;

/** 此 API 时期的光影查询和异常回退策略。 */
final class QuickLitematicaPreviewShaderAccess {
    // 只复用可选 API 的类/方法查找；是否启用光影仍由每次查询决定。
    private static final IrisApi IRIS = IrisApi.load();

    private QuickLitematicaPreviewShaderAccess() {}

    public static boolean isShaderPackActive() {
        IrisApi api = IRIS;
        if (api.missing()) {
            return false;
        }
        if (api.error() != null) {
            return queryFailed(api.error());
        }
        try {
            Object instance = api.getInstance().invoke(null);
            return (boolean) api.isShaderPackInUse().invoke(instance);
        } catch (Throwable throwable) {
            return queryFailed(throwable);
        }
    }

    private static boolean queryFailed(Throwable throwable) {
        if (QuickLitematicaPreviewLog.enabled() && SHADER_API_ERROR_LOGGED.compareAndSet(false, true)) {
            LOGGER.error(
                    "Iris shader state could not be queried; disabling QuickCraft 3D previews for this session",
                    throwable
            );
        }
        return true;
    }

    private record IrisApi(Method getInstance, Method isShaderPackInUse, boolean missing, Throwable error) {
        private static IrisApi load() {
            try {
                Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                return new IrisApi(
                        apiClass.getMethod("getInstance"),
                        apiClass.getMethod("isShaderPackInUse"),
                        false,
                        null
                );
            } catch (ClassNotFoundException ignored) {
                return new IrisApi(null, null, true, null);
            } catch (Throwable throwable) {
                return new IrisApi(null, null, false, throwable);
            }
        }
    }
}
