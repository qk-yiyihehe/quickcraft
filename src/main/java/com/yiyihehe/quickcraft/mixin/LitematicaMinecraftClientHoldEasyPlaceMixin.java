package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaEasyPlaceContainers;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.tool.ToolMode;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 持续轻松放置的客户端 tick 入口。
 *
 * <p>Litematica 原版轻松放置通常由一次右键驱动，QuickCraft 在
 * {@link MinecraftClient#tick()} 开头复用 {@code WorldUtils.doEasyPlaceAction} 实现长按节奏。
 * 这里必须避开打开界面、重建模式和真实容器交互，否则会抢走原版右键或在 GUI 中继续放置。</p>
 */
@Mixin(MinecraftClient.class)
public abstract class LitematicaMinecraftClientHoldEasyPlaceMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void quickcraft$holdEasyPlace(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;

        if (!QuickCraftConfigs.isHoldEasyPlaceEnabled()
                || client.currentScreen != null
                || client.player == null
                || client.world == null
                || !Configs.Generic.EASY_PLACE_MODE.getBooleanValue()
                || DataManager.getToolMode() == ToolMode.REBUILD
                || QuickLitematicaEasyPlaceContainers.shouldAllowVanillaContainerUse(client)) {
            return;
        }

        LitematicaWorldUtilsInvoker.quickcraft$doEasyPlaceAction(client);
    }
}
