package com.yiyihehe.quickcraft.mixin;

import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取信标方块实体当前主效果和副效果。
 * 供 QuickBeacon 判断是否已经激活目标增益。
 */
@Mixin(BeaconBlockEntity.class)
public interface BeaconBlockEntityAccessor {
    @Accessor("primary")
    RegistryEntry<StatusEffect> quickcraft$getPrimary();

    @Accessor("secondary")
    RegistryEntry<StatusEffect> quickcraft$getSecondary();
}
