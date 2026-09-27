package com.yiyihehe.quickcraft.mixin;

import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取信标方块实体当前主效果和副效果。
 * 供 QuickBeacon 判断是否已经激活目标增益。
 */
@Mixin(BeaconBlockEntity.class)
public interface BeaconBlockEntityAccessor {
    @Accessor("primaryPower")
    Holder<MobEffect> quickcraft$getPrimary();

    @Accessor("secondaryPower")
    Holder<MobEffect> quickcraft$getSecondary();
}
