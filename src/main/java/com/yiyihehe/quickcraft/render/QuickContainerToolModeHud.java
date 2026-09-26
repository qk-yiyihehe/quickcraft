package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Displays the active container tool mode while its master switch is enabled. */
public final class QuickContainerToolModeHud {
    private QuickContainerToolModeHud() {
    }

    public static void initialize() {
        HudRenderCallback.EVENT.register((context, tickCounter) -> render(context));
    }

    private static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        // F1 also hides Minecraft's HUD, so the mode text must follow the tool switch itself.
        if (client.player == null || client.world == null || client.currentScreen != null
                || !QuickCraftConfigs.isContainerToolModeEnabled()) {
            return;
        }

        QuickCraftConfigs.ContainerToolMode mode = QuickCraftConfigs.getContainerToolMode();
        Text label = Text.translatable("quickcraft.message.container_tool_mode.changed", mode.getDisplayName());
        int textWidth = client.textRenderer.getWidth(label);
        int x = (context.getScaledWindowWidth() - textWidth) / 2;
        // One text line below vanilla action bar messages.
        int y = context.getScaledWindowHeight() - 62;
        context.drawTextWithShadow(client.textRenderer, label, x, y, 0xFFFFFFFF);
    }
}
