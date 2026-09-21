package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerCraft;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class WorkbenchShulkerClientClickMixin {
    @Inject(method = "clickSlot", at = @At("RETURN"))
    private void quickcraft$onWorkbenchClickSent(int syncId,
                                                int slotId,
                                                int button,
                                                SlotActionType actionType,
                                                PlayerEntity player,
                                                CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onWorkbenchClickSent(syncId);
    }
}
