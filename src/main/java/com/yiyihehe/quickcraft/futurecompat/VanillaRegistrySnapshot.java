package com.yiyihehe.quickcraft.futurecompat;

import java.util.Map;
import java.util.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

public final class VanillaRegistrySnapshot implements RegistrySnapshot {
    private final DynamicRegistryManager registries;

    public VanillaRegistrySnapshot(DynamicRegistryManager registries) {
        this.registries = registries;
    }

    @Override
    public boolean hasBlock(String id) {
        return this.registries.getOrThrow(RegistryKeys.BLOCK)
                .getOptional(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(id))).isPresent();
    }

    @Override
    public boolean hasItem(String id) {
        return this.registries.getOrThrow(RegistryKeys.ITEM)
                .getOptional(RegistryKey.of(RegistryKeys.ITEM, Identifier.of(id))).isPresent();
    }

    @Override
    public boolean blockStateExists(String id, Map<String, String> properties) {
        Optional<? extends net.minecraft.registry.entry.RegistryEntry<Block>> block = this.registries.getOrThrow(RegistryKeys.BLOCK)
                .getOptional(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(id)));
        if (block.isEmpty()) {
            return false;
        }
        BlockState state = block.get().value().getDefaultState();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Property<?> property = block.get().value().getStateManager().getProperty(entry.getKey());
            if (property == null || property.parse(entry.getValue()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public java.util.Set<String> propertyValues(String id, String property) {
        Optional<? extends net.minecraft.registry.entry.RegistryEntry<Block>> block = this.registries.getOrThrow(RegistryKeys.BLOCK)
                .getOptional(RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(id)));
        if (block.isEmpty()) {
            return null;
        }
        Property<?> resolved = block.get().value().getStateManager().getProperty(property);
        return resolved == null ? null : valuesFor(resolved);
    }

    private static <T extends Comparable<T>> java.util.Set<String> valuesFor(Property<T> property) {
        java.util.Set<String> values = new java.util.LinkedHashSet<>();
        for (T value : property.getValues()) {
            values.add(property.name(value));
        }
        return values;
    }
}
