package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.glfw.GLFW;

/**
 * 通用槽位锁覆盖层。
 * 这里只显示锁标记并记录 QuickShulker 的右键来源槽位；槽位锁切换由 malilib 热键回调负责。
 * 26.1+ 的 AbstractRecipeBookScreen 绕过 extractRenderState 并直接调用 extractContents，注入后者才能覆盖生存背包。
 * 优先于 QuickShulker 的 mouseClicked 取消回调记录潜影盒来源槽位，否则快捷打开后无法绑定锁状态。
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

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void quickcraft$prepareQuickShulkerOpen(MouseButtonEvent click, boolean doubled, CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        QuickContainerLock.bindCurrentScreen(screen);
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            QuickContainerLock.prepareQuickShulkerOpen(
                    screen,
                    click.x(),
                    click.y(),
                    accessor.quickcraft$getGuiLeft(),
                    accessor.quickcraft$getGuiTop()
            );
        }
    }
}
