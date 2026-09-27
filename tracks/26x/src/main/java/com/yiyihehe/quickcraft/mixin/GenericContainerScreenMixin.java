package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.QuickStash;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.render.QuickContainerLockButton;
import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import com.yiyihehe.quickcraft.render.QuickRetrieveButton;
import com.yiyihehe.quickcraft.render.QuickStashButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
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
    private QuickContainerLockButton quickcraft$lockButton;

    @Unique
    private QuickStashButton quickcraft$stashButton;

    @Unique
    private QuickRetrieveButton quickcraft$retrieveButton;

    protected GenericContainerScreenMixin(ChestMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Inject(method = "extractBackground", at = @At("HEAD"))
    private void quickcraft$addLockButton(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        QuickContainerLock.bindCurrentScreen(this);
        this.quickcraft$ensureStashButton();
        this.quickcraft$ensureRetrieveButton();
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

        int buttonX = this.leftPos + this.imageWidth - 18;
        int buttonY = this.topPos + 4;
        this.quickcraft$lockButton = this.addRenderableWidget(new QuickContainerLockButton(buttonX, buttonY, button -> {
                    QuickContainerLock.toggleCurrentScreenLock(client, this);
                }, () -> QuickContainerLock.isCurrentScreenLocked(this),
                QuickDraggableButton.PositionKey.CONTAINER_LOCK,
                Component.translatable("quickcraft.button.container_lock.tooltip")));
        this.quickcraft$syncLockButtonPosition();
    }

    @Unique
    private void quickcraft$syncLockButtonPosition() {
        if (this.quickcraft$lockButton == null) {
            return;
        }

        this.quickcraft$lockButton.visible = true;
        this.quickcraft$lockButton.setDefaultPosition(this.leftPos + this.imageWidth - 18, this.topPos + 4);
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
            this.quickcraft$stashButton = this.addRenderableWidget(new QuickStashButton(
                    this.leftPos + this.imageWidth - 34,
                    this.topPos + 4,
                    button -> QuickStash.stashFromButton(this),
                    QuickDraggableButton.PositionKey.QUICK_STASH,
                    Component.translatable("quickcraft.button.quick_stash")
            ));
        }
        this.quickcraft$stashButton.visible = true;
        this.quickcraft$stashButton.setDefaultPosition(this.leftPos + this.imageWidth - 34, this.topPos + 4);
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
            this.quickcraft$retrieveButton = this.addRenderableWidget(new QuickRetrieveButton(
                    this.leftPos + this.imageWidth - 18,
                    this.topPos + this.imageHeight - 96,
                    button -> QuickStash.retrieveFromButton(this),
                    QuickDraggableButton.PositionKey.QUICK_RETRIEVE,
                    Component.translatable("quickcraft.button.quick_retrieve")
            ));
        }
        this.quickcraft$retrieveButton.visible = true;
        this.quickcraft$retrieveButton.setDefaultPosition(
                this.leftPos + this.imageWidth - 18,
                this.topPos + this.imageHeight - 96
        );
    }
}
