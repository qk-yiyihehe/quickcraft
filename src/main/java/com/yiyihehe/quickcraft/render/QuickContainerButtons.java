package com.yiyihehe.quickcraft.render;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickSort;
import com.yiyihehe.quickcraft.QuickStash;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/** 每个容器界面独占一组按钮；重建 children 后重新注册，拖动位置仍由原按钮保存。 */
public final class QuickContainerButtons {
    private QuickStashButton stash;
    private QuickRetrieveButton retrieve;
    private QuickSortButton containerSort;
    private QuickSortButton inventorySort;
    private QuickContainerLockButton inventoryLock;
    private QuickContainerLockButton containerLock;

    public void sync(HandledScreen<?> screen, MinecraftClient client,
                     int left, int top, int width, int height, Consumer<QuickDraggableButton> add) {
        QuickContainerLock.bindCurrentScreen(screen);
        int right = left + width;
        int bottom = top + height - 96;
        boolean stashVisible = QuickCraftConfigs.isQuickStashButtonVisible();
        if (stashVisible && (stash == null || !screen.children().contains(stash))) {
            stash = new QuickStashButton(right - 34, top + 4,
                    button -> QuickStash.stashFromButton(screen),
                    QuickDraggableButton.PositionKey.QUICK_STASH,
                    Text.translatable("quickcraft.button.quick_stash"));
            add.accept(stash);
        }
        position(stash, stashVisible, right - 34, top + 4);
        if (stashVisible && (retrieve == null || !screen.children().contains(retrieve))) {
            retrieve = new QuickRetrieveButton(right - 34, bottom,
                    button -> QuickStash.retrieveFromButton(screen),
                    QuickDraggableButton.PositionKey.QUICK_RETRIEVE,
                    Text.translatable("quickcraft.button.quick_retrieve"));
            add.accept(retrieve);
        }
        position(retrieve, stashVisible, right - 34, bottom);

        boolean sortVisible = QuickCraftConfigs.isQuickSortButtonVisible();
        if (sortVisible && (containerSort == null || !screen.children().contains(containerSort))) {
            containerSort = new QuickSortButton(right - 50, top + 4,
                    button -> QuickSort.sortFromButton(screen, false),
                    QuickDraggableButton.PositionKey.CONTAINER_SORT,
                    Text.translatable("quickcraft.button.sort_container"));
            add.accept(containerSort);
        }
        if (sortVisible && (inventorySort == null || !screen.children().contains(inventorySort))) {
            inventorySort = new QuickSortButton(right - 50, bottom,
                    button -> QuickSort.sortFromButton(screen, true),
                    QuickDraggableButton.PositionKey.INVENTORY_SORT,
                    Text.translatable("quickcraft.button.sort_inventory"));
            add.accept(inventorySort);
        }
        position(containerSort, sortVisible, right - 50, top + 4);
        position(inventorySort, sortVisible, right - 50, bottom);

        boolean inventoryLockVisible = QuickCraftConfigs.isContainerLockButtonVisible();
        if (inventoryLockVisible && (inventoryLock == null || !screen.children().contains(inventoryLock))) {
            inventoryLock = new QuickContainerLockButton(right - 18, bottom,
                    button -> QuickContainerLock.togglePlayerInventoryLock(client),
                    QuickContainerLock::isPlayerInventoryLocked,
                    QuickDraggableButton.PositionKey.INVENTORY_LOCK,
                    Text.translatable("quickcraft.button.inventory_lock.tooltip"));
            add.accept(inventoryLock);
        }
        position(inventoryLock, inventoryLockVisible, right - 18, bottom);

        boolean containerLockVisible = QuickContainerLock.shouldShowLockButton(screen);
        if (containerLockVisible && client != null
                && (containerLock == null || !screen.children().contains(containerLock))) {
            containerLock = new QuickContainerLockButton(right - 18, top + 4,
                    button -> QuickContainerLock.toggleCurrentScreenLock(client, screen),
                    () -> QuickContainerLock.isCurrentScreenLocked(screen),
                    QuickDraggableButton.PositionKey.CONTAINER_LOCK,
                    Text.translatable("quickcraft.button.container_lock.tooltip"));
            add.accept(containerLock);
        }
        position(containerLock, containerLockVisible, right - 18, top + 4);
    }

    private static void position(QuickDraggableButton button, boolean visible, int x, int y) {
        if (button != null) {
            button.visible = visible;
            button.setDefaultPosition(x, y);
        }
    }
}
