package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Litematica 同位置轻松放置缓存时间的配置接入。
 *
 * <p>Litematica 1.21-1.21.1 在 {@code cacheEasyPlacePosition} 里用纳秒常量限制同一位置的重复放置。
 * QuickCraft 只替换这个常量，让长按轻松放置的节奏跟随配置；常量或方法名变化时，表现为配置项失效。</p>
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
