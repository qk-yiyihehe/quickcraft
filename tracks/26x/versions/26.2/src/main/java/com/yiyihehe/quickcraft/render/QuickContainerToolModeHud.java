package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Displays the active container tool mode while its master switch is enabled. */
public final class QuickContainerToolModeHud {
    private QuickContainerToolModeHud() {
    }

    public static void initialize() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.OVERLAY_MESSAGE,
                Identifier.fromNamespaceAndPath("quickcraft", "container_tool_mode"),
                (graphics, tickCounter) -> render(graphics));
    }

    private static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        // F1 also hides Minecraft's HUD, so the mode text must follow the tool switch itself.
        if (client.player == null || client.level == null || client.gui.screen() != null
                || !QuickCraftConfigs.isContainerToolModeEnabled()) {
            return;
        }

        QuickCraftConfigs.ContainerToolMode mode = QuickCraftConfigs.getContainerToolMode();
        Component label = Component.translatable("quickcraft.message.container_tool_mode.changed", mode.getDisplayName());
        int x = (graphics.guiWidth() - client.font.width(label)) / 2;
        // One text line below vanilla action bar messages.
        int y = graphics.guiHeight() - 62;
        graphics.text(client.font, label, x, y, 0xFFFFFFFF, true);
    }
}
