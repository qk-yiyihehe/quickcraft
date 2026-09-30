package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.glfw.GLFW;

/**
 * 通用槽位锁覆盖层（1.21.10+）。
 * 1.21.10+ mouseClicked 采用 Click 数据封装。
 */
@Mixin(value = HandledScreen.class, priority = 1100)
public abstract class QuickContainerLockScreenMixin {
    @Inject(method = "renderMain", at = @At("TAIL"))
    private void quickcraft$renderSlotLocks(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        QuickContainerLock.bindCurrentScreen(screen);
        QuickContainerLock.renderSlotLocks(screen, context, accessor.quickcraft$getGuiLeft(), accessor.quickcraft$getGuiTop());
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void quickcraft$prepareQuickShulkerOpen(Click click, boolean doubled,
                                                    CallbackInfoReturnable<Boolean> cir) {
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        QuickContainerLock.bindCurrentScreen(screen);
        int button = click.button();
        double mouseX = click.x();
        double mouseY = click.y();
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            QuickContainerLock.prepareQuickShulkerOpen(
                    screen,
                    mouseX,
                    mouseY,
                    accessor.quickcraft$getGuiLeft(),
                    accessor.quickcraft$getGuiTop()
            );
        }
    }
}
