package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.crafting.QuickCraftBackpack;
import com.yiyihehe.quickcraft.crafting.QuickCraftStonecutter;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchRouter;
import com.yiyihehe.quickcraft.render.QuickContainerLockButton;
import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.glfw.GLFW;

/**
 * 给工作台界面补一个 Q 小按钮。
 * 按钮点击后走 QuickCraftWorkbench 的快速合成逻辑。
 */
@Mixin(CraftingScreen.class)
public abstract class CraftActionButtonMixin extends AbstractContainerScreen<CraftingMenu> {
    @Unique
    private QuickDraggableButton quickcraft$craftButton;

    protected CraftActionButtonMixin(CraftingMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void quickcraft$addCraftButton(CallbackInfo ci) {
        if (!QuickCraftConfigs.isWorkbenchQuickCraftFeatureEnabled()
                || !QuickCraftConfigs.isCraftActionButtonVisible()) {
            return;
        }

        // 按钮放到产物槽右下外侧，尽量不遮挡产物图标。
        int buttonX = this.leftPos + this.getMenu().getSlot(0).x + 13;
        int buttonY = this.topPos + this.getMenu().getSlot(0).y + 13;
        this.quickcraft$craftButton = this.addRenderableWidget(new QuickDraggableButton(
                buttonX, buttonY, 10, 10, Component.literal("Q"),
                button -> QuickCraftWorkbenchRouter.handleCraftButton(quickcraft$isAltDown()),
                QuickDraggableButton.PositionKey.WORKBENCH_CRAFT
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
        if (this.quickcraft$craftButton == null) {
            return;
        }

        this.quickcraft$craftButton.visible = QuickCraftConfigs.isWorkbenchQuickCraftFeatureEnabled()
                && QuickCraftConfigs.isCraftActionButtonVisible();
        this.quickcraft$craftButton.setDefaultPosition(
                this.leftPos + this.getMenu().getSlot(0).x + 13,
                this.topPos + this.getMenu().getSlot(0).y + 13
        );
    }

    @Unique
    private static boolean quickcraft$isAltDown() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }

        long handle = client.getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }
}

/**
 * 给玩家背包 2x2 合成界面补一个 Q 小按钮。
 * 按钮点击后走 QuickCraftBackpack 的快速合成逻辑。
 */
@Mixin(InventoryScreen.class)
abstract class CraftActionButtonBackpackMixin extends AbstractContainerScreen<InventoryMenu> {
    @Unique
    private QuickDraggableButton quickcraft$craftButton;

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
        this.quickcraft$craftButton = this.addRenderableWidget(new QuickDraggableButton(
                buttonX, buttonY, 10, 10, Component.literal("Q"),
                button -> QuickCraftBackpack.handleBackpackCraftButton(quickcraft$isAltDown()),
                QuickDraggableButton.PositionKey.BACKPACK_CRAFT
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

    @Unique
    private static boolean quickcraft$isAltDown() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }

        long handle = client.getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }
}

/**
 * 给切石机界面补一个 Q 小按钮。
 * 按钮点击后走 QuickCraftStonecutter 的快速切石逻辑。
 */
@Mixin(StonecutterScreen.class)
abstract class CraftActionButtonStonecutterMixin extends AbstractContainerScreen<StonecutterMenu> {
    @Unique
    private QuickDraggableButton quickcraft$craftButton;

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
        this.quickcraft$craftButton = this.addRenderableWidget(new QuickDraggableButton(
                buttonX, buttonY, 10, 10, Component.literal("Q"),
                button -> QuickCraftStonecutter.handleStonecutterCraftButton(quickcraft$isAltDown()),
                QuickDraggableButton.PositionKey.STONECUTTER_CRAFT
        ));
        this.quickcraft$syncCraftButtonPosition();
    }

    @Unique
    private static boolean quickcraft$isAltDown() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }

        long handle = client.getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
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
