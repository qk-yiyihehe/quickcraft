package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(QuickCraftMinecraftTestBootstrap.class)
class QuickContainerLockDragTest {
    @Test
    void togglesEachSlotOnceAndStopsOnReleaseOrScreenChange() {
        var client = mock(MinecraftClient.class);
        var inventory = mock(PlayerInventory.class);
        var handler = GenericContainerScreenHandler.createGeneric9x3(0, inventory);
        var screen = mock(HandledScreen.class, withSettings().extraInterfaces(HandledScreenAccessor.class));
        when(screen.getScreenHandler()).thenReturn(handler);
        client.currentScreen = screen;
        var first = handler.slots.get(27);
        var second = handler.slots.get(28);
        boolean overlayVisible = QuickCraftConfigs.ContainerTools.SHOW_SLOT_LOCK_OVERLAY.getBooleanValue();
        QuickCraftConfigs.ContainerTools.SHOW_SLOT_LOCK_OVERLAY.setBooleanValue(true);
        QuickContainerLock.clearPersistentState();

        try (var persistence = mockStatic(QuickPersistentState.class);
             var input = mockStatic(QuickCraftKeyBindings.class)) {
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
            client.currentScreen = null;
            QuickContainerLock.handleSlotLockDrag(client, second.x + 1, second.y + 1);
            client.currentScreen = screen;
            QuickContainerLock.handleSlotLockDrag(client, second.x + 1, second.y + 1);
            assertThat(QuickContainerLock.isLockedSlot(handler, second)).isTrue();
        } finally {
            QuickContainerLock.clearPersistentState();
            QuickCraftConfigs.ContainerTools.SHOW_SLOT_LOCK_OVERLAY.setBooleanValue(overlayVisible);
        }
    }
}
