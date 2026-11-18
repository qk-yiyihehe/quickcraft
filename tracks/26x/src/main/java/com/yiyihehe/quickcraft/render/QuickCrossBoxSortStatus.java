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

/** 容量提示和结果依次显示；容器打开时在界面上层补画同一条中间提示。 */
public final class QuickCrossBoxSortStatus {
    private static final int DISPLAY_TICKS = 60;
    private static final Deque<Component> pending = new ArrayDeque<>();
    private static Component current;
    private static int remaining;
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
        world = null;
    }

    public static void show(Component text) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) { return; }
        if (world != client.level) { clear(); }
        world = client.level;
        pending.addLast(text.copy());
        if (current == null) { advance(client); }
    }

    private static void tick(Minecraft client) {
        if (client.player == null || client.level != world) { clear(); return; }
        if (current != null && --remaining <= 0) { advance(client); }
    }

    private static void advance(Minecraft client) {
        current = pending.pollFirst();
        remaining = DISPLAY_TICKS;
        if (current != null) { client.player.sendOverlayMessage(current); }
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
