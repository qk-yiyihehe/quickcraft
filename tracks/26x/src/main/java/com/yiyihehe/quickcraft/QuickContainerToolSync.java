package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;

import java.lang.ref.WeakReference;

/** 完整库存已应用后，按当前菜单实例放行容器工具。 */
public final class QuickContainerToolSync {
    private static WeakReference<AbstractContainerMenu> syncedMenu = new WeakReference<>(null);

    private QuickContainerToolSync() {
    }

    public static void onServerInventoryUpdate(int containerId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        AbstractContainerMenu menu = client.player.containerMenu;
        if (menu.containerId == containerId && syncedMenu.get() != menu) {
            syncedMenu = new WeakReference<>(menu);
        }
    }

    static boolean hasSyncedContents(AbstractContainerMenu menu) {
        return syncedMenu.get() == menu;
    }

    static final class PendingOpen {
        private static final int OPEN_TIMEOUT_TICKS = 20;

        private final LocalPlayer player;
        private final ClientLevel world;
        private AbstractContainerScreen<?> opened;
        private int ticks;

        PendingOpen(Minecraft client) {
            player = client.player;
            world = client.level;
        }

        boolean tick(Minecraft client) {
            if (client.player != player || client.level != world || ++ticks > OPEN_TIMEOUT_TICKS) {
                return false;
            }
            if (QuickClientScreenAccess.currentScreen(client) == null) {
                return opened == null;
            }
            return QuickClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?> screen
                    && client.player.containerMenu == screen.getMenu()
                    && client.player.containerMenu != client.player.inventoryMenu
                    && (opened == null || opened == screen);
        }

        boolean inventoryReady(AbstractContainerScreen<?> screen) {
            if (opened == null) {
                opened = screen;
                ticks = 0;
            }
            return hasSyncedContents(screen.getMenu());
        }
    }
}
