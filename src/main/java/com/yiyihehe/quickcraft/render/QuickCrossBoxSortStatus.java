package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;

public final class QuickCrossBoxSortStatus {
    private static final int DISPLAY_TICKS = 60;
    private static final int LINE_HEIGHT = 10;
    private static HandledScreen<?> owner;
    private static Text current;
    private static int remaining;
    private static ClientWorld world;

    private QuickCrossBoxSortStatus() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickCrossBoxSortStatus::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof HandledScreen<?> container) {
                ScreenEvents.afterRender(screen).register(
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

    public static void show(Text text) {
        var client = MinecraftClient.getInstance();
        show(client.currentScreen, text);
    }

    public static void show(Screen screen, Text text) {
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        if (screen instanceof HandledScreen<?> container && screen == client.currentScreen) {
            owner = container;
            world = client.world;
            current = text.copy();
            remaining = DISPLAY_TICKS;
        } else {
            client.player.sendMessage(text, true);
        }
    }

    private static void clear() {
        owner = null;
        current = null;
        remaining = 0;
        world = null;
    }

    private static void tick(MinecraftClient client) {
        if (current != null && (client.player == null || client.world != world || client.currentScreen != owner
                || --remaining <= 0)) {
            clear();
        }
    }

    private static void render(HandledScreen<?> screen, DrawContext graphics) {
        var client = MinecraftClient.getInstance();
        if (current == null || owner != screen || client.currentScreen != screen || client.player == null
                || client.world != world) {
            return;
        }
        int width = graphics.getScaledWindowWidth();
        int height = graphics.getScaledWindowHeight();
        var lines = client.textRenderer.wrapLines(current, Math.max(1, width - 24));
        int textHeight = lines.size() * LINE_HEIGHT;
        var bounds = (HandledScreenAccessor) screen;
        int top = bounds.quickcraft$getGuiTop();
        int bottom = top + bounds.quickcraft$getGuiHeight();
        int y = Math.min(bottom + 4, height - textHeight);
        if (y < bottom) {
            y = Math.max(0, top - textHeight - 4);
        }
        for (var line : lines) {
            int x = (width - client.textRenderer.getWidth(line)) / 2;
            graphics.drawTextWithShadow(client.textRenderer, line, x, y, 0xFFFFFFFF);
            y += LINE_HEIGHT;
        }
    }
}
