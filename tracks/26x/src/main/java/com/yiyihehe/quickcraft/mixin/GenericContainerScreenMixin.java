package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.render.QuickContainerButtons;
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

// 目标类没有独立 init；沿用现有渲染入口，业务在同轨道共用。
@Mixin(ContainerScreen.class)
public abstract class GenericContainerScreenMixin extends AbstractContainerScreen<ChestMenu> {
    @Unique
    private final QuickContainerButtons quickcraft$buttons = new QuickContainerButtons();

    protected GenericContainerScreenMixin(ChestMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Inject(method = "extractBackground", at = @At("HEAD"))
    private void quickcraft$addLockButton(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.quickcraft$buttons.sync(this, this.minecraft, this.leftPos, this.topPos, this.imageWidth, this.imageHeight, this::addRenderableWidget);
    }
}
