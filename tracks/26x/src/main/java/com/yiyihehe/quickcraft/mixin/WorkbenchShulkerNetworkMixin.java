package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerToolSync;
import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilRename;
import com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckExecutor;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerCraft;
import com.yiyihehe.quickcraft.portablestorage.QuickShulkerRestockFallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class WorkbenchShulkerNetworkMixin {
    // 原版方法入口可能仍在 Netty 线程；RETURN 时已通过 forceMainThread 切回客户端线程。
    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void quickcraft$onSlotUpdate(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        QuickCraftAnvilRename.onServerContainerUpdate(packet.getContainerId(), packet.getSlot());
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.getContainerId(), packet.getStateId(), false);
        QuickCraftMouseCraftAckExecutor.onServerSlotUpdate(
                packet.getContainerId(), packet.getStateId(), packet.getSlot(), packet.getItem());
        com.yiyihehe.quickcraft.sort.QuickCrossBoxSortSync.recordSlot(packet.getContainerId(), packet.getStateId());
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void quickcraft$onInventoryUpdate(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        QuickCraftAnvilRename.onServerContainerUpdate(packet.containerId(), -1);
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.containerId(), packet.stateId(), true);
        QuickCraftMouseCraftAckExecutor.onServerInventoryUpdate(
                packet.containerId(), packet.stateId(), packet.items(), packet.carriedItem());
        QuickContainerToolSync.onServerInventoryUpdate(packet.containerId());
        com.yiyihehe.quickcraft.sort.QuickCrossBoxSortSync.recordContent(packet.containerId(), packet.stateId());
    }

    @Inject(method = "handleEntityEvent", at = @At("RETURN"))
    private void quickcraft$onHandItemConsumed(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        int status = packet.getEventId();
        if ((status == EntityEvent.PROTECTED_FROM_DEATH || status == EntityEvent.MAINHAND_BREAK
                || status == EntityEvent.OFFHAND_BREAK) && client.player != null && client.level != null
                && packet.getEntity(client.level) == client.player) {
            switch (status) {
                case EntityEvent.PROTECTED_FROM_DEATH -> QuickShulkerRestockFallback.totemUsed(client);
                case EntityEvent.MAINHAND_BREAK -> QuickShulkerRestockFallback.itemBroken(client, InteractionHand.MAIN_HAND);
                case EntityEvent.OFFHAND_BREAK -> QuickShulkerRestockFallback.itemBroken(client, InteractionHand.OFF_HAND);
            }
        }
    }

    // RETURN 时客户端 StatHandler 已应用服务端绝对值，统计探针可确认没有槽位变化的批次。
    @Inject(method = "handleAwardStats", at = @At("RETURN"))
    private void quickcraft$onServerCraftStats(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        com.yiyihehe.quickcraft.crafting.QuickCraftAnvilAssistant.onStatistics(
                (ClientPacketListener) (Object) this);
        QuickCraftWorkbenchShulkerCraft.onServerStatistics(
                (ClientPacketListener) (Object) this);
        QuickCraftMouseCraftAckExecutor.onServerStatistics(
                (ClientPacketListener) (Object) this);
    }
}
