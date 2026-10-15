package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilAssistant;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.yiyihehe.quickcraft.QuickThrow;
import com.yiyihehe.quickcraft.crafting.QuickCraftAnvilRename;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 铁砧命名框会优先吃键盘输入，这里把 QuickCraft 命名快捷键拦下来。
 */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin extends AbstractContainerScreen<AnvilMenu> {
    protected AnvilScreenMixin(AnvilMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    // init 由 ItemCombinerScreen 实现；铁砧实际在 subInit 创建命名框，随后追加按钮。
    @Inject(method = "subInit", at = @At("TAIL"))
    private void quickcraft$addAnvilAssistant(CallbackInfo ci) {
        if (!QuickCraftConfigs.Crafting.ENABLE_ANVIL_ASSISTANT.getBooleanValue()) return;
        int buttonY = Math.max(2, this.topPos - 23);
        var action = this.addRenderableWidget(Button.builder(Component.empty(),
                button -> QuickCraftAnvilAssistant.action(false)).bounds(this.leftPos, buttonY, 176, 20).build());
        QuickCraftAnvilAssistant.attach(action);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void quickcraft$consumeRenameHotkey(KeyEvent input,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (QuickCraftAnvilRename.shouldConsumeRenameHotkeyInput()
                || QuickCraftAnvilRename.shouldConsumeRenameHotkeyKeyPress(input.key())
                || QuickThrow.shouldConsumeAnvilThrowHotkeyInput()) {
            cir.setReturnValue(true);
        }
    }

    // 只阻止本地补料重置目标名，仍保留原版的编辑状态和焦点更新。
    @WrapOperation(method = "slotChanged", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/EditBox;setValue(Ljava/lang/String;)V"))
    private void quickcraft$keepBatchRenameName(EditBox field, String name, Operation<Void> original,
                                                AbstractContainerMenu handler, int slotId, ItemStack stack) {
        if (!QuickCraftAnvilRename.isRenamingBatch(handler)) original.call(field, name);
    }
}
