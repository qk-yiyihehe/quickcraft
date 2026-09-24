package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.crafting.QuickCraftActionButton;
import com.yiyihehe.quickcraft.crafting.QuickCraftBackpack;
import com.yiyihehe.quickcraft.crafting.QuickCraftStonecutter;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchOptionButton;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchRouter;
import com.yiyihehe.quickcraft.render.QuickContainerLockButton;
import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.glfw.GLFW;

/**
 * 给原版工作台添加合成、模式与产物装盒按钮；渲染时同步位置以跟随配方书对界面的偏移。
 * 两个标题栏按钮与产物槽旁的合成按钮分别受工作台功能和按钮显示配置控制。
 */
@Mixin(CraftingScreen.class)
public abstract class CraftActionButtonMixin extends AbstractContainerScreen<CraftingMenu> {
    @Unique
    private QuickCraftActionButton quickcraft$craftButton;

    @Unique
    private QuickCraftWorkbenchOptionButton quickcraft$modeButton;

    @Unique
    private QuickCraftWorkbenchOptionButton quickcraft$outputButton;

    protected CraftActionButtonMixin(CraftingMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void quickcraft$addCraftButton(CallbackInfo ci) {
        if (!QuickCraftConfigs.isWorkbenchQuickCraftFeatureEnabled()) {
            return;
        }

        this.quickcraft$modeButton = this.addRenderableWidget(new QuickCraftWorkbenchOptionButton(
                this.leftPos + 138, this.topPos + 2,
                Component.translatable("quickcraft.button.workbench_mode.normal.tooltip"),
                button -> QuickCraftWorkbenchRouter.toggleMode(),
                QuickDraggableButton.PositionKey.WORKBENCH_MODE,
                Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/workbench_normal_mode.png"),
                Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/workbench_shulker_mode.png"),
                QuickCraftConfigs::isWorkbenchQuickShulkerCraftEnabled,
                QuickCraftConfigs::isWorkbenchQuickShulkerCraftEnabled
        ));
        this.quickcraft$outputButton = this.addRenderableWidget(new QuickCraftWorkbenchOptionButton(
                this.leftPos + 156, this.topPos + 2,
                Component.translatable("quickcraft.button.workbench_output.disabled.tooltip"),
                button -> QuickCraftWorkbenchRouter.toggleOutputToShulker(),
                QuickDraggableButton.PositionKey.WORKBENCH_OUTPUT,
                Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/workbench_output_drop.png"),
                Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/workbench_output_shulker.png"),
                QuickCraftConfigs::isWorkbenchQuickCraftOutputToShulkerEnabled,
                () -> QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled()
                        && QuickCraftConfigs.isWorkbenchQuickCraftOutputToShulkerEnabled()
        ));
        if (!QuickCraftConfigs.isCraftActionButtonVisible()) {
            return;
        }

        // 按钮放到产物槽右下外侧，尽量不遮挡产物图标。
        int buttonX = this.leftPos + this.getMenu().getSlot(0).x + 13;
        int buttonY = this.topPos + this.getMenu().getSlot(0).y + 13;
        this.quickcraft$craftButton = this.addRenderableWidget(new QuickCraftActionButton(
                buttonX, buttonY,
                button -> QuickCraftWorkbenchRouter.handleCraftButton(QuickCraftKeyBindings.isAltDown()),
                QuickDraggableButton.PositionKey.WORKBENCH_CRAFT,
                Component.translatable("quickcraft.button.craft_action")
        ));
    }

    @Inject(method = "extractBackground", at = @At("HEAD"))
    private void quickcraft$syncCraftButton(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (QuickCraftWorkbenchRouter.shouldSuppressRecipeGhostSlots()) {
            CraftingScreen screen = (CraftingScreen) (Object) this;
            ((RecipeBookScreenAccessor) (Object) screen)
                    .quickcraft$getRecipeBook()
                    .slotClicked(screen.getMenu().getSlot(0));
        }
        if (this.quickcraft$craftButton != null) {
            this.quickcraft$craftButton.visible = QuickCraftConfigs.isWorkbenchQuickCraftFeatureEnabled()
                    && QuickCraftConfigs.isCraftActionButtonVisible();
            this.quickcraft$craftButton.setDefaultPosition(
                    this.leftPos + this.getMenu().getSlot(0).x + 13,
                    this.topPos + this.getMenu().getSlot(0).y + 13
            );
        }
        if (this.quickcraft$modeButton != null) {
            boolean shulkerMode = QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled();
            this.quickcraft$modeButton.visible = QuickCraftConfigs.isWorkbenchQuickCraftFeatureEnabled()
                    && !(this.width < 379 && ((RecipeBookScreenAccessor) (Object) this)
                    .quickcraft$getRecipeBook().isVisible());
            this.quickcraft$modeButton.active = !QuickCraftWorkbenchRouter.shouldSuppressRecipeGhostSlots();
            this.quickcraft$modeButton.setDefaultPosition(this.leftPos + 138, this.topPos + 2);
            Component modeTooltip = Component.translatable(shulkerMode
                    ? "quickcraft.button.workbench_mode.shulker.tooltip"
                    : "quickcraft.button.workbench_mode.normal.tooltip");
            this.quickcraft$modeButton.setMessage(modeTooltip);
            this.quickcraft$modeButton.setTooltip(Tooltip.create(modeTooltip));
            this.quickcraft$outputButton.visible = this.quickcraft$modeButton.visible;
            this.quickcraft$outputButton.active = shulkerMode && this.quickcraft$modeButton.active;
            this.quickcraft$outputButton.setDefaultPosition(this.leftPos + 156, this.topPos + 2);
            Component outputTooltip = Component.translatable(!shulkerMode
                    ? "quickcraft.button.workbench_output.disabled.tooltip"
                    : QuickCraftConfigs.isWorkbenchQuickCraftOutputToShulkerEnabled()
                    ? "quickcraft.button.workbench_output.shulker.tooltip"
                    : "quickcraft.button.workbench_output.drop.tooltip");
            this.quickcraft$outputButton.setMessage(outputTooltip);
            this.quickcraft$outputButton.setTooltip(Tooltip.create(outputTooltip));
        }
    }


}

/**
 * 给玩家背包 2x2 合成界面补一个快速合成小按钮。
 * 按钮点击后走 QuickCraftBackpack 的快速合成逻辑。
 */
@Mixin(InventoryScreen.class)
abstract class CraftActionButtonBackpackMixin extends AbstractContainerScreen<InventoryMenu> {
    @Unique
    private QuickCraftActionButton quickcraft$craftButton;

