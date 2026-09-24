package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerCraft;
import com.yiyihehe.quickcraft.crafting.QuickCraftMouseCraftAckExecutor;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class WorkbenchShulkerClientClickMixin {
    // HEAD 保存点击发出前的准确槽位/光标状态，供普通鼠标合成 ACK 遥测统计。
    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void quickcraft$recordMouseCraftClickStart(int containerId,
                                                       int slotId,
                                                       int button,
                                                       ContainerInput actionType,
                                                       Player player,
                                                       CallbackInfo ci) {
        QuickCraftMouseCraftAckExecutor.onClientClickStart(
                containerId, slotId, button, actionType, player);
    }

    // RETURN 表示本次原版点击已完成本地预测并发包，随后才能计入当前确认批次。
    @Inject(method = "handleContainerInput", at = @At("RETURN"))
    private void quickcraft$recordWorkbenchClick(int containerId,
                                                 int slotId,
                                                 int button,
                                                 ContainerInput actionType,
                                                 Player player,
                                                 CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onWorkbenchClickSent(containerId);
        QuickCraftMouseCraftAckExecutor.onClientClickEnd(
                containerId, slotId, button, actionType, player);
    }
}
