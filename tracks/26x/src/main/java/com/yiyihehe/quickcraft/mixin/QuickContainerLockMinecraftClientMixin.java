package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.Minecraft;
//#if MC>=260300
//$$ import net.minecraft.client.multiplayer.MultiPlayerGameMode;
//#endif
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拦截 26.x Minecraft.handleKeybinds 对当前快捷栏物品的直接丢弃。
 * 该路径在未打开界面时绕过 clickSlot；注入点失效会导致锁定快捷栏仍可用 Q 丢弃。
 */
@Mixin(Minecraft.class)
public abstract class QuickContainerLockMinecraftClientMixin {
    @Redirect(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    //#if MC>=260300
                    //$$ target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;dropItem(Lnet/minecraft/client/player/LocalPlayer;Z)V"
                    //#else
                    target = "Lnet/minecraft/client/player/LocalPlayer;drop(Z)Z"
                    //#endif
            )
    )
    //#if MC>=260300
    //$$ private void quickcraft$blockLockedHotbarDrop(
    //$$         MultiPlayerGameMode gameMode, LocalPlayer player, boolean entireStack) {
    //$$     if (QuickContainerLock.isLockedPlayerHotbarSlot(player.getInventory().getSelectedSlot())) {
    //$$         return;
    //$$     }
    //$$     gameMode.dropItem(player, entireStack);
    //$$ }
    //#else
    private boolean quickcraft$blockLockedHotbarDrop(LocalPlayer player, boolean entireStack) {
        if (QuickContainerLock.isLockedPlayerHotbarSlot(player.getInventory().getSelectedSlot())) {
            return false;
        }

        return player.drop(entireStack);
    }
    //#endif
}
