package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerCraft;
import com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckExecutor;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class WorkbenchShulkerClientClickMixin {
    // HEAD 保存点击发出前的准确槽位/光标状态，供普通鼠标合成 ACK 遥测统计。
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

    // RETURN 表示本次原版点击已完成本地预测并发包，随后才能计入当前确认批次。
    @Inject(method = "clickSlot", at = @At("RETURN"))
    private void quickcraft$recordWorkbenchClick(int syncId,
                                                 int slotId,
                                                 int button,
                                                 SlotActionType actionType,
                                                 PlayerEntity player,
                                                 CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onWorkbenchClickSent(syncId);
        QuickCraftMouseCraftAckExecutor.onClientClickEnd(
                syncId, slotId, button, actionType, player);
    }
}
