package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickStash;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.render.QuickContainerLockButton;
import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import com.yiyihehe.quickcraft.render.QuickStashButton;
import com.yiyihehe.quickcraft.render.QuickRetrieveButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
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
    private QuickContainerLockButton quickcraft$lockButton;

    @Unique
    private QuickStashButton quickcraft$stashButton;

    @Unique
    private QuickRetrieveButton quickcraft$retrieveButton;

    protected GenericContainerScreenMixin(GenericContainerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void quickcraft$addLockButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        QuickContainerLock.bindCurrentScreen(this);
        this.quickcraft$ensureStashButton();
        this.quickcraft$ensureRetrieveButton();
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

        int buttonX = this.x + this.backgroundWidth - 18;
        int buttonY = this.y + 4;
        this.quickcraft$lockButton = this.addDrawableChild(new QuickContainerLockButton(buttonX, buttonY, button -> {
                    QuickContainerLock.toggleCurrentScreenLock(client, this);
                }, () -> QuickContainerLock.isCurrentScreenLocked(this),
                QuickDraggableButton.PositionKey.CONTAINER_LOCK,
                Text.translatable("quickcraft.button.container_lock.tooltip")));
        this.quickcraft$syncLockButtonPosition();
    }

    @Unique
    private void quickcraft$syncLockButtonPosition() {
        if (this.quickcraft$lockButton == null) {
            return;
        }

        this.quickcraft$lockButton.visible = true;
        this.quickcraft$lockButton.setDefaultPosition(this.x + this.backgroundWidth - 18, this.y + 4);
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
            this.quickcraft$stashButton = this.addDrawableChild(new QuickStashButton(
                    this.x + this.backgroundWidth - 34,
                    this.y + 4,
                    button -> QuickStash.stashFromButton(this),
                    QuickDraggableButton.PositionKey.QUICK_STASH,
                    Text.translatable("quickcraft.button.quick_stash")
            ));
        }
        this.quickcraft$stashButton.visible = true;
        this.quickcraft$stashButton.setDefaultPosition(this.x + this.backgroundWidth - 34, this.y + 4);
    }

    @Unique
    private void quickcraft$ensureRetrieveButton() {
        if (!QuickCraftConfigs.isQuickStashButtonVisible()) {
            if (this.quickcraft$retrieveButton != null) {
                this.quickcraft$retrieveButton.visible = false;
            }
            return;
        }
        if (this.quickcraft$retrieveButton == null || !this.children().contains(this.quickcraft$retrieveButton)) {
            this.quickcraft$retrieveButton = this.addDrawableChild(new QuickRetrieveButton(
                    this.x + this.backgroundWidth - 18,
                    this.y + this.backgroundHeight - 96,
                    button -> QuickStash.retrieveFromButton(this),
                    QuickDraggableButton.PositionKey.QUICK_RETRIEVE,
                    Text.translatable("quickcraft.button.quick_retrieve")
            ));
        }
        this.quickcraft$retrieveButton.visible = true;
        this.quickcraft$retrieveButton.setDefaultPosition(
                this.x + this.backgroundWidth - 18,
                this.y + this.backgroundHeight - 96
        );
    }
}
