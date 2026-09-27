package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取和改写 MerchantScreen 的当前配方索引与滚动偏移。
 * 供 QuickTrade 在自定义排序后同步原版界面状态。
 */
@Mixin(MerchantScreen.class)
public interface MerchantScreenAccessor {
    @Accessor("shopItem")
    int quickcraft$getSelectedIndex();

    @Accessor("shopItem")
    void quickcraft$setSelectedIndex(int value);

    @Accessor("scrollOff")
    int quickcraft$getIndexStartOffset();

    @Accessor("scrollOff")
    void quickcraft$setIndexStartOffset(int value);
}
