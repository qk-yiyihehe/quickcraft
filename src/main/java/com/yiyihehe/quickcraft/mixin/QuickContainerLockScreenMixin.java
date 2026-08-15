package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.glfw.GLFW;

/**
 * 通用槽位锁覆盖层。
 * 这里只负责显示和槽位点击，右上角按钮由各自界面 mixin 负责。
 * 优先于 QuickShulker 的 mouseClicked 取消回调记录潜影盒来源槽位，否则快捷打开后无法绑定锁状态。
 */
@Mixin(value = HandledScreen.class, priority = 1100)
public abstract class QuickContainerLockScreenMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void quickcraft$renderSlotLocks(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        QuickContainerLock.bindCurrentScreen(screen);
        QuickContainerLock.renderSlotLocks(screen, context, accessor.quickcraft$getGuiLeft(), accessor.quickcraft$getGuiTop());
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void quickcraft$handleSlotLockClick(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        QuickContainerLock.bindCurrentScreen(screen);
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !Screen.hasAltDown()) {
            QuickContainerLock.prepareQuickShulkerOpen(
                    screen,
                    mouseX,
                    mouseY,
                    accessor.quickcraft$getGuiLeft(),
                    accessor.quickcraft$getGuiTop()
            );
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && Screen.hasAltDown()
                && QuickContainerLock.handleSlotLockHotkey(
                        screen,
                        mouseX,
                        mouseY,
                        accessor.quickcraft$getGuiLeft(),
                        accessor.quickcraft$getGuiTop()
                )) {
            cir.setReturnValue(true);
            return;
        }
    }
}
