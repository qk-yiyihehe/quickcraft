package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickThrow;
import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilRename;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 原版铁砧名字输入框会把 Alt+C 里的字符写进名字，这里只在铁砧命名快捷键时吞字符。
 */
@Mixin(TextFieldWidget.class)
public abstract class AnvilRenameTextFieldMixin {
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void quickcraft$consumeRenameHotkeyChar(CharInput input,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftAnvilRename.shouldConsumeRenameHotkeyInput()
                || QuickCraftAnvilRename.consumePendingRenameHotkeyChar()
                || QuickThrow.shouldConsumeAnvilThrowHotkeyInput()) {
            cir.setReturnValue(true);
        }
    }
}
