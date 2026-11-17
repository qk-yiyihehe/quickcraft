package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.kyrptonaught.quickshulker.network.OpenShulkerPacket", remap = false)
public abstract class QuickShulkerOpenPacketMixin {
    // 实际发包槽号属于服务端当前菜单，避免依赖鼠标回调顺序和创造背包的包装槽号。
    @Inject(method = "sendOpenPacket(I)V", at = @At("HEAD"))
    private static void quickcraft$prepareContainerLock(int slotId, CallbackInfo ci) {
        QuickContainerLock.prepareQuickShulkerOpen(slotId);
    }
}
