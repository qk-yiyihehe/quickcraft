package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerTelemetry;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class WorkbenchShulkerClientClickTelemetryMixin {
    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void quickcraft$recordClickStart(int containerId,
                                             int slotId,
                                             int button,
                                             ContainerInput actionType,
                                             Player player,
                                             CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onClientClickStart(containerId, slotId, button, actionType, player);
    }

    @Inject(
            method = "handleContainerInput",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void quickcraft$recordClickPacketSent(int containerId,
                                                  int slotId,
                                                  int button,
                                                  ContainerInput actionType,
                                                  Player player,
                                                  CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onClientClickSent(
                containerId, slotId, button, actionType, player);
    }

    @Inject(method = "handleContainerInput", at = @At("RETURN"))
    private void quickcraft$recordClickEnd(int containerId,
                                           int slotId,
                                           int button,
                                           ContainerInput actionType,
                                           Player player,
                                           CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onClientClickEnd(containerId, player);
    }
}
