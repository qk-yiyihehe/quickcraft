package com.yiyihehe.quickcraft;

import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IKeyboardInputHandler;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/**
 * 1.21.10+ 键盘输入回调迁移至 KeyInput。
 */
public final class QuickThrowAccess {
    private QuickThrowAccess() {
    }

    public static void registerKeyboardInputHandler() {
        InputEventHandler.getInputManager().registerKeyboardInputHandler(new IKeyboardInputHandler() {
            @Override
            public boolean onKeyInput(KeyInput input, boolean eventKeyState) {
                return QuickThrow.handleKeyInput(input.key(), eventKeyState);
            }
        });
    }

    public static void executeThrow(MinecraftClient client, QuickThrow.ThrowTarget target) {
        if (target.screen() instanceof CreativeInventoryScreen) {
            sendCreativePlayerThrowPacket(target, client);
            return;
        }
        client.interactionManager.clickSlot(
                target.handler().syncId,
                target.clickSlotId(),
                1,
                SlotActionType.THROW,
                client.player
        );
    }

    private static void sendCreativePlayerThrowPacket(QuickThrow.ThrowTarget target, MinecraftClient client) {
        if (client.getNetworkHandler() == null) {
            return;
        }
        target.visibleSlot().setStackNoCallbacks(ItemStack.EMPTY);
        target.effectiveSlot().setStackNoCallbacks(ItemStack.EMPTY);
        Int2ObjectMap<ItemStackHash> modifiedStacks = new Int2ObjectOpenHashMap<>();
        modifiedStacks.put(target.clickSlotId(), ItemStackHash.EMPTY);
        client.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(
                target.handler().syncId,
                target.handler().getRevision(),
                (short) target.clickSlotId(),
                (byte) 1,
                SlotActionType.THROW,
                modifiedStacks,
                ItemStackHash.EMPTY
        ));
    }
}
