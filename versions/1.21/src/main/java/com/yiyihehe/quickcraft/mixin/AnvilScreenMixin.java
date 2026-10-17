package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilAssistant;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import com.yiyihehe.quickcraft.gui.QuickCraftAnvilButton;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.yiyihehe.quickcraft.QuickThrow;
import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilRename;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 铁砧命名框会优先吃键盘输入，这里把 QuickCraft 命名快捷键拦下来。
 * 1.21–1.21.8 使用旧版 keyPressed(int, int, int) 签名。
 */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin extends HandledScreen<AnvilScreenHandler> {
    protected AnvilScreenMixin(AnvilScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    // init 由 ForgingScreen 实现；铁砧实际在 setup 创建命名框，随后追加按钮。
    @Inject(method = "setup", at = @At("TAIL"))
    private void quickcraft$addAnvilAssistant(CallbackInfo ci) {
        if (!QuickCraftConfigs.Crafting.ENABLE_ANVIL_ASSISTANT.getBooleanValue()) return;
        int buttonY = Math.max(2, this.y - 23);
        var action = this.addDrawableChild(new QuickCraftAnvilButton(this.x, buttonY,
                button -> QuickCraftAnvilAssistant.action(false)));
        QuickCraftAnvilAssistant.attach(action);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        return QuickCraftAnvilAssistant.scrollPlan(mouseX, mouseY, horizontal, vertical)
                || super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void quickcraft$consumeRenameHotkey(int keyCode,
                                                int scanCode,
                                                int modifiers,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftAnvilRename.shouldConsumeRenameHotkeyInput()
                || QuickCraftAnvilRename.shouldConsumeRenameHotkeyKeyPress(keyCode)
                || QuickThrow.shouldConsumeAnvilThrowHotkeyInput()) {
            cir.setReturnValue(true);
        }
    }

    // 只阻止本地补料重置目标名，仍保留原版的编辑状态和焦点更新。
    @WrapOperation(method = "onSlotUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/widget/TextFieldWidget;setText(Ljava/lang/String;)V"))
    private void quickcraft$keepBatchRenameName(TextFieldWidget field, String name, Operation<Void> original,
                                                ScreenHandler handler, int slotId, ItemStack stack) {
        if (!QuickCraftAnvilRename.isRenamingBatch(handler)) original.call(field, name);
    }
}
