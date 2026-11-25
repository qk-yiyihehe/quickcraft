package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 拦截 26.3+ Minecraft.handleKeybinds 对当前快捷栏物品的直接丢弃。
 * 26.3 起原版丢弃调用迁移为 MultiPlayerGameMode.dropItem。
 */
@Mixin(Minecraft.class)
public abstract class QuickContainerLockMinecraftClientMixin {
    @WrapOperation(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;dropItem(Lnet/minecraft/client/player/LocalPlayer;Z)V"))
    private void quickcraft$blockLockedHotbarDrop(
            MultiPlayerGameMode gameMode,
            LocalPlayer player,
            boolean entireStack,
            Operation<Void> original
    ) {
        if (QuickContainerLock.isLockedPlayerHotbarSlot(player.getInventory().getSelectedSlot())) {
            return;
        }
        original.call(gameMode, player, entireStack);
    }

    @WrapOperation(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void quickcraft$blockLockedOffhandSwap(
            ClientPacketListener connection,
            Packet<?> packet,
            Operation<Void> original
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null
                && packet instanceof ServerboundPlayerActionPacket action
                && action.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
                && !QuickCraftConfigs.areManualLockedSlotInteractionsAllowed()
                && QuickContainerLock.isLockedPlayerHotbarSlot(player.getInventory().getSelectedSlot())) {
            return;
        }
        original.call(connection, packet);
    }
}
