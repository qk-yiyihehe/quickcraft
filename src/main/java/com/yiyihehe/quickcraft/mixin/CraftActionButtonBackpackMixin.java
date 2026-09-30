package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.crafting.QuickCraftActionButton;
import com.yiyihehe.quickcraft.crafting.QuickCraftBackpack;
import com.yiyihehe.quickcraft.render.QuickContainerLockButton;
import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给玩家背包 2x2 合成界面补一个快速合成小按钮。
 * 按钮点击后走 QuickCraftBackpack 的快速合成逻辑。
 */
@Mixin(InventoryScreen.class)
public abstract class CraftActionButtonBackpackMixin extends HandledScreen<PlayerScreenHandler> {
    @Unique
    private QuickCraftActionButton quickcraft$craftButton;

    @Unique
    private QuickContainerLockButton quickcraft$lockButton;

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
        this.quickcraft$craftButton = this.addDrawableChild(new QuickCraftActionButton(
                buttonX, buttonY,
                button -> QuickCraftBackpack.handleBackpackCraftButton(QuickCraftKeyBindings.isAltDown()),
                QuickDraggableButton.PositionKey.BACKPACK_CRAFT,
                Text.translatable("quickcraft.button.craft_action")
        ));
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void quickcraft$syncCraftButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (this.quickcraft$craftButton == null) {
            return;
        }

        this.quickcraft$craftButton.visible = QuickCraftConfigs.isBackpackQuickCraftEnabled()
                && QuickCraftConfigs.isCraftActionButtonVisible();
        this.quickcraft$craftButton.setDefaultPosition(
                this.x + this.getScreenHandler().getSlot(0).x + 13,
                this.y + this.getScreenHandler().getSlot(0).y + 13
        );
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

        int buttonX = this.x + this.backgroundWidth - 18;
        int buttonY = this.y + 65;
        this.quickcraft$lockButton = this.addDrawableChild(new QuickContainerLockButton(buttonX, buttonY, button -> {
                    QuickContainerLock.toggleCurrentScreenLock(client, this);
                }, () -> QuickContainerLock.isCurrentScreenLocked(this),
                QuickDraggableButton.PositionKey.INVENTORY_LOCK,
                Text.translatable("quickcraft.button.container_lock.tooltip")));
        this.quickcraft$syncLockButtonPosition();
    }

    @Unique
    private void quickcraft$syncLockButtonPosition() {
        if (this.quickcraft$lockButton == null) {
            return;
        }

        this.quickcraft$lockButton.visible = true;
        // 背包界面右侧中部有一块空白边，锁按钮放这里避免挤在右上角。
        this.quickcraft$lockButton.setDefaultPosition(this.x + this.backgroundWidth - 18, this.y + 65);
    }
}
