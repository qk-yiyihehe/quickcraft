package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerTelemetry;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerCraft;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class WorkbenchShulkerClientClickTelemetryMixin {
    @Inject(method = "clickSlot", at = @At("HEAD"))
    private void quickcraft$recordClickStart(int syncId,
                                             int slotId,
                                             int button,
                                             SlotActionType actionType,
                                             PlayerEntity player,
                                             CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onClientClickStart(syncId, slotId, button, actionType, player);
    }

    @Inject(
            method = "clickSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayNetworkHandler;sendPacket(Lnet/minecraft/network/packet/Packet;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void quickcraft$recordClickPacketSent(int syncId,
                                                  int slotId,
                                                  int button,
                                                  SlotActionType actionType,
                                                  PlayerEntity player,
                                                  CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onClientClickSent(
                syncId, slotId, button, actionType, player);
    }

    @Inject(method = "clickSlot", at = @At("RETURN"))
    private void quickcraft$recordClickEnd(int syncId,
                                           int slotId,
                                           int button,
                                           SlotActionType actionType,
                                           PlayerEntity player,
                                           CallbackInfo ci) {
        QuickCraftWorkbenchShulkerTelemetry.onClientClickEnd(syncId, player);
        QuickCraftWorkbenchShulkerCraft.onWorkbenchClickSent(syncId);
    }
}
