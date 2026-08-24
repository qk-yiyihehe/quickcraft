package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchShulkerCraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class WorkbenchShulkerClientClickMixin {
    // RETURN 表示本次原版点击已完成本地预测并发包，随后才能计入当前确认批次。
    @Inject(method = "handleContainerInput", at = @At("RETURN"))
    private void quickcraft$recordWorkbenchClick(int containerId,
                                                 int slotId,
                                                 int button,
                                                 ContainerInput actionType,
                                                 Player player,
                                                 CallbackInfo ci) {
        QuickCraftWorkbenchShulkerCraft.onWorkbenchClickSent(containerId);
    }
}
