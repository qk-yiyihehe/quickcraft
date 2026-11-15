package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.render.QuickContainerButtons;
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

// 目标类没有独立 init；沿用现有渲染入口，业务在同轨道共用。
@Mixin(GenericContainerScreen.class)
public abstract class GenericContainerScreenMixin extends HandledScreen<GenericContainerScreenHandler> {
    @Unique
    private final QuickContainerButtons quickcraft$buttons = new QuickContainerButtons();

    protected GenericContainerScreenMixin(GenericContainerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void quickcraft$addLockButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.quickcraft$buttons.sync(this, this.client, this.x, this.y, this.backgroundWidth, this.backgroundHeight, this::addDrawableChild);
    }
}
