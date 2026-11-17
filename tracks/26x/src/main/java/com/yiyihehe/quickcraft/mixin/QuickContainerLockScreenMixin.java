package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 通用槽位锁覆盖层。
 * 槽位锁切换由 malilib 热键回调负责。
 * 26.1+ 的 AbstractRecipeBookScreen 绕过 extractRenderState 并直接调用 extractContents，注入后者才能覆盖生存背包。
 */
@Mixin(value = AbstractContainerScreen.class, priority = 1100)
public abstract class QuickContainerLockScreenMixin {
    @Inject(method = "extractContents", at = @At("TAIL"))
    private void quickcraft$renderSlotLocks(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        QuickContainerLock.bindCurrentScreen(screen);
        QuickContainerLock.renderSlotLocks(screen, context, accessor.quickcraft$getGuiLeft(), accessor.quickcraft$getGuiTop());
    }
}
