package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.QuickClientScreenAccess;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

public final class QuickCrossBoxSortStatus {
    private static final int DISPLAY_TICKS = 60;
    private static final int LINE_HEIGHT = 10;
    private static AbstractContainerScreen<?> owner;
    private static Component current;
    private static int remaining;
    private static ClientLevel world;

    private QuickCrossBoxSortStatus() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickCrossBoxSortStatus::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof AbstractContainerScreen<?> container) {
                ScreenEvents.afterExtract(screen).register(
                        (shown, graphics, mouseX, mouseY, delta) -> render(container, graphics)
                );
                ScreenEvents.remove(screen).register(removed -> {
                    if (owner == removed) {
                        clear();
                    }
                });
            }
        });
    }

    public static void show(Component text) {
        var client = Minecraft.getInstance();
        show(QuickClientScreenAccess.currentScreen(client), text);
    }

    public static void show(Screen screen, Component text) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        if (screen instanceof AbstractContainerScreen<?> container && screen == QuickClientScreenAccess.currentScreen(
                client
        )) {
            owner = container;
            world = client.level;
            current = text.copy();
            remaining = DISPLAY_TICKS;
        } else {
            client.player.sendOverlayMessage(text);
        }
    }

    private static void clear() {
        owner = null;
        current = null;
        remaining = 0;
        world = null;
    }

    private static void tick(Minecraft client) {
        if (current != null && (client.player == null || client.level != world || QuickClientScreenAccess.currentScreen(
                client
        ) != owner || --remaining <= 0)) {
            clear();
        }
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        var client = Minecraft.getInstance();
        if (current == null || owner != screen || QuickClientScreenAccess.currentScreen(client) != screen
                || client.player == null || client.level != world) {
            return;
        }
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        var lines = client.font.split(current, Math.max(1, width - 24));
        int textHeight = lines.size() * LINE_HEIGHT;
        var bounds = (HandledScreenAccessor) screen;
        int top = bounds.quickcraft$getGuiTop();
        int bottom = top + bounds.quickcraft$getGuiHeight();
        int y = Math.min(bottom + 4, height - textHeight);
        if (y < bottom) {
            y = Math.max(0, top - textHeight - 4);
        }
        for (var line : lines) {
            int x = (width - client.font.width(line)) / 2;
            graphics.text(client.font, line, x, y, 0xFFFFFFFF, true);
            y += LINE_HEIGHT;
        }
    }
}
