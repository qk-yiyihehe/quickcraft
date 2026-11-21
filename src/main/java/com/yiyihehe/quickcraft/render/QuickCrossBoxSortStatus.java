package com.yiyihehe.quickcraft.render;

import java.util.ArrayDeque;
import java.util.Deque;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;

/** 在容器界面绘制整理状态，结果可即时替换开始提示。 */
public final class QuickCrossBoxSortStatus {
    private static final int DISPLAY_TICKS = 60;
    private static final Deque<Text> pending = new ArrayDeque<>();
    private static Text current;
    private static int remaining;
    private static boolean currentIsProgress;
    private static ClientWorld world;

    private QuickCrossBoxSortStatus() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickCrossBoxSortStatus::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof HandledScreen<?>) {
                ScreenEvents.afterRender(screen).register((shown, graphics, mouseX, mouseY, delta) -> render(graphics));
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

    public static void show(Text text) {
        show(text, false);
    }

    public static void showProgress(Text text) {
        show(text, true);
    }

    private static void show(Text text, boolean progress) {
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) { return; }
        if (world != client.world) { clear(); }
        world = client.world;
        if (progress) {
            current = text.copy();
            currentIsProgress = true;
            remaining = DISPLAY_TICKS;
            return;
        }
        pending.addLast(text.copy());
        if (current == null || currentIsProgress) { advance(); }
    }

    private static void tick(MinecraftClient client) {
        if (client.player == null || client.world != world) { clear(); return; }
        if (current != null && --remaining <= 0) { advance(); }
    }

    private static void advance() {
        current = pending.pollFirst();
        remaining = DISPLAY_TICKS;
        currentIsProgress = false;
    }

    private static void render(DrawContext graphics) {
        var client = MinecraftClient.getInstance();
        if (current == null || client.player == null || client.world != world) { return; }
        var lines = client.textRenderer.wrapLines(current, Math.max(1, graphics.getScaledWindowWidth() - 24));
        int y = graphics.getScaledWindowHeight() - 68 - (lines.size() - 1) * 10;
        for (var line : lines) {
            int x = (graphics.getScaledWindowWidth() - client.textRenderer.getWidth(line)) / 2;
            graphics.drawTextWithShadow(client.textRenderer, line, x, y, 0xFFFFFFFF);
            y += 10;
        }
    }
}
