package com.yiyihehe.quickcraft.litematica;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.MultifaceSpreadeableBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.EmptyBlockGetter;
import java.util.Arrays;
import java.util.HashSet;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.Property;

/** 比较完整状态定义和默认实体结构；候选资格不依赖实际容器是否装有物品。 */
public final class QuickLitematicaProjectionReplacementCompatibility {
    private static volatile Map<Item, List<Block>> itemBlocks;
    private QuickLitematicaProjectionReplacementCompatibility() { }

    public static boolean isAllowed(ItemStack source, ItemStack target) {
        if (source == null || target == null || source.isEmpty() || target.isEmpty()
                || !(source.getItem() instanceof BlockItem sourceItem)
                || !(target.getItem() instanceof BlockItem)) { return false; }
        return replacementState(sourceItem.getBlock().defaultBlockState(), target) != null;
    }

    public static BlockState replacementState(BlockState source, ItemStack target) {
        if (target == null || target.isEmpty() || !(target.getItem() instanceof BlockItem item)) { return null; }
        if (source.getBlock().asItem() == target.getItem()) { return source; }
        for (Block targetBlock : placedBlocks(item)) {
            BlockState result = targetBlock.defaultBlockState();
            if (!hasSameStateDefinition(source, result)) { continue; }
            for (Property<?> property : source.getProperties()) {
                Property<?> targetProperty = targetBlock.getStateDefinition().getProperty(property.getName());
                result = copyProperty(source, property, result, targetProperty);
                if (result == null) { break; }
            }
            if (result == null) { continue; }
            boolean full = hasFullOutline(source);
            if (full != hasFullOutline(result)) { continue; }
            if (!full && nonFullFamily(source.getBlock()) != nonFullFamily(targetBlock)) { continue; }
            if (hasCompatibleEntityStructure(source, result)) { return result; }
        }
        return null;
    }

    private static List<Block> placedBlocks(BlockItem item) {
        if (itemBlocks == null) {
            Map<Item, List<Block>> grouped = new HashMap<>();
            // 原版把挂墙和落地版本关联到同一物品；按这份注册关系匹配，不按方块 ID 猜测。
            for (Block block : BuiltInRegistries.BLOCK) {
                Item associated = block.asItem();
                if (associated instanceof BlockItem) {
                    grouped.computeIfAbsent(associated, ignored -> new ArrayList<>()).add(block);
                }
            }
            grouped.values().forEach(blocks -> {
                Block primary = ((BlockItem) blocks.getFirst().asItem()).getBlock();
                blocks.remove(primary);
                blocks.addFirst(primary);
            });
            itemBlocks = grouped;
        }
        return itemBlocks.getOrDefault(item, List.of(item.getBlock()));
    }

    static Class<?> nonFullFamily(Block block) {
        if (block instanceof StairBlock) { return StairBlock.class; }
        if (block instanceof SlabBlock) { return SlabBlock.class; }
        if (block instanceof CarpetBlock) { return CarpetBlock.class; }
        if (block instanceof ChestBlock) { return ChestBlock.class; }
        if (block instanceof DoorBlock) { return DoorBlock.class; }
        if (block instanceof TrapDoorBlock) { return TrapDoorBlock.class; }
        if (block instanceof TorchBlock) { return TorchBlock.class; }
        // 铜制氧化变体通过子类实现；归入已确认的结构类别，状态定义仍须完全兼容。
        if (block instanceof LanternBlock) { return LanternBlock.class; }
        if (block instanceof ChainBlock) { return ChainBlock.class; }
        if (block instanceof IronBarsBlock) { return IronBarsBlock.class; }
        if (block instanceof net.minecraft.world.level.block.CopperGolemStatueBlock) { return net.minecraft.world.level.block.CopperGolemStatueBlock.class; }
        if (block instanceof LightningRodBlock) { return LightningRodBlock.class; }
        // 未确认的子类不并入父类，保留活/死珊瑚、普通花/凋零玫瑰等特殊实现的区别。
        return block.getClass();
    }

    static boolean hasFullOutline(BlockState state) {
        // 多面附着物的无附着默认状态会返回整格外形；包覆多个面也不属于完整建筑方块。
        if (state.getBlock() instanceof MultifaceSpreadeableBlock || state.getBlock() instanceof VineBlock) { return false; }
        // 比较外形，不用碰撞、遮光或包围盒；灵魂沙外形完整，楼梯的包围盒虽满格却有缺口。
        return !Shapes.joinIsNotEmpty(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                Shapes.block(), BooleanOp.NOT_SAME);
    }

    static boolean hasSameStateDefinition(BlockState source, BlockState target) {
        if (source.getProperties().size() != target.getProperties().size()) { return false; }
        for (Property<?> property : source.getProperties()) {
            Property<?> other = target.getBlock().getStateDefinition().getProperty(property.getName());
            if (other == null || !hasSamePropertyDefinition(property, other)) { return false; }
        }
        return true;
    }

    static boolean hasSamePropertyDefinition(Property<?> source, Property<?> target) {
        return source.getName().equals(target.getName()) && valueNames(source).equals(valueNames(target));
    }

    private static <T extends Comparable<T>> java.util.Set<String> valueNames(Property<T> property) {
        java.util.Set<String> values = new HashSet<>();
        for (T value : property.getPossibleValues()) { values.add(property.getName(value)); }
        return values;
    }

    private static boolean hasCompatibleEntityStructure(BlockState source, BlockState target) {
        if (source.hasBlockEntity() != target.hasBlockEntity()) { return false; }
        if (!source.hasBlockEntity()) { return true; }
        var original = ((EntityBlock) source.getBlock()).newBlockEntity(BlockPos.ZERO, source);
        var replacement = ((EntityBlock) target.getBlock()).newBlockEntity(BlockPos.ZERO, target);
        if (original == null || replacement == null) { return false; }
        // 只接受同一实体实现及其继承类型，避免用空实体省略的 NBT 字段推断数据兼容。
        Class<?> sourceType = original.getClass();
        Class<?> targetType = replacement.getClass();
        if (!sourceType.isAssignableFrom(targetType) && !targetType.isAssignableFrom(sourceType)) { return false; }
        if ((original instanceof Container) != (replacement instanceof Container)) { return false; }
        if (original instanceof Container sourceInventory && replacement instanceof Container targetInventory
                && sourceInventory.getContainerSize() != targetInventory.getContainerSize()) { return false; }
        if ((original instanceof WorldlyContainer) != (replacement instanceof WorldlyContainer)) { return false; }
        if (original instanceof WorldlyContainer sourceInventory && replacement instanceof WorldlyContainer targetInventory) {
            for (Direction direction : Direction.values()) {
                if (!Arrays.equals(sourceInventory.getSlotsForFace(direction), targetInventory.getSlotsForFace(direction))) { return false; }
            }
        }
        return true;
    }

    private static <T extends Comparable<T>, U extends Comparable<U>> BlockState copyProperty(
            BlockState source, Property<T> property, BlockState target, Property<U> targetProperty) {
        return targetProperty.getValue(property.getName(source.getValue(property)))
                .map(value -> target.setValue(targetProperty, value)).orElse(null);
    }
}
