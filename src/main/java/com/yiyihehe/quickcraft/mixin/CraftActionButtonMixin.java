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
 * 工作台快速合成按钮的界面注入。
 *
 * <p>按钮挂在 {@link CraftingScreen#init()} 末尾，确保原版槽位和子控件已经完成布局。
 * 渲染阶段只同步可见性和位置，避免材质包或窗口尺寸变化后按钮留在旧坐标。</p>
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

        // 产物槽是 16x16；+13 让 10x10 按钮压在右下角外沿，保留产物图标主体可见。
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
 * 玩家背包 2x2 合成按钮，以及背包界面的锁格按钮入口。
 *
 * <p>背包合成按钮和锁格按钮都依赖当前 {@link InventoryScreen} 的实时布局。
 * 注入 {@code render} 用于同步锁格绑定状态，注入点失效时通常表现为按钮不出现或锁格状态不随界面更新。</p>
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

        // 产物槽是 16x16；+13 让 10x10 按钮压在右下角外沿，保留产物图标主体可见。
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
        // 原版背包右侧中部在 1.21-1.21.1 保持空白，放这里不遮挡配方书和装备槽。
        this.quickcraft$lockButton.setY(this.y + 66);
        this.quickcraft$lockButton.setMessage(QuickContainerLock.getLockButtonText(this));
    }
}

/**
 * 切石机快速加工按钮的界面注入。
 *
 * <p>1.21-1.21.1 的 {@link StonecutterScreen} 没有稳定可用的自定义初始化入口，
 * 因此在渲染阶段懒加载按钮，并在每帧同步位置。注入失效时，切石机界面只会缺少 Q 按钮，
 * 不应影响原版切石交互。</p>
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

        // 1.21-1.21.1 的 StonecutterScreen 没有覆写 init，这里改为渲染时懒加载按钮。
        if (this.quickcraft$craftButton != null && this.children().contains(this.quickcraft$craftButton)) {
            this.quickcraft$syncCraftButtonPosition();
            return;
        }

        // 产物槽是 16x16；+13 让 10x10 按钮压在右下角外沿，保留产物图标主体可见。
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
