package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaEasyPlaceInteractions;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.tool.ToolMode;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * QuickCraft 开关启用后，每个客户端 tick 持续触发 Litematica 轻松放置。
 */
@Mixin(Minecraft.class)
public abstract class LitematicaMinecraftClientHoldEasyPlaceMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void quickcraft$holdEasyPlace(CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;

        if (!QuickCraftConfigs.isHoldEasyPlaceEnabled()
                || client.gui.screen() != null
                || client.player == null
                || client.level == null
                || !Configs.Generic.EASY_PLACE_MODE.getBooleanValue()
                || DataManager.getToolMode() == ToolMode.REBUILD
                || QuickLitematicaEasyPlaceInteractions.shouldAllowVanillaUse(client)) {
            return;
        }

        if (Configs.Generic.EASY_PLACE_POST_REWRITE.getBooleanValue()) {
            if (EasyPlaceUtils.isHandling()) {
                return;
            }

            EasyPlaceUtils.setHandling(true);
            try {
                LitematicaEasyPlaceUtilsInvoker.quickcraft$handleEasyPlace();
            } finally {
                EasyPlaceUtils.setHandling(false);
            }
        } else {
            LitematicaWorldUtilsInvoker.quickcraft$doEasyPlaceAction(client);
        }
    }
}
