package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortSessions;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortStacks;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortStackKey;
import java.util.Comparator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 跨盒整理入口，复用 QC 的排序规则、配置、热键和容器保护。 */
public final class QuickCrossBoxSort implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("QuickCraft/CrossBoxSort");

    @Override
    public void onInitializeClient() {
        com.yiyihehe.quickcraft.render.QuickCrossBoxSortStatus.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(QuickCrossBoxSortSessions.INSTANCE::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> QuickCrossBoxSortSessions.INSTANCE.clear());
    }

    public static Comparator<QuickCrossBoxSortStacks.Stack<QuickCrossBoxSortStackKey>> comparator() {
        QuickSort.prepareCrossBoxOrder();
        return (left, right) -> QuickSort.compareCrossBoxStacks(
                left.key().template().copyWithCount(left.count()), right.key().template().copyWithCount(right.count()));
    }

    public static void message(Text text) {
        com.yiyihehe.quickcraft.render.QuickCrossBoxSortStatus.show(text);
    }

    public static void reportPlan(int required, int available, boolean onePerBox) {
        if (onePerBox && required > available) {
            message(Text.translatable("quickcraft.message.cross_box_sort.boxes_insufficient", required, available));
        } else {
            message(Text.translatable("quickcraft.message.cross_box_sort.started"));
        }
    }

    public static boolean isRunning() {
        return QuickCrossBoxSortSessions.INSTANCE.isRunning();
    }

    public static boolean handleHotkey() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!QuickCraftConfigs.isCrossBoxSortEnabled()
                || !(client.currentScreen instanceof HandledScreen<?> screen)
                || QuickSort.isTextInputFocused(screen)) {
            return false;
        }
        start(screen, !QuickSort.isContainerSortArea(screen), true);
        return true;
    }

    public static void start(boolean onePerBox) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof HandledScreen<?> screen) { start(screen, true, onePerBox); }
    }

    public static void start(HandledScreen<?> screen, boolean playerInventory, boolean onePerBox) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!QuickCraftConfigs.isCrossBoxSortEnabled() || isRunning() || client.player == null
                || client.currentScreen != screen || screen.getScreenHandler() != client.player.currentScreenHandler
                || QuickStashShulkerTransfer.isRunning()
                || QuickContainerCopy.shouldHideBackgroundHandledScreen()
                || QuickMaterialCollector.shouldHideBackgroundHandledScreen()) { return; }
        QuickContainerLock.bindCurrentScreen(screen);
        if (playerInventory ? QuickContainerLock.handleLockedPlayerInventorySortAttempt(client)
                : QuickContainerLock.handleLockedSortAttempt(client, screen)) { return; }
        var slots = QuickSort.crossBoxSortSlots(screen, playerInventory);
        com.yiyihehe.quickcraft.render.QuickCrossBoxSortStatus.clear();
        if (slots.isEmpty()) { message(Text.translatable("quickcraft.message.cross_box_sort.nothing_to_sort")); return; }
        QuickCrossBoxSortSessions.INSTANCE.start(client, onePerBox, slots, playerInventory);
    }

    public static boolean consumeScreen(Screen screen) {
        return isRunning() && (screen instanceof InventoryScreen || QuickCrossBoxSortSessions.INSTANCE.consumeScreen(screen));
    }
}
