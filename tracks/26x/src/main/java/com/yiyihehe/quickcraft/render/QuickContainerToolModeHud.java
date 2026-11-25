package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 容器工具总开关开启时，显示当前模式。 */
public final class QuickContainerToolModeHud {
    private QuickContainerToolModeHud() {}

    public static void initialize() {
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.OVERLAY_MESSAGE,
                Identifier.fromNamespaceAndPath("quickcraft", "container_tool_mode"),
                (graphics, tickCounter) -> render(graphics)
        );
    }

    private static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        // F1 也会隐藏 Minecraft 的 HUD，因此模式文字的显隐应由容器工具开关控制。
        if (client.player == null || client.level == null || QuickClientScreenAccess.currentScreen(client) != null
                || !QuickCraftConfigs.isContainerToolModeEnabled()) {
            return;
        }

        QuickCraftConfigs.ContainerToolMode mode = QuickCraftConfigs.getContainerToolMode();
        Component label = Component.translatable(
                "quickcraft.message.container_tool_mode.changed",
                mode.getDisplayName()
        );
        int x = (graphics.guiWidth() - client.font.width(label)) / 2;
        // 显示在原版动作栏消息下方一行。
        int y = graphics.guiHeight() - 62;
        graphics.text(client.font, label, x, y, 0xFFFFFFFF, true);
    }
}
