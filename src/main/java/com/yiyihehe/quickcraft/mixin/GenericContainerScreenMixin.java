package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickStash;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给通用箱子类界面补容器锁按钮。
 * 这里负责在打开容器时绑定当前界面，并懒加载右上角锁定开关。
 */
@Mixin(GenericContainerScreen.class)
public abstract class GenericContainerScreenMixin extends HandledScreen<GenericContainerScreenHandler> {
    @Unique
    private ButtonWidget quickcraft$lockButton;

    @Unique
    private ButtonWidget quickcraft$stashButton;

    protected GenericContainerScreenMixin(GenericContainerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void quickcraft$addLockButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        QuickContainerLock.bindCurrentScreen(this);
        this.quickcraft$ensureStashButton();
        if (!QuickContainerLock.shouldShowLockButton(this)) {
            if (this.quickcraft$lockButton != null) {
                this.quickcraft$lockButton.visible = false;
            }
            return;
        }

        // GenericContainerScreen 在 1.21 里也没有覆写 init，避免注入点失效。
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

    @Unique
    private void quickcraft$ensureStashButton() {
        if (!QuickCraftConfigs.isQuickStashButtonVisible()) {
            if (this.quickcraft$stashButton != null) {
                this.quickcraft$stashButton.visible = false;
            }
            return;
        }
        if (this.quickcraft$stashButton == null || !this.children().contains(this.quickcraft$stashButton)) {
            this.quickcraft$stashButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("↑"), button ->
                            QuickStash.stashFromButton(this))
                    .dimensions(this.x + this.backgroundWidth - 30, this.y + 5, 12, 12)
                    .build());
        }
        this.quickcraft$stashButton.visible = true;
        this.quickcraft$stashButton.setX(this.x + this.backgroundWidth - 30);
        this.quickcraft$stashButton.setY(this.y + 5);
        this.quickcraft$stashButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                Text.translatable("quickcraft.button.quick_stash")));
    }
}
