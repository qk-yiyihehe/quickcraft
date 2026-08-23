package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerTelemetry;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerCraft;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.StatisticsS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class WorkbenchShulkerNetworkTelemetryMixin {
    // 原版方法入口可能仍在 Netty 线程；RETURN 时已通过 forceMainThread 切回客户端线程。
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("RETURN"))
    private void quickcraft$recordSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onServerSlotUpdate(packet.getSyncId(), packet.getRevision());
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.getSyncId(), packet.getRevision(), false);
    }

    @Inject(method = "onInventory", at = @At("RETURN"))
    private void quickcraft$recordInventoryUpdate(InventoryS2CPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onServerInventoryUpdate(packet.getSyncId(), packet.getRevision());
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.getSyncId(), packet.getRevision(), true);
    }

    // RETURN 时客户端 StatHandler 已应用服务端绝对值，可用于排除本地预测产生的虚假输出点击。
    @Inject(method = "onStatistics", at = @At("RETURN"))
    private void quickcraft$recordServerCraftStats(StatisticsS2CPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerStatistics(
                (ClientPlayNetworkHandler) (Object) this);
    }
}
