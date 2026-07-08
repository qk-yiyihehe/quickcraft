package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilRename;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 铁砧屏幕的重命名快捷键拦截层。
 *
 * <p>铁砧界面会把按键优先交给名称输入框处理，因此在
 * {@link AnvilScreen#keyPressed(int, int, int)} 的入口消费 QuickCraft 热键。
 * 注入点失效时，表现通常是快捷键不触发，或输入框继续接收这次按键。</p>
 */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void quickcraft$consumeRenameHotkey(int keyCode,
                                                int scanCode,
                                                int modifiers,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftAnvilRename.shouldConsumeRenameHotkeyInput()) {
            cir.setReturnValue(true);
        }
    }
}
