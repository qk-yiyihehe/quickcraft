package com.yiyihehe.quickcraft.mixin;

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
    // RETURN 时数据包已经切回客户端线程并应用到 AbstractContainerMenu，可安全检查确认批次终态。
    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void quickcraft$handleSlotUpdate(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.getContainerId(), packet.getStateId(), false);
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void quickcraft$handleInventoryUpdate(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.containerId(), packet.stateId(), true);
    }

    // 统计回包只作为完整终态批次的顺序屏障，不读取或记录玩家统计内容。
    @Inject(method = "handleAwardStats", at = @At("RETURN"))
    private void quickcraft$handleServerStatistics(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerStatistics(
                (ClientPacketListener) (Object) this);
    }
}
