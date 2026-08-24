package com.yiyihehe.quickcraft.mixin;

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
    // RETURN 时数据包已经切回客户端线程并应用到 ScreenHandler，可安全检查确认批次终态。
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("RETURN"))
    private void quickcraft$handleSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.getSyncId(), packet.getRevision(), false);
    }

    @Inject(method = "onInventory", at = @At("RETURN"))
    private void quickcraft$handleInventoryUpdate(InventoryS2CPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerContainerUpdate(
                packet.syncId(), packet.revision(), true);
    }

    // 统计回包只作为完整终态批次的顺序屏障，不读取或记录玩家统计内容。
    @Inject(method = "onStatistics", at = @At("RETURN"))
    private void quickcraft$handleServerStatistics(StatisticsS2CPacket packet, CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onServerStatistics(
                (ClientPlayNetworkHandler) (Object) this);
    }
}
