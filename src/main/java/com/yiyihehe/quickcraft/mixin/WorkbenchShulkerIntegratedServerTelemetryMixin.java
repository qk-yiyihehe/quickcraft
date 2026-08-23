package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerTelemetry;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class WorkbenchShulkerIntegratedServerTelemetryMixin {
    @Shadow
    public ServerPlayerEntity player;

    // forceMainThread 在网络线程会抛出 OffThreadException；AFTER 因而只在集成服务端主线程执行一次。
    @Inject(
            method = "onClickSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void quickcraft$recordIntegratedServerClickStart(ClickSlotC2SPacket packet,
                                                             CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onIntegratedServerClickStart(packet);
    }

    @Inject(
            method = "onClickSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/screen/ScreenHandler;updateToClient()V",
                    shift = At.Shift.BEFORE
            )
    )
    private void quickcraft$recordIntegratedServerFullSyncStart(ClickSlotC2SPacket packet,
                                                                CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onIntegratedServerClickProcessed(packet);
    }

    @Inject(
            method = "onClickSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/screen/ScreenHandler;sendContentUpdates()V",
                    shift = At.Shift.BEFORE
            )
    )
    private void quickcraft$recordIntegratedServerIncrementalSyncStart(ClickSlotC2SPacket packet,
                                                                       CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onIntegratedServerClickProcessed(packet);
    }

    // RETURN 时可读取本次响应最终 revision；客户端是否已应用由 S2C 回包注入单独记录。
    @Inject(method = "onClickSlot", at = @At("RETURN"))
    private void quickcraft$recordIntegratedServerClickEnd(ClickSlotC2SPacket packet,
                                                           CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onIntegratedServerClickEnd(
                packet, player.currentScreenHandler.getRevision());
    }
}
