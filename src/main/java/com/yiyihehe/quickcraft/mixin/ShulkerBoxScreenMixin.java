package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给潜影盒界面补容器锁按钮。
 * 使用渲染时懒加载的方式兼容 1.21 下没有独立 init 的界面实现。
 */
@Mixin(ShulkerBoxScreen.class)
public abstract class ShulkerBoxScreenMixin extends HandledScreen<ShulkerBoxScreenHandler> {
    @Unique
    private ButtonWidget quickcraft$lockButton;

    protected ShulkerBoxScreenMixin(ShulkerBoxScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void quickcraft$addLockButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        QuickContainerLock.bindCurrentScreen(this);
        if (!QuickContainerLock.shouldShowLockButton(this)) {
            if (this.quickcraft$lockButton != null) {
                this.quickcraft$lockButton.visible = false;
            }
            return;
        }

        // 潜影盒界面同样没有自己的 init，改为渲染时补按钮更稳。
        if (this.quickcraft$lockButton != null && this.children().contains(this.quickcraft$lockButton)) {
            this.quickcraft$syncLockButtonPosition();
            return;
        }
        var client = this.client;
        if (client == null) {
            return;
        }

        int buttonX = this.x + this.backgroundWidth - 16;
        int buttonY = this.y + 4;
        this.quickcraft$lockButton = this.addDrawableChild(ButtonWidget.builder(QuickContainerLock.getLockButtonText(this), button -> {
                    QuickContainerLock.toggleCurrentScreenLock(client, this);
                    button.setMessage(QuickContainerLock.getLockButtonText(this));
                })
                .dimensions(buttonX, buttonY, 14, 14)
                .build());
        this.quickcraft$syncLockButtonPosition();
    }

    @Unique
    private void quickcraft$syncLockButtonPosition() {
        if (this.quickcraft$lockButton == null) {
            return;
        }

        this.quickcraft$lockButton.visible = true;
        this.quickcraft$lockButton.setX(this.x + this.backgroundWidth - 16);
        this.quickcraft$lockButton.setY(this.y + 4);
        this.quickcraft$lockButton.setMessage(QuickContainerLock.getLockButtonText(this));
    }
}
