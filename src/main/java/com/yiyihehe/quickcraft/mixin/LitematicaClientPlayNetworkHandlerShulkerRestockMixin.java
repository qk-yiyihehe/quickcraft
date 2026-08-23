package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaShulkerMaterialRestock;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Quick Shulker 的完整容器内容包到达后，后台处理器才具备可点击的潜影盒槽位。
 * 注入返回点保证 InventoryS2CPacket 已写入当前 ScreenHandler；失效时补料会在开箱超时后回退。
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class LitematicaClientPlayNetworkHandlerShulkerRestockMixin {
    @Inject(method = "onInventory", at = @At("RETURN"))
    private void quickcraft$moveRestockMaterialAfterContents(InventoryS2CPacket packet, CallbackInfo ci) {
        QuickLitematicaShulkerMaterialRestock.onShulkerContentsReceived(packet.syncId());
    }
}
