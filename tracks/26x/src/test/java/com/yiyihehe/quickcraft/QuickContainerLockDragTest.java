package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickContainerLockDragTest {
    @Test
    void togglesEachSlotOnceAndStopsOnReleaseOrScreenChange() {
        var client = mock(Minecraft.class);
        var inventory = mock(Inventory.class);
        var handler = ChestMenu.threeRows(0, inventory);
        var screen = mock(AbstractContainerScreen.class, withSettings().extraInterfaces(HandledScreenAccessor.class));
        when(screen.getMenu()).thenReturn(handler);
        var first = handler.slots.get(27);
        var second = handler.slots.get(28);
        boolean overlayVisible = QuickCraftConfigs.ContainerTools.SHOW_SLOT_LOCK_OVERLAY.getBooleanValue();
        QuickCraftConfigs.ContainerTools.SHOW_SLOT_LOCK_OVERLAY.setBooleanValue(true);
        QuickContainerLock.clearPersistentState();

        try (var screens = mockStatic(QuickClientScreenAccess.class);
             var persistence = mockStatic(QuickPersistentState.class);
             var input = mockStatic(QuickCraftKeyBindings.class)) {
            screens.when(() -> QuickClientScreenAccess.currentScreen(client)).thenReturn(screen);
            input.when(() -> QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.Hotkeys.SLOT_LOCK.getKeybind()))
                    .thenReturn(true);
            QuickContainerLock.handleSlotLockHotkey(screen, first.x + 1, first.y + 1, 0, 0);
            QuickContainerLock.handleSlotLockDrag(client, second.x + 1, second.y + 1);
            QuickContainerLock.handleSlotLockDrag(client, first.x + 1, first.y + 1);
            assertThat(QuickContainerLock.isLockedSlot(handler, first)).isTrue();
            assertThat(QuickContainerLock.isLockedSlot(handler, second)).isTrue();

            // 新一轮从空白处开始，经过已锁格时解锁，驻留和回划不再翻转。
            QuickContainerLock.handleSlotLockHotkey(screen, -10, -10, 0, 0);
            QuickContainerLock.handleSlotLockDrag(client, first.x + 1, first.y + 1);
            QuickContainerLock.handleSlotLockDrag(client, first.x + 1, first.y + 1);
            assertThat(QuickContainerLock.isLockedSlot(handler, first)).isFalse();
            input.when(() -> QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.Hotkeys.SLOT_LOCK.getKeybind()))
                    .thenReturn(false);
            QuickContainerLock.handleSlotLockDrag(client, second.x + 1, second.y + 1);
            assertThat(QuickContainerLock.isLockedSlot(handler, second)).isTrue();

            input.when(() -> QuickCraftKeyBindings.isHotkeyDown(QuickCraftConfigs.Hotkeys.SLOT_LOCK.getKeybind()))
                    .thenReturn(true);
            QuickContainerLock.handleSlotLockHotkey(screen, -10, -10, 0, 0);
            screens.when(() -> QuickClientScreenAccess.currentScreen(client)).thenReturn(null);
            QuickContainerLock.handleSlotLockDrag(client, second.x + 1, second.y + 1);
            screens.when(() -> QuickClientScreenAccess.currentScreen(client)).thenReturn(screen);
            QuickContainerLock.handleSlotLockDrag(client, second.x + 1, second.y + 1);
            assertThat(QuickContainerLock.isLockedSlot(handler, second)).isTrue();
        } finally {
            QuickContainerLock.clearPersistentState();
            QuickCraftConfigs.ContainerTools.SHOW_SLOT_LOCK_OVERLAY.setBooleanValue(overlayVisible);
        }
    }
}
