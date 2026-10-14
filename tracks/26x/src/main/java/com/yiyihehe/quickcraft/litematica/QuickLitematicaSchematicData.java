package com.yiyihehe.quickcraft.litematica;

import com.mojang.serialization.Dynamic;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.HolderLookup;
import org.slf4j.LoggerFactory;
import java.util.HashSet;
import java.util.Set;

/** 在投影库存进入原生解析前补救遗留物品；失败保留原记录，写入方必须拒绝不完整库存。 */
public final class QuickLitematicaSchematicData {
    private static final int LAST_LEGACY_ITEM_VERSION = 3700; // 1.20.4，Count/tag 格式的最后正式版本。
    private static final Set<String> WARNED = new HashSet<>();

    private QuickLitematicaSchematicData() { }

    public static Tag repair(Tag input, int sourceVersion) {
        if (input instanceof CompoundTag compound) {
            CompoundTag result = compound;
            if (isLegacyItem(compound)) {
                if (compound.contains("count") || compound.contains("components")) {
                    warn(compound, "Conflicting legacy and current item formats");
                    return compound;
                }
                try {
                    int version = sourceVersion > 0 ? Math.min(sourceVersion, LAST_LEGACY_ITEM_VERSION) : LAST_LEGACY_ITEM_VERSION;
                    result = (CompoundTag) DataFixers.getDataFixer().update(References.ITEM_STACK,
                            new Dynamic<>(NbtOps.INSTANCE, compound.copy()), version, LitematicaSchematic.MINECRAFT_DATA_VERSION).getValue();
                    if (isLegacyItem(result)) { throw new IllegalArgumentException("Legacy item was not upgraded"); }
                    // Slot 属于容器记录，不属于 ItemStack 的协议。
                    if (compound.contains("Slot")) { result.put("Slot", compound.get("Slot").copy()); }
                } catch (RuntimeException exception) {
                    warn(compound, exception.getMessage());
                    return compound;
                }
            }
            for (String key : result.keySet()) {
                Tag value = result.get(key);
                if (value == null || key.equals("minecraft:custom_data")) { continue; }
                Tag repaired = repair(value, sourceVersion);
                if (repaired != value) {
                    if (result == compound) { result = compound.copy(); }
                    result.put(key, repaired);
                }
            }
            return result;
        }
        if (input instanceof ListTag list) {
            ListTag result = list;
            for (int i = 0; i < list.size(); i++) {
                Tag value = list.get(i);
                Tag repaired = repair(value, sourceVersion);
                if (repaired != value) {
                    if (result == list) { result = list.copy(); }
                    result.set(i, repaired);
                }
            }
            return result;
        }
        return input;
    }

    // 旧原理图可省略 id；从实际方块类型恢复，不能把所有库存猜成箱子，也不能覆盖已存在的类型。
    static CompoundTag prepareBlockEntity(CompoundTag input, BlockState state, BlockPos pos) {
        CompoundTag nbt = (CompoundTag) repair(input, LAST_LEGACY_ITEM_VERSION);
        if (!QuickLitematicaContainerMaterialsAccess.readString(nbt, "id").isEmpty()
                || !(state.getBlock() instanceof EntityBlock provider)) { return nbt; }
        var entity = provider.newBlockEntity(pos, state);
        if (entity == null) { return nbt; }
        var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
        if (id == null) { return nbt; }
        CompoundTag prepared = nbt.copy();
        prepared.putString("id", id.toString());
        return prepared;
    }

    public static Object repairLitematicaList(Object data, int sourceVersion) {
        ListTag list = data instanceof ListTag nbt ? nbt
                : fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt.toVanillaList((fi.dy.masa.malilib.util.data.tag.ListData) data);
        ListTag repaired = (ListTag) repair(list, sourceVersion);
        if (repaired == list) { return data; }
        return data instanceof ListTag ? repaired
                : fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt.fromVanillaList(repaired);
    }

    public static Object repairLitematicaCompound(Object data, int sourceVersion) {
        CompoundTag nbt = QuickLitematicaDataCompat.toVanillaNbt(data);
        CompoundTag repaired = (CompoundTag) repair(nbt, sourceVersion);
        if (repaired == nbt) { return data; }
        return data instanceof CompoundTag ? repaired
                : fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt.fromVanillaCompound(repaired);
    }

    public static ItemStack decodeItem(CompoundTag input, HolderLookup.Provider lookup) {
        CompoundTag nbt = (CompoundTag) repair(input, LAST_LEGACY_ITEM_VERSION);
        if (isLegacyItem(nbt) || nbt.contains("tag")) {
            warn(input, "Legacy item data remains unresolved");
            return ItemStack.EMPTY;
        }
        try {
            ItemStack stack = QuickLitematicaContainerMaterialsAccess.decodeCurrentItem(lookup, nbt);
            if (stack.isEmpty() && !isEmptyItem(input)) { warn(input, "ItemStack decode failed"); }
            return stack;
        } catch (RuntimeException exception) {
            warn(input, exception.getMessage());
            return ItemStack.EMPTY;
        }
    }

    static boolean isEmptyItem(CompoundTag nbt) {
        return nbt.isEmpty() || QuickLitematicaContainerMaterialsAccess.readString(nbt, "id").equals("minecraft:air");
    }

    private static boolean isLegacyItem(CompoundTag nbt) {
        return nbt.contains("id") && nbt.contains("Count") && !nbt.contains("Items")
                && !nbt.contains("x") && !nbt.contains("Pos");
    }

    static void unreadableInventory(CompoundTag nbt) { warn(nbt, "Invalid or duplicate inventory slot"); }

    private static void warn(CompoundTag nbt, String reason) {
        String id = QuickLitematicaContainerMaterialsAccess.readString(nbt, "id");
        synchronized (WARNED) {
            if (WARNED.contains(id) || WARNED.size() >= 64) { return; }
            WARNED.add(id);
        }
        LoggerFactory.getLogger(QuickLitematicaSchematicData.class).warn("Unresolved schematic item {}: {}; preserving original NBT", id, reason);
        var client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> InfoUtils.showGuiOrInGameMessage(MessageType.WARNING,
                    "quickcraft.litematica.error.container_data_incomplete", id));
        }
    }
}
