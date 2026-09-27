package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 调用创造背包界面被保护的 slotClicked。
 * 供 QuickCraft 复用原版逻辑模拟创造模式槽位点击。
 */
@Mixin(CreativeModeInventoryScreen.class)
public interface CreativeInventoryScreenInvoker {
    @Invoker("slotClicked")
    void quickcraft$invokeOnMouseClick(Slot slot, int slotId, int button, ContainerInput actionType);
}
