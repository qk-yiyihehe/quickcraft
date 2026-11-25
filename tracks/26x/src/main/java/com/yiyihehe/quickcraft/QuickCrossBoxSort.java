package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.portablestorage.QuickPortableStorageTransfer;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortSessions;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortStacks;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortStackKey;
import java.util.Comparator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
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
                left.key().template().copyWithCount(left.count()),
                right.key().template().copyWithCount(right.count())
        );
    }

    public static void message(Screen screen, Component text) {
        com.yiyihehe.quickcraft.render.QuickCrossBoxSortStatus.show(screen, text);
    }

    public static boolean reportPlan(Screen screen, int required, int available, boolean onePerBox) {
        boolean groupedFallback = onePerBox && required > available;
        message(
                screen,
                groupedFallback ? Component.translatable(
                        "quickcraft.message.cross_box_sort.boxes_insufficient",
                        required,
                        available
                ) : Component.translatable("quickcraft.message.cross_box_sort.started")
        );
        return groupedFallback;
    }

    public static void reportCompletion(Screen screen, boolean groupedFallback, int emptied, int remaining, int moves) {
        message(
                screen,
                groupedFallback ? Component.translatableWithFallback(
                        "quickcraft.message.cross_box_sort.completed_grouped",
                        "Too few boxes, grouped by type: %s freed, %s left, %s moves",
                        emptied,
                        remaining,
                        moves
                ) : Component.translatable("quickcraft.message.cross_box_sort.completed", emptied, remaining, moves)
        );
    }

    public static boolean isRunning() {
        return QuickCrossBoxSortSessions.INSTANCE.isRunning();
    }

    public static boolean handleHotkey() {
        Minecraft client = Minecraft.getInstance();
        if (!QuickCraftConfigs.isCrossBoxSortEnabled() || !(QuickClientScreenAccess.currentScreen(
                client
        ) instanceof AbstractContainerScreen<?> screen) || QuickSort.isTextInputFocused(screen)) {
            return false;
        }
        start(screen, !QuickSort.isContainerSortArea(screen), false);
        return true;
    }

    public static void start(AbstractContainerScreen<?> screen, boolean playerInventory, boolean onePerBox) {
        Minecraft client = Minecraft.getInstance();
        if (!QuickCraftConfigs.isCrossBoxSortEnabled()
                || isRunning()
                || client.player == null
                || QuickClientScreenAccess.currentScreen(client) != screen
                || screen.getMenu() != client.player.containerMenu
                || QuickPortableStorageTransfer.isRunning()
                || QuickStashShulkerTransfer.isRunning()
                || QuickContainerCopy.shouldHideBackgroundHandledScreen()
                || QuickMaterialCollector.shouldHideBackgroundHandledScreen()) {
            return;
        }
        QuickContainerLock.bindCurrentScreen(screen);
        if (playerInventory ? QuickContainerLock.handleLockedPlayerInventorySortAttempt(client)
                : QuickContainerLock.handleLockedSortAttempt(client, screen)) {
            return;
        }
        var slots = QuickSort.crossBoxSortSlots(screen, playerInventory);
        if (slots.isEmpty()) {
            message(screen, Component.translatable("quickcraft.message.cross_box_sort.nothing_to_sort"));
            return;
        }
        QuickCrossBoxSortSessions.INSTANCE.start(client, onePerBox, slots, playerInventory);
    }

    public static boolean consumeScreen(Screen screen) {
        return isRunning() && (screen instanceof InventoryScreen || QuickCrossBoxSortSessions.INSTANCE.consumeScreen(
                screen
        ));
    }

    public static boolean blocksInventoryInput(Screen screen) {
        return QuickCrossBoxSortSessions.INSTANCE.blocksInventoryInput(screen);
    }

    public static boolean consumeInventoryKey(Screen screen, boolean escape) {
        if (!blocksInventoryInput(screen)) {
            return false;
        }
        if (escape) {
            QuickCrossBoxSortSessions.INSTANCE.requestCancel();
        }
        return true;
    }
}
