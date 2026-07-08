package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilRename;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 铁砧重命名快捷键的字符拦截层。
 *
 * <p>{@link TextFieldWidget#charTyped(char, int)} 在屏幕热键之后仍会接收可输入字符。
 * 若这里只拦 {@code keyPressed}，Alt+C 触发命名时仍可能把 {@code c} 写进铁砧名称。
 * 这个 mixin 只在 QuickCraft 确认要消费重命名热键时返回成功，避免影响普通文本输入。</p>
 */
@Mixin(TextFieldWidget.class)
public abstract class AnvilRenameTextFieldMixin {
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void quickcraft$consumeRenameHotkeyChar(char chr,
                                                   int modifiers,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftAnvilRename.shouldConsumeRenameHotkeyInput()) {
            cir.setReturnValue(true);
        }
    }
}
