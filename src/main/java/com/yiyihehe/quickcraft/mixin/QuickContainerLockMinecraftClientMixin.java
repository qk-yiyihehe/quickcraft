package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickContainerLockAccess;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 拦截 1.21 MinecraftClient.handleInputEvents 对当前快捷栏物品的直接丢弃。
 * 该路径在未打开界面时绕过 clickSlot；注入点失效会导致锁定快捷栏仍可用 Q 丢弃。
 */
@Mixin(MinecraftClient.class)
public abstract class QuickContainerLockMinecraftClientMixin {
    @WrapOperation(
            method = "handleInputEvents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerEntity;dropSelectedItem(Z)Z"))
    private boolean quickcraft$blockLockedHotbarDrop(
            ClientPlayerEntity player,
            boolean entireStack,
            Operation<Boolean> original
    ) {
        if (QuickContainerLock.isLockedPlayerHotbarSlot(
                QuickContainerLockAccess.getSelectedSlot(player.getInventory())
        )) {
            return false;
        }

        return original.call(player, entireStack);
    }

    @WrapOperation(
            method = "handleInputEvents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayNetworkHandler;sendPacket(Lnet/minecraft/network/packet/Packet;)V"))
    private void quickcraft$blockLockedOffhandSwap(
            ClientPlayNetworkHandler connection,
            Packet<?> packet,
            Operation<Void> original
    ) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null && packet instanceof PlayerActionC2SPacket action && action
                .getAction() == PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND && !QuickCraftConfigs
                        .areManualLockedSlotInteractionsAllowed() && QuickContainerLock.isLockedPlayerHotbarSlot(
                                QuickContainerLockAccess.getSelectedSlot(player.getInventory())
                        )) {
            return;
        }
        original.call(connection, packet);
    }
}
