package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拦截 1.21 MinecraftClient.handleInputEvents 对当前快捷栏物品的直接丢弃。
 * 该路径在未打开界面时绕过 clickSlot；注入点失效会导致锁定快捷栏仍可用 Q 丢弃。
 */
@Mixin(MinecraftClient.class)
public abstract class QuickContainerLockMinecraftClientMixin {
    @Redirect(
            method = "handleInputEvents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerEntity;dropSelectedItem(Z)Z"
            )
    )
    private boolean quickcraft$blockLockedHotbarDrop(ClientPlayerEntity player, boolean entireStack) {
        if (QuickContainerLock.isLockedPlayerHotbarSlot(player.getInventory().selectedSlot)) {
            return false;
        }

        return player.dropSelectedItem(entireStack);
    }
}
