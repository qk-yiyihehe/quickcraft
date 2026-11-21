package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerToolSync;
import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilRename;
import com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckExecutor;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerCraft;
import com.yiyihehe.quickcraft.portablestorage.QuickShulkerRestockFallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.StatisticsS2CPacket;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class WorkbenchShulkerNetworkMixin {
    // 原版方法入口可能仍在 Netty 线程；RETURN 时已通过 forceMainThread 切回客户端线程。
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("RETURN"))
    private void quickcraft$onSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        QuickCraftAnvilRename.onServerContainerUpdate(packet.getSyncId(), packet.getSlot());
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.getSyncId(), packet.getRevision(), false);
        QuickCraftMouseCraftAckExecutor.onServerSlotUpdate(
                packet.getSyncId(), packet.getRevision(), packet.getSlot(), packet.getStack());
        com.yiyihehe.quickcraft.sort.QuickCrossBoxSortSync.recordSlot(packet.getSyncId(), packet.getRevision());
    }

    @Inject(method = "onInventory", at = @At("RETURN"))
    private void quickcraft$onInventoryUpdate(InventoryS2CPacket packet, CallbackInfo ci) {
        QuickCraftAnvilRename.onServerContainerUpdate(packet.getSyncId(), -1);
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.getSyncId(), packet.getRevision(), true);
        QuickCraftMouseCraftAckExecutor.onServerInventoryUpdate(
                packet.getSyncId(), packet.getRevision(), packet.getContents(), packet.getCursorStack());
        QuickContainerToolSync.onServerInventoryUpdate(packet.getSyncId());
        com.yiyihehe.quickcraft.sort.QuickCrossBoxSortSync.recordContent(packet.getSyncId(), packet.getRevision());
    }

    @Inject(method = "onEntityStatus", at = @At("RETURN"))
    private void quickcraft$onHandItemConsumed(EntityStatusS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        int status = packet.getStatus();
        if ((status == EntityStatuses.USE_TOTEM_OF_UNDYING || status == EntityStatuses.BREAK_MAINHAND
                || status == EntityStatuses.BREAK_OFFHAND) && client.player != null && client.world != null
                && packet.getEntity(client.world) == client.player) {
            switch (status) {
                case EntityStatuses.USE_TOTEM_OF_UNDYING -> QuickShulkerRestockFallback.totemUsed(client);
                case EntityStatuses.BREAK_MAINHAND -> QuickShulkerRestockFallback.itemBroken(client, Hand.MAIN_HAND);
                case EntityStatuses.BREAK_OFFHAND -> QuickShulkerRestockFallback.itemBroken(client, Hand.OFF_HAND);
            }
        }
    }

    // RETURN 时客户端 StatHandler 已应用服务端绝对值，统计探针可确认没有槽位变化的批次。
    @Inject(method = "onStatistics", at = @At("RETURN"))
    private void quickcraft$onServerCraftStats(StatisticsS2CPacket packet, CallbackInfo ci) {
        com.yiyihehe.quickcraft.crafting.QuickCraftAnvilAssistant.onStatistics(
                (ClientPlayNetworkHandler) (Object) this);
        QuickCraftWorkbenchShulkerCraft.onServerStatistics(
                (ClientPlayNetworkHandler) (Object) this);
        QuickCraftMouseCraftAckExecutor.onServerStatistics(
                (ClientPlayNetworkHandler) (Object) this);
    }
}