    @Unique
    private QuickContainerLockButton quickcraft$lockButton;

    protected CraftActionButtonBackpackMixin(InventoryMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void quickcraft$addCraftButton(CallbackInfo ci) {
        if (!QuickCraftConfigs.isBackpackQuickCraftEnabled()
                || !QuickCraftConfigs.isCraftActionButtonVisible()) {
            return;
        }

        // 按钮放到产物槽右下外侧，尽量不遮挡产物图标。
        int buttonX = this.leftPos + this.getMenu().getSlot(0).x + 13;
        int buttonY = this.topPos + this.getMenu().getSlot(0).y + 13;
        this.quickcraft$craftButton = this.addRenderableWidget(new QuickCraftActionButton(
                buttonX, buttonY,
                button -> QuickCraftBackpack.handleBackpackCraftButton(QuickCraftKeyBindings.isAltDown()),
                QuickDraggableButton.PositionKey.BACKPACK_CRAFT,
                Component.translatable("quickcraft.button.craft_action")
        ));
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void quickcraft$syncCraftButton(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (this.quickcraft$craftButton == null) {
            return;
        }

        this.quickcraft$craftButton.visible = QuickCraftConfigs.isBackpackQuickCraftEnabled()
                && QuickCraftConfigs.isCraftActionButtonVisible();
        this.quickcraft$craftButton.setDefaultPosition(
                this.leftPos + this.getMenu().getSlot(0).x + 13,
                this.topPos + this.getMenu().getSlot(0).y + 13
        );
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void quickcraft$addLockButton(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
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
        var client = this.minecraft;
        if (client == null) {
            return;
        }

        int buttonX = this.leftPos + this.imageWidth - 18;
        int buttonY = this.topPos + 65;
        this.quickcraft$lockButton = this.addRenderableWidget(new QuickContainerLockButton(buttonX, buttonY, button -> {
                    QuickContainerLock.toggleCurrentScreenLock(client, this);
                }, () -> QuickContainerLock.isCurrentScreenLocked(this),
                QuickDraggableButton.PositionKey.INVENTORY_LOCK,
                Component.translatable("quickcraft.button.container_lock.tooltip")));
        this.quickcraft$syncLockButtonPosition();
    }

    @Unique
    private void quickcraft$syncLockButtonPosition() {
        if (this.quickcraft$lockButton == null) {
            return;
        }

        this.quickcraft$lockButton.visible = true;
        // 背包界面右侧中部有一块空白边，锁按钮放这里避免挤在右上角。
        this.quickcraft$lockButton.setDefaultPosition(this.leftPos + this.imageWidth - 18, this.topPos + 65);
    }


}

/**
 * 给切石机界面补一个快速合成小按钮。
 * 按钮点击后走 QuickCraftStonecutter 的快速切石逻辑。
 */
@Mixin(StonecutterScreen.class)
abstract class CraftActionButtonStonecutterMixin extends AbstractContainerScreen<StonecutterMenu> {
    @Unique
    private QuickCraftActionButton quickcraft$craftButton;

    protected CraftActionButtonStonecutterMixin(StonecutterMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Inject(method = "extractBackground", at = @At("HEAD"))
    private void quickcraft$addCraftButton(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!QuickCraftConfigs.isStonecutterQuickCraftEnabled()
                || !QuickCraftConfigs.isCraftActionButtonVisible()) {
            if (this.quickcraft$craftButton != null) {
                this.quickcraft$craftButton.visible = false;
            }
            return;
        }

        // StonecutterScreen 没有覆写 init，这里在它的背景提取阶段懒加载按钮。
        if (this.quickcraft$craftButton != null && this.children().contains(this.quickcraft$craftButton)) {
            this.quickcraft$syncCraftButtonPosition();
            return;
        }

        // 按钮放到产物槽右下外侧，尽量不遮挡产物图标。
        int buttonX = this.leftPos + this.getMenu().getSlot(1).x + 13;
        int buttonY = this.topPos + this.getMenu().getSlot(1).y + 13;
        this.quickcraft$craftButton = this.addRenderableWidget(new QuickCraftActionButton(
                buttonX, buttonY,
                button -> QuickCraftStonecutter.handleStonecutterCraftButton(QuickCraftKeyBindings.isAltDown()),
                QuickDraggableButton.PositionKey.STONECUTTER_CRAFT,
                Component.translatable("quickcraft.button.craft_action")
        ));
        this.quickcraft$syncCraftButtonPosition();
    }

    @Unique

    private void quickcraft$syncCraftButtonPosition() {
        if (this.quickcraft$craftButton == null) {
            return;
        }

        this.quickcraft$craftButton.visible = true;
        this.quickcraft$craftButton.setDefaultPosition(
                this.leftPos + this.getMenu().getSlot(1).x + 13,
                this.topPos + this.getMenu().getSlot(1).y + 13
        );
    }
}
