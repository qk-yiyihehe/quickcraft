package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickStash;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ShulkerBoxMenu;
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
public abstract class ShulkerBoxScreenMixin extends AbstractContainerScreen<ShulkerBoxMenu> {
    @Unique
    private Button quickcraft$lockButton;

    @Unique
    private Button quickcraft$stashButton;

    protected ShulkerBoxScreenMixin(ShulkerBoxMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Inject(method = "extractBackground", at = @At("HEAD"))
    private void quickcraft$addLockButton(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        QuickContainerLock.bindCurrentScreen(this);
        this.quickcraft$ensureStashButton();
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

    @Unique
    private void quickcraft$ensureStashButton() {
        if (!QuickCraftConfigs.isQuickStashButtonVisible()) {
            if (this.quickcraft$stashButton != null) {
                this.quickcraft$stashButton.visible = false;
            }
            return;
        }
        if (this.quickcraft$stashButton == null || !this.children().contains(this.quickcraft$stashButton)) {
            this.quickcraft$stashButton = this.addRenderableWidget(Button.builder(Component.literal("↑"), button ->
                             QuickStash.stashFromButton(this))
                    .bounds(this.leftPos + this.imageWidth - 30, this.topPos + 5, 12, 12)
                    .build());
        }
        this.quickcraft$stashButton.visible = true;
        this.quickcraft$stashButton.setX(this.leftPos + this.imageWidth - 30);
        this.quickcraft$stashButton.setY(this.topPos + 5);
        this.quickcraft$stashButton.setTooltip(Tooltip.create(
                Component.translatable("quickcraft.button.quick_stash")));
    }
}
