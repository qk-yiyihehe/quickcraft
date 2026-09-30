package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 26.1.2 调整持续轻松放置的同位置缓存。
 */
@Mixin(value = EasyPlaceUtils.class, remap = false)
public abstract class LitematicaEasyPlaceUtilsMixin {
    @ModifyConstant(
            method = "cacheEasyPlacePosition",
            constant = @Constant(longValue = 2_000_000_000L),
            remap = false
    )
    private static long quickcraft$useConfiguredCacheTime(long timeout) {
        return QuickCraftConfigs.isHoldEasyPlaceEnabled()
                ? 1_000_000L * QuickCraftConfigs.getHoldEasyPlaceCacheTimeMs()
                : timeout;
    }
}
