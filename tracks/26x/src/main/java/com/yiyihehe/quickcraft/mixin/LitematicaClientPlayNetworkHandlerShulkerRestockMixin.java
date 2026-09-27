package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaShulkerMaterialRestock;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Quick Shulker 的完整容器内容包到达后，后台处理器才具备可点击的潜影盒槽位。
 * 注入返回点保证 ClientboundContainerSetContentPacket 已写入当前 ScreenHandler；失效时补料会在开箱超时后回退。
 */
@Mixin(ClientPacketListener.class)
public abstract class LitematicaClientPlayNetworkHandlerShulkerRestockMixin {
    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void quickcraft$moveRestockMaterialAfterContents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        QuickLitematicaShulkerMaterialRestock.onShulkerContentsReceived(packet.containerId());
    }
}
