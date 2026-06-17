package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.crafting.QuickCraftBackpack;
import com.yiyihehe.quickcraft.crafting.QuickCraftStonecutter;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbench;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给工作台界面补一个 Q 小按钮。
 * 按钮点击后走 QuickCraftWorkbench 的快速合成逻辑。
 */
@Mixin(CraftingScreen.class)
public abstract class CraftActionButtonMixin extends HandledScreen<CraftingScreenHandler> {
    @Unique
    private ButtonWidget quickcraft$craftButton;

    protected CraftActionButtonMixin(CraftingScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void quickcraft$addCraftButton(CallbackInfo ci) {
        if (!QuickCraftConfigs.isWorkbenchQuickCraftEnabled()
                || !QuickCraftConfigs.isCraftActionButtonVisible()) {
            return;
        }

        // 按钮放到产物槽右下外侧，尽量不遮挡产物图标。
        int buttonX = this.x + this.getScreenHandler().getSlot(0).x + 13;
        int buttonY = this.y + this.getScreenHandler().getSlot(0).y + 13;
        this.quickcraft$craftButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Q"), button ->
                QuickCraftWorkbench.handleWorkbenchCraftButton(Screen.hasAltDown()))
                .dimensions(buttonX, buttonY, 10, 10)
                .build());
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void quickcraft$syncCraftButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (this.quickcraft$craftButton == null) {
            return;
        }

        this.quickcraft$craftButton.visible = QuickCraftConfigs.isWorkbenchQuickCraftEnabled()
                && QuickCraftConfigs.isCraftActionButtonVisible();
        this.quickcraft$craftButton.setX(this.x + this.getScreenHandler().getSlot(0).x + 13);
        this.quickcraft$craftButton.setY(this.y + this.getScreenHandler().getSlot(0).y + 13);
    }
}

/**
 * 给玩家背包 2x2 合成界面补一个 Q 小按钮。
 * 按钮点击后走 QuickCraftBackpack 的快速合成逻辑。
 */
@Mixin(InventoryScreen.class)
abstract class CraftActionButtonBackpackMixin extends HandledScreen<PlayerScreenHandler> {
    @Unique
    private ButtonWidget quickcraft$craftButton;

    @Unique
    private ButtonWidget quickcraft$lockButton;

    protected CraftActionButtonBackpackMixin(PlayerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void quickcraft$addCraftButton(CallbackInfo ci) {
        if (!QuickCraftConfigs.isBackpackQuickCraftEnabled()
                || !QuickCraftConfigs.isCraftActionButtonVisible()) {
            return;
        }

        // 按钮放到产物槽右下外侧，尽量不遮挡产物图标。
        int buttonX = this.x + this.getScreenHandler().getSlot(0).x + 13;
        int buttonY = this.y + this.getScreenHandler().getSlot(0).y + 13;
        this.quickcraft$craftButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Q"), button ->
                QuickCraftBackpack.handleBackpackCraftButton(Screen.hasAltDown()))
                .dimensions(buttonX, buttonY, 10, 10)
                .build());
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void quickcraft$syncCraftButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (this.quickcraft$craftButton == null) {
            return;
        }

        this.quickcraft$craftButton.visible = QuickCraftConfigs.isBackpackQuickCraftEnabled()
                && QuickCraftConfigs.isCraftActionButtonVisible();
        this.quickcraft$craftButton.setX(this.x + this.getScreenHandler().getSlot(0).x + 13);
        this.quickcraft$craftButton.setY(this.y + this.getScreenHandler().getSlot(0).y + 13);
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
        // 背包界面右侧中部有一块空白边，锁按钮放这里避免挤在右上角。
        this.quickcraft$lockButton.setY(this.y + 66);
        this.quickcraft$lockButton.setMessage(QuickContainerLock.getLockButtonText(this));
    }
}

/**
 * 给切石机界面补一个 Q 小按钮。
 * 按钮点击后走 QuickCraftStonecutter 的快速切石逻辑。
 */
@Mixin(StonecutterScreen.class)
abstract class CraftActionButtonStonecutterMixin extends HandledScreen<StonecutterScreenHandler> {
    @Unique
    private ButtonWidget quickcraft$craftButton;

    protected CraftActionButtonStonecutterMixin(StonecutterScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void quickcraft$addCraftButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!QuickCraftConfigs.isStonecutterQuickCraftEnabled()
                || !QuickCraftConfigs.isCraftActionButtonVisible()) {
            if (this.quickcraft$craftButton != null) {
                this.quickcraft$craftButton.visible = false;
            }
            return;
        }

        // 1.21 的 StonecutterScreen 没有覆写 init，这里改为渲染时懒加载按钮。
        if (this.quickcraft$craftButton != null && this.children().contains(this.quickcraft$craftButton)) {
            this.quickcraft$syncCraftButtonPosition();
            return;
        }

        // 按钮放到产物槽右下外侧，尽量不遮挡产物图标。
        int buttonX = this.x + this.getScreenHandler().getSlot(1).x + 13;
        int buttonY = this.y + this.getScreenHandler().getSlot(1).y + 13;
        this.quickcraft$craftButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Q"), button ->
                QuickCraftStonecutter.handleStonecutterCraftButton(Screen.hasAltDown()))
                .dimensions(buttonX, buttonY, 10, 10)
                .build());
        this.quickcraft$syncCraftButtonPosition();
    }

    @Unique
    private void quickcraft$syncCraftButtonPosition() {
        if (this.quickcraft$craftButton == null) {
            return;
        }

        this.quickcraft$craftButton.visible = true;
        this.quickcraft$craftButton.setX(this.x + this.getScreenHandler().getSlot(1).x + 13);
        this.quickcraft$craftButton.setY(this.y + this.getScreenHandler().getSlot(1).y + 13);
    }
}
