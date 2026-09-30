package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.crafting.QuickCraftActionButton;
import com.yiyihehe.quickcraft.crafting.QuickCraftStonecutter;
import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给切石机界面补一个快速合成小按钮。
 * 按钮点击后走 QuickCraftStonecutter 的快速切石逻辑。
 */
@Mixin(StonecutterScreen.class)
public abstract class CraftActionButtonStonecutterMixin extends HandledScreen<StonecutterScreenHandler> {
    @Unique
    private QuickCraftActionButton quickcraft$craftButton;

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
        this.quickcraft$craftButton = this.addDrawableChild(new QuickCraftActionButton(
                buttonX, buttonY,
                button -> QuickCraftStonecutter.handleStonecutterCraftButton(QuickCraftKeyBindings.isAltDown()),
                QuickDraggableButton.PositionKey.STONECUTTER_CRAFT,
                Text.translatable("quickcraft.button.craft_action")
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
                this.x + this.getScreenHandler().getSlot(1).x + 13,
                this.y + this.getScreenHandler().getSlot(1).y + 13
        );
    }
}
