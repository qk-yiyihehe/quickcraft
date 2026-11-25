package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

import java.lang.ref.WeakReference;

/** 完整库存已应用后，按当前菜单实例放行容器工具。 */
public final class QuickContainerToolSync {
    private static WeakReference<ScreenHandler> syncedMenu = new WeakReference<>(null);

    private QuickContainerToolSync() {}

    public static void onServerInventoryUpdate(int containerId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        ScreenHandler menu = client.player.currentScreenHandler;
        if (menu.syncId == containerId && syncedMenu.get() != menu) {
            syncedMenu = new WeakReference<>(menu);
        }
    }

    static boolean hasSyncedContents(ScreenHandler menu) {
        return syncedMenu.get() == menu;
    }

    static final class PendingOpen {
        private static final int OPEN_TIMEOUT_TICKS = 20;

        private final ClientPlayerEntity player;
        private final ClientWorld world;
        private HandledScreen<?> opened;
        private int ticks;

        PendingOpen(MinecraftClient client) {
            player = client.player;
            world = client.world;
        }

        boolean tick(MinecraftClient client) {
            if (client.player != player || client.world != world || ++ticks > OPEN_TIMEOUT_TICKS) {
                return false;
            }
            if (client.currentScreen == null) {
                return opened == null;
            }
            return client.currentScreen instanceof HandledScreen<?> screen
                    && client.player.currentScreenHandler == screen.getScreenHandler()
                    && client.player.currentScreenHandler != client.player.playerScreenHandler && (opened == null
                            || opened == screen);
        }

        boolean inventoryReady(HandledScreen<?> screen) {
            if (opened == null) {
                opened = screen;
                ticks = 0;
            }
            return hasSyncedContents(screen.getScreenHandler());
        }
    }
}
