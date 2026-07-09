package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 让同位置轻松放置缓存时间跟随 QuickCraft 配置。
 */
@Mixin(value = EasyPlaceUtils.class, remap = false)
public abstract class LitematicaEasyPlaceUtilsMixin {
    @ModifyConstant(
            method = "cacheEasyPlacePosition",
            constant = @Constant(longValue = 2_000_000_000L),
            remap = false
    )
    private static long quickcraft$useConfiguredCacheTime(long timeout) {
        return 1_000_000L * QuickCraftConfigs.getHoldEasyPlaceCacheTimeMs();
    }
}
