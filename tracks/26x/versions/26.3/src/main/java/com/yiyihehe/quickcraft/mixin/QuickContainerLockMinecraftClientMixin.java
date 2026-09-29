package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拦截 26.3+ Minecraft.handleKeybinds 对当前快捷栏物品的直接丢弃。
 * 26.3 起原版丢弃调用迁移为 MultiPlayerGameMode.dropItem。
 */
@Mixin(Minecraft.class)
public abstract class QuickContainerLockMinecraftClientMixin {
    @Redirect(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;dropItem(Lnet/minecraft/client/player/LocalPlayer;Z)V"
            )
    )
    private void quickcraft$blockLockedHotbarDrop(
            MultiPlayerGameMode gameMode, LocalPlayer player, boolean entireStack) {
        if (QuickContainerLock.isLockedPlayerHotbarSlot(player.getInventory().getSelectedSlot())) {
            return;
        }
        gameMode.dropItem(player, entireStack);
    }
}
