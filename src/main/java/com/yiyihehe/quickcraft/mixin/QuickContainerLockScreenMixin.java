package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.glfw.GLFW;

/**
 * 通用槽位锁覆盖层。
 * 这里只负责显示和槽位点击，右上角按钮由各自界面 mixin 负责。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class QuickContainerLockScreenMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void quickcraft$renderSlotLocks(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        QuickContainerLock.bindCurrentScreen(screen);
        QuickContainerLock.renderSlotLocks(screen, context, accessor.quickcraft$getGuiLeft(), accessor.quickcraft$getGuiTop());
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void quickcraft$handleSlotLockClick(MouseButtonEvent click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        QuickContainerLock.bindCurrentScreen(screen);
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && quickcraft$isAltDown()
                && QuickContainerLock.handleSlotLockHotkey(
                        screen,
                        click.x(),
                        click.y(),
                        accessor.quickcraft$getGuiLeft(),
                        accessor.quickcraft$getGuiTop()
                )) {
            cir.setReturnValue(true);
            return;
        }
    }

    @Unique
    private static boolean quickcraft$isAltDown() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }

        long handle = client.getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }
}
