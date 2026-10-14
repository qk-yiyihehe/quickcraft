package com.yiyihehe.quickcraft.litematica;

import com.mojang.serialization.Dynamic;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.datafixer.Schemas;
import net.minecraft.datafixer.TypeReferences;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryWrapper;
import org.slf4j.LoggerFactory;
import java.util.HashSet;
import java.util.Set;

/** 在投影库存进入原生解析前补救遗留物品；失败保留原记录，写入方必须拒绝不完整库存。 */
public final class QuickLitematicaSchematicData {
    private static final int LAST_LEGACY_ITEM_VERSION = 3700; // 1.20.4，Count/tag 格式的最后正式版本。
    private static final Set<String> WARNED = new HashSet<>();

    private QuickLitematicaSchematicData() { }

    public static NbtElement repair(NbtElement input, int sourceVersion) {
        if (input instanceof NbtCompound compound) {
            NbtCompound result = compound;
            if (isLegacyItem(compound)) {
                if (compound.contains("count") || compound.contains("components")) {
                    warn(compound, "Conflicting legacy and current item formats");
                    return compound;
                }
                try {
                    int version = sourceVersion > 0 ? Math.min(sourceVersion, LAST_LEGACY_ITEM_VERSION) : LAST_LEGACY_ITEM_VERSION;
                    result = (NbtCompound) Schemas.getFixer().update(TypeReferences.ITEM_STACK,
                            new Dynamic<>(NbtOps.INSTANCE, compound.copy()), version, LitematicaSchematic.MINECRAFT_DATA_VERSION).getValue();
                    if (isLegacyItem(result)) { throw new IllegalArgumentException("Legacy item was not upgraded"); }
                    // Slot 属于容器记录，不属于 ItemStack 的协议。
                    if (compound.contains("Slot")) { result.put("Slot", compound.get("Slot").copy()); }
                } catch (RuntimeException exception) {
                    warn(compound, exception.getMessage());
                    return compound;
                }
            }
            for (String key : result.getKeys()) {
                NbtElement value = result.get(key);
                if (value == null || key.equals("minecraft:custom_data")) { continue; }
                NbtElement repaired = repair(value, sourceVersion);
                if (repaired != value) {
                    if (result == compound) { result = compound.copy(); }
                    result.put(key, repaired);
                }
            }
            return result;
        }
        if (input instanceof NbtList list) {
            NbtList result = list;
            for (int i = 0; i < list.size(); i++) {
                NbtElement value = list.get(i);
                NbtElement repaired = repair(value, sourceVersion);
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
    static NbtCompound prepareBlockEntity(NbtCompound input, BlockState state, BlockPos pos) {
        NbtCompound nbt = (NbtCompound) repair(input, LAST_LEGACY_ITEM_VERSION);
        if (!QuickLitematicaContainerMaterialsAccess.readString(nbt, "id").isEmpty()
                || !(state.getBlock() instanceof BlockEntityProvider provider)) { return nbt; }
        var entity = provider.createBlockEntity(pos, state);
        if (entity == null) { return nbt; }
        var id = Registries.BLOCK_ENTITY_TYPE.getId(entity.getType());
        if (id == null) { return nbt; }
        NbtCompound prepared = nbt.copy();
        prepared.putString("id", id.toString());
        return prepared;
    }

    public static Object repairLitematicaList(Object data, int version) {
        return QuickLitematicaSchematicDataAccess.repairList(data, version);
    }

    public static Object repairLitematicaCompound(Object data, int version) {
        return QuickLitematicaSchematicDataAccess.repairCompound(data, version);
    }

    public static ItemStack decodeItem(NbtCompound input, RegistryWrapper.WrapperLookup lookup) {
        NbtCompound nbt = (NbtCompound) repair(input, LAST_LEGACY_ITEM_VERSION);
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

    static boolean isEmptyItem(NbtCompound nbt) {
        return nbt.isEmpty() || QuickLitematicaContainerMaterialsAccess.readString(nbt, "id").equals("minecraft:air");
    }

    private static boolean isLegacyItem(NbtCompound nbt) {
        return nbt.contains("id") && nbt.contains("Count") && !nbt.contains("Items")
                && !nbt.contains("x") && !nbt.contains("Pos");
    }

    static void unreadableInventory(NbtCompound nbt) { warn(nbt, "Invalid or duplicate inventory slot"); }

    private static void warn(NbtCompound nbt, String reason) {
        String id = QuickLitematicaContainerMaterialsAccess.readString(nbt, "id");
        synchronized (WARNED) {
            if (WARNED.contains(id) || WARNED.size() >= 64) { return; }
            WARNED.add(id);
        }
        LoggerFactory.getLogger(QuickLitematicaSchematicData.class).warn("Unresolved schematic item {}: {}; preserving original NBT", id, reason);
        var client = MinecraftClient.getInstance();
        if (client != null) {
            client.execute(() -> InfoUtils.showGuiOrInGameMessage(MessageType.WARNING,
                    "quickcraft.litematica.error.container_data_incomplete", id));
        }
    }
}
