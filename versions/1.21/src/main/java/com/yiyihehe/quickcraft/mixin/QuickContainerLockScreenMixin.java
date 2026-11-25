package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.portablestorage.QuickPortableStoragePanel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 通用槽位锁覆盖层（1.21–1.21.5）。
 * 槽位锁切换由 malilib 热键回调负责。
 */
@Mixin(value = HandledScreen.class, priority = 1100)
public abstract class QuickContainerLockScreenMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void quickcraft$renderPortableStorage(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        QuickPortableStoragePanel.render((HandledScreen<?>) (Object) this, context, mouseX, mouseY, delta);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void quickcraft$renderSlotLocks(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        QuickContainerLock.bindCurrentScreen(screen);
        QuickContainerLock.renderSlotLocks(
                screen,
                context,
                accessor.quickcraft$getGuiLeft(),
                accessor.quickcraft$getGuiTop()
        );
    }
}
