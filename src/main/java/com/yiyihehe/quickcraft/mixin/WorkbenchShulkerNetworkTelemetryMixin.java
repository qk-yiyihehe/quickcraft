package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckExecutor;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerTelemetry;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerCraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class WorkbenchShulkerNetworkTelemetryMixin {
    // 原版方法入口可能仍在 Netty 线程；RETURN 时已通过 forceMainThread 切回客户端线程。
    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void quickcraft$recordSlotUpdate(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onServerSlotUpdate(packet.getContainerId(), packet.getStateId());
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.getContainerId(), packet.getStateId(), false);
        QuickCraftMouseCraftAckExecutor.onServerSlotUpdate(
                packet.getContainerId(), packet.getStateId(), packet.getSlot(), packet.getItem());
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void quickcraft$recordInventoryUpdate(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onServerInventoryUpdate(packet.containerId(), packet.stateId());
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.containerId(), packet.stateId(), true);
        QuickCraftMouseCraftAckExecutor.onServerInventoryUpdate(
                packet.containerId(), packet.stateId(), packet.items(), packet.carriedItem());
    }

    // RETURN 时客户端 StatHandler 已应用服务端绝对值，可用于排除本地预测产生的虚假输出点击。
    @Inject(method = "handleAwardStats", at = @At("RETURN"))
    private void quickcraft$recordServerCraftStats(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerStatistics(
                (ClientPacketListener) (Object) this);
        QuickCraftMouseCraftAckExecutor.onServerStatistics(
                (ClientPacketListener) (Object) this);
    }
}
