package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckExecutor;
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
    // HEAD 记录普通鼠标合成 ACK 判定批次路径所需的点击计数。
    @Inject(method = "clickSlot", at = @At("HEAD"))
    private void quickcraft$recordMouseCraftClickStart(int syncId,
                                                       int slotId,
                                                       int button,
                                                       SlotActionType actionType,
                                                       PlayerEntity player,
                                                       CallbackInfo ci) {
        QuickCraftMouseCraftAckExecutor.onClientClickStart(
                syncId, slotId, button, actionType, player);
    }

    // RETURN 时原版已完成点击，通知盒喷状态机更新本批操作。
    @Inject(method = "clickSlot", at = @At("RETURN"))
    private void quickcraft$onWorkbenchClickSent(int syncId,
                                                int slotId,
                                                int button,
                                                SlotActionType actionType,
                                                PlayerEntity player,
                                                CallbackInfo ci) {
        QuickCraftMouseCraftAckExecutor.onClientClickEnd(
                syncId, slotId, button, actionType, player);
        QuickCraftWorkbenchShulkerCraft.onWorkbenchClickSent(syncId);
    }
}
