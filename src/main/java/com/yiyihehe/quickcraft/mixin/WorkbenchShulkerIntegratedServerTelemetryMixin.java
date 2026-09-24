package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerTelemetry;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class WorkbenchShulkerIntegratedServerTelemetryMixin {
    @Shadow
    public ServerPlayer player;

    // forceMainThread 在网络线程会抛出 OffThreadException；AFTER 因而只在集成服务端主线程执行一次。
    @Inject(
            method = "handleContainerClick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void quickcraft$recordIntegratedServerClickStart(ServerboundContainerClickPacket packet,
                                                             CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onIntegratedServerClickStart(packet);
    }

    @Inject(
            method = "handleContainerClick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;broadcastFullState()V",
                    shift = At.Shift.BEFORE
            )
    )
    private void quickcraft$recordIntegratedServerFullSyncStart(ServerboundContainerClickPacket packet,
                                                                CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onIntegratedServerClickProcessed(packet);
    }

    @Inject(
            method = "handleContainerClick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;broadcastChanges()V",
                    shift = At.Shift.BEFORE
            )
    )
    private void quickcraft$recordIntegratedServerIncrementalSyncStart(ServerboundContainerClickPacket packet,
                                                                       CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onIntegratedServerClickProcessed(packet);
    }

    // RETURN 时可读取本次响应最终 revision；客户端是否已应用由 S2C 回包注入单独记录。
    @Inject(method = "handleContainerClick", at = @At("RETURN"))
    private void quickcraft$recordIntegratedServerClickEnd(ServerboundContainerClickPacket packet,
                                                           CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onIntegratedServerClickEnd(
                packet, player.containerMenu.getStateId());
    }
}
