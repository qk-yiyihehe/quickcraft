package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckExecutor;
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
public abstract class WorkbenchShulkerNetworkMixin {
    // 原版方法入口可能仍在 Netty 线程；RETURN 时已通过 forceMainThread 切回客户端线程。
    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void quickcraft$onSlotUpdate(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.getContainerId(), packet.getStateId(), false);
        QuickCraftMouseCraftAckExecutor.onServerSlotUpdate(
                packet.getContainerId(), packet.getStateId(), packet.getSlot(), packet.getItem());
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void quickcraft$onInventoryUpdate(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.containerId(), packet.stateId(), true);
        QuickCraftMouseCraftAckExecutor.onServerInventoryUpdate(
                packet.containerId(), packet.stateId(), packet.items(), packet.carriedItem());
    }

    // RETURN 时客户端 StatHandler 已应用服务端绝对值，统计探针可确认没有槽位变化的批次。
    @Inject(method = "handleAwardStats", at = @At("RETURN"))
    private void quickcraft$onServerCraftStats(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerStatistics(
                (ClientPacketListener) (Object) this);
        QuickCraftMouseCraftAckExecutor.onServerStatistics(
                (ClientPacketListener) (Object) this);
    }
}
