package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilRename;
import com.yiyihehe.quickcraft.QuickThrow;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
//#if MC>=12110
//$$ import net.minecraft.client.input.KeyInput;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 铁砧命名框会优先吃键盘输入，这里把 QuickCraft 命名快捷键拦下来。
 */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    //#if MC>=12110
    //$$ private void quickcraft$consumeRenameHotkey(KeyInput input,
    //#else
    private void quickcraft$consumeRenameHotkey(int keyCode,
                                                int scanCode,
                                                int modifiers,
    //#endif
                                                CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftAnvilRename.shouldConsumeRenameHotkeyInput()
                //#if MC>=12110
                //$$ || QuickCraftAnvilRename.shouldConsumeRenameHotkeyKeyPress(input.key())
                //#else
                || QuickCraftAnvilRename.shouldConsumeRenameHotkeyKeyPress(keyCode)
                //#endif
                || QuickThrow.shouldConsumeAnvilThrowHotkeyInput()) {
            cir.setReturnValue(true);
        }
    }
}
