package com.yiyihehe.quickcraft.mixin;

import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * QuickTrade 访问原版交易列表选择状态的 accessor。
 *
 * <p>交易收藏会重排显示顺序，但 {@link MerchantScreen} 内部仍用原版索引和滚动偏移驱动
 * 选中项、滚动条和同步包。字段名或语义变化时，交易排序最容易出现“显示项”和“实际交易项”错位。</p>
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
