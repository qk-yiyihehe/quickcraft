package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取和改写 MerchantScreen 的当前配方索引与滚动偏移。
 * 供 QuickTrade 在自定义排序后同步原版界面状态。
 */
@Mixin(MerchantScreen.class)
public interface MerchantScreenAccessor {
    @Accessor("selectedIndex")
    int quickcraft$getSelectedIndex();

    @Accessor("selectedIndex")
    void quickcraft$setSelectedIndex(int value);

    @Accessor("indexStartOffset")
    int quickcraft$getIndexStartOffset();

    @Accessor("indexStartOffset")
    void quickcraft$setIndexStartOffset(int value);
}
