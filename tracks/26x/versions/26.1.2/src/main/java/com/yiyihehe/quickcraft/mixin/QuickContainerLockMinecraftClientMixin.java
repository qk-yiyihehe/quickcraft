package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 拦截 26.1.2–26.2 Minecraft.handleKeybinds 对当前快捷栏物品的直接丢弃。
 * 该路径在未打开界面时绕过 clickSlot；注入点失效会导致锁定快捷栏仍可用 Q 丢弃。
 */
@Mixin(Minecraft.class)
public abstract class QuickContainerLockMinecraftClientMixin {
    @WrapOperation(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;drop(Z)Z"
            )
    )
    private boolean quickcraft$blockLockedHotbarDrop(LocalPlayer player, boolean entireStack, Operation<Boolean> original) {
        if (QuickContainerLock.isLockedPlayerHotbarSlot(player.getInventory().getSelectedSlot())) {
            return false;
        }

        return original.call(player, entireStack);
    }
}
