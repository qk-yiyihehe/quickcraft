package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.compat.QuickShulkerConnection;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs.ContainerToolMode;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulker;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.screen.ShulkerBoxScreenHandler;

/** 接管背包潜影盒的右键操作，收到完整库存后复用当前容器工具。 */
public final class QuickInventoryShulkerTools {
    private static final int OPEN_TIMEOUT_TICKS = 20;

    private static HandledScreen<?> sourceScreen;
    private static ClientWorld sourceWorld;
    private static ContainerToolMode pendingMode;
    private static int sourceInventorySlot;
    private static int pendingTicks;
    private static boolean consumeRightRelease;

    private QuickInventoryShulkerTools() {
    }

    public static boolean handleMouseClick(double mouseX, double mouseY, int button, boolean pressed) {
        if (button != 1) {
            return false;
        }
        if (!pressed) {
            boolean consumed = consumeRightRelease;
            consumeRightRelease = false;
            return consumed;
        }
        // malilib 的全局鼠标回调早于 QuickShulker 的界面开盒回调，成功接管后连同松键一起消费。
        consumeRightRelease = QuickContainerLock.openInventoryShulkerForTool(mouseX, mouseY);
        return consumeRightRelease;
    }

    static boolean tryOpen(HandledScreen<?> screen, Slot sourceSlot) {
        clearPending();
        MinecraftClient client = MinecraftClient.getInstance();
        ContainerToolMode mode = QuickCraftConfigs.getContainerToolMode();
        if (client.player == null || client.world == null || client.currentScreen != screen
                || !screen.getScreenHandler().getCursorStack().isEmpty()
                || !isEnabled(mode) || !QuickShulkerConnection.canOpen()
                || QuickStashShulkerTransfer.isRunning()
                || QuickCraftWorkbenchShulker.shouldBlockWorkbenchInput()
                || QuickContainerCopy.shouldHideBackgroundHandledScreen()
                || QuickMaterialCollector.shouldHideBackgroundHandledScreen()) {
            return false;
        }

        int inventorySlot = sourceSlot.getIndex();
        int handlerSlotId = sourceSlot.id;
        if (screen instanceof CreativeInventoryScreen) {
            // 创造背包的包装槽 ID 不属于服务端当前菜单，按底层背包索引映射到玩家菜单。
            handlerSlotId = -1;
            for (Slot slot : client.player.playerScreenHandler.slots) {
                if (slot.inventory == sourceSlot.inventory && slot.getIndex() == inventorySlot) {
                    handlerSlotId = slot.id;
                    break;
                }
            }
            if (handlerSlotId < 0) {
                return false;
            }
        }
        sourceScreen = screen;
        sourceWorld = client.world;
        pendingMode = mode;
        sourceInventorySlot = inventorySlot;
        if (!QuickShulkerConnection.sendOpen(handlerSlotId)) {
            clearPending();
            return false;
        }
        return true;
    }

    static void onClientTick(MinecraftClient client) {
        if (sourceScreen == null) {
            return;
        }
        if (++pendingTicks > OPEN_TIMEOUT_TICKS || !isPendingValid(client)
                || (client.currentScreen != sourceScreen
                    && !(client.currentScreen instanceof HandledScreen<?> screen
                        && screen.getScreenHandler() instanceof ShulkerBoxScreenHandler))) {
            clearPending();
        }
    }

    public static void onServerInventoryUpdate(int syncId) {
        if (sourceScreen == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isPendingValid(client)) {
            clearPending();
            return;
        }
        if (!(client.currentScreen instanceof HandledScreen<?> screen)
                || screen == sourceScreen
                || !(screen.getScreenHandler() instanceof ShulkerBoxScreenHandler)
                || screen.getScreenHandler() != client.player.currentScreenHandler
                || screen.getScreenHandler().syncId != syncId) {
            return;
        }

        ContainerToolMode mode = pendingMode;
        int inventorySlot = sourceInventorySlot;
        clearPending();
        if (!screen.getScreenHandler().getCursorStack().isEmpty()) {
            return;
        }

        // 完整库存包之前的菜单仍可能为空壳，不能据此回存或挑选填充物品。
        QuickContainerLock.bindInventoryShulkerScreen(screen, inventorySlot);
        switch (mode) {
            case QUICK_STASH -> {
                if (!QuickStashShulkerTransfer.start(screen, inventorySlot, false)) {
                    new QuickStash().stashMatchingPlayerItems(screen);
                }
            }
            case QUICK_CLEAR -> new QuickClearContainer().clearContainer(screen);
            case QUICK_FILL -> new QuickFillContainer().fillContainer(screen);
            default -> { return; }
        }
    }

    private static boolean isPendingValid(MinecraftClient client) {
        return client.player != null && client.world == sourceWorld
                && QuickCraftConfigs.getContainerToolMode() == pendingMode
                && isEnabled(pendingMode);
    }

    private static boolean isEnabled(ContainerToolMode mode) {
        if (!QuickCraftConfigs.isContainerToolModeEnabled()
                || !QuickCraftConfigs.ModSupport.ENABLE_QUICK_SHULKER.getBooleanValue()) {
            return false;
        }
        return switch (mode) {
            case QUICK_STASH -> QuickCraftConfigs.ContainerTools.QUICK_STASH_INVENTORY_SHULKERS.getBooleanValue();
            case QUICK_CLEAR -> QuickCraftConfigs.ContainerTools.QUICK_CLEAR_INVENTORY_SHULKERS.getBooleanValue();
            case QUICK_FILL -> QuickCraftConfigs.ContainerTools.QUICK_FILL_INVENTORY_SHULKERS.getBooleanValue();
            default -> false;
        };
    }

    private static void clearPending() {
        sourceScreen = null;
        sourceWorld = null;
        pendingMode = null;
        sourceInventorySlot = -1;
        pendingTicks = 0;
    }
}
