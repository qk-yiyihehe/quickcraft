package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.crafting.QuickCraftActionButton;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchOptionButton;
import com.yiyihehe.quickcraft.crafting.QuickCraftWorkbenchRouter;
import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.3+ 给原版工作台添加合成、模式与产物装盒按钮；渲染时同步位置以跟随配方书对界面的偏移。
 */
@Mixin(CraftingScreen.class)
public abstract class CraftActionButtonMixin extends HandledScreen<CraftingScreenHandler> {
    @Unique
    private QuickCraftActionButton quickcraft$craftButton;

    @Unique
    private QuickCraftWorkbenchOptionButton quickcraft$modeButton;

    @Unique
    private QuickCraftWorkbenchOptionButton quickcraft$outputButton;

    protected CraftActionButtonMixin(CraftingScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void quickcraft$addCraftButton(CallbackInfo ci) {
        if (!QuickCraftConfigs.isWorkbenchQuickCraftFeatureEnabled()) {
            return;
        }

        this.quickcraft$modeButton = this.addDrawableChild(new QuickCraftWorkbenchOptionButton(
                this.x + 138, this.y + 2,
                Text.translatable("quickcraft.button.workbench_mode.normal.tooltip"),
                button -> QuickCraftWorkbenchRouter.toggleMode(),
                QuickDraggableButton.PositionKey.WORKBENCH_MODE,
                Identifier.of("quickcraft", "textures/gui/workbench_normal_mode.png"),
                Identifier.of("quickcraft", "textures/gui/workbench_shulker_mode.png"),
                QuickCraftConfigs::isWorkbenchQuickShulkerCraftEnabled,
                QuickCraftConfigs::isWorkbenchQuickShulkerCraftEnabled
        ));
        this.quickcraft$outputButton = this.addDrawableChild(new QuickCraftWorkbenchOptionButton(
                this.x + 156, this.y + 2,
                Text.translatable("quickcraft.button.workbench_output.disabled.tooltip"),
                button -> QuickCraftWorkbenchRouter.toggleOutputToShulker(),
                QuickDraggableButton.PositionKey.WORKBENCH_OUTPUT,
                Identifier.of("quickcraft", "textures/gui/workbench_output_drop.png"),
                Identifier.of("quickcraft", "textures/gui/workbench_output_shulker.png"),
                QuickCraftConfigs::isWorkbenchQuickCraftOutputToShulkerEnabled,
                () -> QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled()
                        && QuickCraftConfigs.isWorkbenchQuickCraftOutputToShulkerEnabled()
        ));
        if (!QuickCraftConfigs.isCraftActionButtonVisible()) {
            return;
        }

        int buttonX = this.x + this.getScreenHandler().getSlot(0).x + 13;
        int buttonY = this.y + this.getScreenHandler().getSlot(0).y + 13;
        this.quickcraft$craftButton = this.addDrawableChild(new QuickCraftActionButton(
                buttonX, buttonY,
                button -> QuickCraftWorkbenchRouter.handleCraftButton(QuickCraftKeyBindings.isAltDown()),
                QuickDraggableButton.PositionKey.WORKBENCH_CRAFT,
                Text.translatable("quickcraft.button.craft_action")
        ));
    }

    @Inject(method = "drawBackground", at = @At("HEAD"))
    private void quickcraft$syncCraftButton(DrawContext context, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        if (QuickCraftWorkbenchRouter.shouldSuppressRecipeGhostSlots()) {
            CraftingScreen screen = (CraftingScreen) (Object) this;
            ((RecipeBookScreenAccessor) (Object) screen)
                    .quickcraft$getRecipeBook()
                    .onMouseClick(screen.getScreenHandler().getSlot(0));
        }
        if (this.quickcraft$craftButton != null) {
            this.quickcraft$craftButton.visible = QuickCraftConfigs.isWorkbenchQuickCraftFeatureEnabled()
                    && QuickCraftConfigs.isCraftActionButtonVisible();
            this.quickcraft$craftButton.setDefaultPosition(
                    this.x + this.getScreenHandler().getSlot(0).x + 13,
                    this.y + this.getScreenHandler().getSlot(0).y + 13
            );
        }
        if (this.quickcraft$modeButton != null) {
            boolean shulkerMode = QuickCraftConfigs.isWorkbenchQuickShulkerCraftEnabled();
            this.quickcraft$modeButton.visible = QuickCraftConfigs.isWorkbenchQuickCraftFeatureEnabled()
                    && !(this.width < 379 && ((RecipeBookScreenAccessor) (Object) this).quickcraft$getRecipeBook().isOpen());
            this.quickcraft$modeButton.active = !QuickCraftWorkbenchRouter.shouldSuppressRecipeGhostSlots();
            this.quickcraft$modeButton.setDefaultPosition(this.x + 138, this.y + 2);
            Text modeTooltip = Text.translatable(shulkerMode
                    ? "quickcraft.button.workbench_mode.shulker.tooltip"
                    : "quickcraft.button.workbench_mode.normal.tooltip");
            this.quickcraft$modeButton.setMessage(modeTooltip);
            this.quickcraft$modeButton.setTooltip(Tooltip.of(modeTooltip));
            this.quickcraft$outputButton.visible = this.quickcraft$modeButton.visible;
            this.quickcraft$outputButton.active = shulkerMode && this.quickcraft$modeButton.active;
            this.quickcraft$outputButton.setDefaultPosition(this.x + 156, this.y + 2);
            Text outputTooltip = Text.translatable(!shulkerMode
                    ? "quickcraft.button.workbench_output.disabled.tooltip"
                    : QuickCraftConfigs.isWorkbenchQuickCraftOutputToShulkerEnabled()
                    ? "quickcraft.button.workbench_output.shulker.tooltip"
                    : "quickcraft.button.workbench_output.drop.tooltip");
            this.quickcraft$outputButton.setMessage(outputTooltip);
            this.quickcraft$outputButton.setTooltip(Tooltip.of(outputTooltip));
        }
    }
}
