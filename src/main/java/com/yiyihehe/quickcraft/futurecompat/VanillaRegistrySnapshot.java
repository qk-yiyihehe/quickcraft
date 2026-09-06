package com.yiyihehe.quickcraft.futurecompat;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public final class VanillaRegistrySnapshot implements RegistrySnapshot {
    private final RegistryAccess registries;

    public VanillaRegistrySnapshot(RegistryAccess registries) {
        this.registries = registries;
    }

    @Override
    public boolean hasBlock(String id) {
        return blockEntry(id).isPresent();
    }

    @Override
    public boolean hasItem(String id) {
        return this.registries.lookupOrThrow(Registries.ITEM)
                .getOptional(Identifier.parse(id)).isPresent();
    }

    @Override
    public boolean blockStateExists(String id, Map<String, String> properties) {
        Optional<Block> block = blockEntry(id);
        if (block.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Property<?> property = block.get().getStateDefinition().getProperty(entry.getKey());
            if (property == null || property.getValue(entry.getValue()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public java.util.Set<String> propertyValues(String id, String property) {
        Optional<Block> block = blockEntry(id);
        if (block.isEmpty()) {
            return null;
        }
        Property<?> resolved = block.get().getStateDefinition().getProperty(property);
        return resolved == null ? null : valuesFor(resolved);
    }

    private Optional<Block> blockEntry(String id) {
        return this.registries.lookupOrThrow(Registries.BLOCK).getOptional(Identifier.parse(id));
    }

    private static <T extends Comparable<T>> java.util.Set<String> valuesFor(Property<T> property) {
        java.util.Set<String> values = new java.util.LinkedHashSet<>();
        for (T value : property.getPossibleValues()) {
            values.add(property.getName(value));
        }
        return values;
    }
}
