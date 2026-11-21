package com.yiyihehe.quickcraft.render;

import java.util.ArrayDeque;
import java.util.Deque;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

/** 在容器界面绘制整理状态，结果可即时替换开始提示。 */
public final class QuickCrossBoxSortStatus {
    private static final int DISPLAY_TICKS = 60;
    private static final Deque<Component> pending = new ArrayDeque<>();
    private static Component current;
    private static int remaining;
    private static boolean currentIsProgress;
    private static ClientLevel world;

    private QuickCrossBoxSortStatus() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickCrossBoxSortStatus::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenEvents.afterExtract(screen).register((shown, graphics, mouseX, mouseY, delta) -> render(graphics));
            }
        });
    }

    public static void clear() {
        pending.clear();
        current = null;
        remaining = 0;
        currentIsProgress = false;
        world = null;
    }

    public static void show(Component text) {
        show(text, false);
    }

    public static void showProgress(Component text) {
        show(text, true);
    }

    private static void show(Component text, boolean progress) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) { return; }
        if (world != client.level) { clear(); }
        world = client.level;
        if (progress) {
            current = text.copy();
            currentIsProgress = true;
            remaining = DISPLAY_TICKS;
            return;
        }
        pending.addLast(text.copy());
        if (current == null || currentIsProgress) { advance(); }
    }

    private static void tick(Minecraft client) {
        if (client.player == null || client.level != world) { clear(); return; }
        if (current != null && --remaining <= 0) { advance(); }
    }

    private static void advance() {
        current = pending.pollFirst();
        remaining = DISPLAY_TICKS;
        currentIsProgress = false;
    }

    private static void render(GuiGraphicsExtractor graphics) {
        var client = Minecraft.getInstance();
        if (current == null || client.player == null || client.level != world) { return; }
        var lines = client.font.split(current, Math.max(1, graphics.guiWidth() - 24));
        int y = graphics.guiHeight() - 68 - (lines.size() - 1) * 10;
        for (var line : lines) {
            int x = (graphics.guiWidth() - client.font.width(line)) / 2;
            graphics.text(client.font, line, x, y, 0xFFFFFFFF, true);
            y += 10;
        }
    }
}
