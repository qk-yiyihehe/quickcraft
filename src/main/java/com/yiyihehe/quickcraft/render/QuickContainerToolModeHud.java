package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** 容器工具总开关开启时，显示当前模式。 */
public final class QuickContainerToolModeHud {
    private QuickContainerToolModeHud() {}

    public static void initialize() {
        HudRenderCallback.EVENT.register((context, tickCounter) -> render(context));
    }

    private static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        // F1 也会隐藏 Minecraft 的 HUD，因此模式文字的显隐应由容器工具开关控制。
        if (client.player == null || client.world == null || client.currentScreen != null || !QuickCraftConfigs
                .isContainerToolModeEnabled()) {
            return;
        }

        QuickCraftConfigs.ContainerToolMode mode = QuickCraftConfigs.getContainerToolMode();
        Text label = Text.translatable("quickcraft.message.container_tool_mode.changed", mode.getDisplayName());
        int textWidth = client.textRenderer.getWidth(label);
        int x = (context.getScaledWindowWidth() - textWidth) / 2;
        // 显示在原版动作栏消息下方一行。
        int y = context.getScaledWindowHeight() - 62;
        QuickContainerToolModeHudAccess.draw(client, context, label, x, y);
    }
}
