package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckExecutor;
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
public abstract class WorkbenchShulkerNetworkMixin {
    // 原版方法入口可能仍在 Netty 线程；RETURN 时已通过 forceMainThread 切回客户端线程。
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("RETURN"))
    private void quickcraft$onSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.getSyncId(), packet.getRevision(), false);
        QuickCraftMouseCraftAckExecutor.onServerSlotUpdate(
                packet.getSyncId(), packet.getRevision(), packet.getSlot(), packet.getStack());
    }

    @Inject(method = "onInventory", at = @At("RETURN"))
    private void quickcraft$onInventoryUpdate(InventoryS2CPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.syncId(), packet.revision(), true);
        QuickCraftMouseCraftAckExecutor.onServerInventoryUpdate(
                packet.syncId(), packet.revision(), packet.contents(), packet.cursorStack());
    }

    // RETURN 时客户端 StatHandler 已应用服务端绝对值，统计探针可确认没有槽位变化的批次。
    @Inject(method = "onStatistics", at = @At("RETURN"))
    private void quickcraft$onServerCraftStats(StatisticsS2CPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerStatistics(
                (ClientPlayNetworkHandler) (Object) this);
        QuickCraftMouseCraftAckExecutor.onServerStatistics(
                (ClientPlayNetworkHandler) (Object) this);
    }
}
