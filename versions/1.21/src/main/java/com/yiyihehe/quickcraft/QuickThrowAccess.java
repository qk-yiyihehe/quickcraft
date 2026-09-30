package com.yiyihehe.quickcraft;

import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IKeyboardInputHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/**
 * 1.21 丢弃网络包与键盘输入处理器注册适配器。
 */
public final class QuickThrowAccess {
    private QuickThrowAccess() {
    }

    public static void registerKeyboardInputHandler() {
        InputEventHandler.getInputManager().registerKeyboardInputHandler(new IKeyboardInputHandler() {
            @Override
            public boolean onKeyInput(int keyCode, int scanCode, int modifiers, boolean eventKeyState) {
                return QuickThrow.handleKeyInput(keyCode, eventKeyState);
            }
        });
    }

    public static void executeThrow(MinecraftClient client, QuickThrow.ThrowTarget target) {
        ScreenHandler previousHandler = client.player.currentScreenHandler;
        boolean usingCreativePlayerInventory = target.screen() instanceof CreativeInventoryScreen;
        try {
            if (usingCreativePlayerInventory) {
                client.player.currentScreenHandler = target.handler();
            }
            client.interactionManager.clickSlot(
                    target.handler().syncId,
                    target.clickSlotId(),
                    1,
                    SlotActionType.THROW,
                    client.player
            );
        } finally {
            if (usingCreativePlayerInventory) {
                client.player.currentScreenHandler = previousHandler;
            }
        }
    }
}
