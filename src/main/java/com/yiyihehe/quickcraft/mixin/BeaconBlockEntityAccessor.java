package com.yiyihehe.quickcraft.mixin;

import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * QuickBeacon 读取信标当前效果的 accessor。
 *
 * <p>1.21-1.21.1 的 {@link BeaconBlockEntity} 把主/副效果保存在私有字段里。
 * 自动配置信标前需要先确认当前状态，避免重复提交相同效果。</p>
 */
@Mixin(BeaconBlockEntity.class)
public interface BeaconBlockEntityAccessor {
    @Accessor("primary")
    RegistryEntry<StatusEffect> quickcraft$getPrimary();

    @Accessor("secondary")
    RegistryEntry<StatusEffect> quickcraft$getSecondary();
}
