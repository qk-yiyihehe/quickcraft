package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给通用箱子类界面补容器锁按钮。
 * 这里负责在打开容器时绑定当前界面，并懒加载右上角锁定开关。
 */
@Mixin(ContainerScreen.class)
public abstract class GenericContainerScreenMixin extends AbstractContainerScreen<ChestMenu> {
    @Unique
    private Button quickcraft$lockButton;

    protected GenericContainerScreenMixin(ChestMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Inject(method = "extractBackground", at = @At("HEAD"))
    private void quickcraft$addLockButton(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        QuickContainerLock.bindCurrentScreen(this);
        if (!QuickContainerLock.shouldShowLockButton(this)) {
            if (this.quickcraft$lockButton != null) {
                this.quickcraft$lockButton.visible = false;
            }
            return;
        }

        // ContainerScreen 没有覆写 init，避免注入点随继承实现失效。
        if (this.quickcraft$lockButton != null && this.children().contains(this.quickcraft$lockButton)) {
            this.quickcraft$syncLockButtonPosition();
            return;
        }
        var client = this.minecraft;
        if (client == null) {
            return;
        }

        int buttonX = this.leftPos + this.imageWidth - 16;
        int buttonY = this.topPos + 4;
        this.quickcraft$lockButton = this.addRenderableWidget(Button.builder(QuickContainerLock.getLockButtonText(this), button -> {
                    QuickContainerLock.toggleCurrentScreenLock(client, this);
                    button.setMessage(QuickContainerLock.getLockButtonText(this));
                })
                .bounds(buttonX, buttonY, 14, 14)
                .build());
        this.quickcraft$syncLockButtonPosition();
    }

    @Unique
    private void quickcraft$syncLockButtonPosition() {
        if (this.quickcraft$lockButton == null) {
            return;
        }

        this.quickcraft$lockButton.visible = true;
        this.quickcraft$lockButton.setX(this.leftPos + this.imageWidth - 16);
        this.quickcraft$lockButton.setY(this.topPos + 4);
        this.quickcraft$lockButton.setMessage(QuickContainerLock.getLockButtonText(this));
    }
}
