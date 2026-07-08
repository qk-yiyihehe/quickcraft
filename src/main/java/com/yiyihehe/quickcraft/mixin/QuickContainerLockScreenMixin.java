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
 * 所有处理器界面的锁格覆盖层和快捷点击入口。
 *
 * <p>这个 mixin 只负责槽位上的锁图标与 Alt+右键切换；界面右上角的总开关由具体屏幕
 * mixin 注入。渲染依赖 {@link HandledScreenAccessor} 读取左上角坐标，目标字段变化时，
 * 锁图标会整体偏移或无法命中鼠标点击。</p>
 */
@Mixin(HandledScreen.class)
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
