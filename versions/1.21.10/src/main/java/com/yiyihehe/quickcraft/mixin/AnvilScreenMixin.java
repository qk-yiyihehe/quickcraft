package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.QuickThrow;
import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilRename;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 铁砧命名框会优先吃键盘输入，这里把 QuickCraft 命名快捷键拦下来。
 * 1.21.10+ 使用 KeyInput 封装输入。
 */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void quickcraft$consumeRenameHotkey(KeyInput input,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftAnvilRename.shouldConsumeRenameHotkeyInput()
                || QuickCraftAnvilRename.shouldConsumeRenameHotkeyKeyPress(input.key())
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
