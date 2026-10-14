package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.ListData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

/** 1.21.11 声明的依赖范围同时包含原生 NBT 和 MaLiLib 数据标签接口。 */
final class QuickLitematicaSchematicDataAccess {
    private QuickLitematicaSchematicDataAccess() { }

    static Object repairList(Object data, int version) {
        NbtList list = data instanceof NbtList nbt ? nbt : DataConverterNbt.toVanillaList((ListData) data);
        NbtList repaired = (NbtList) QuickLitematicaSchematicData.repair(list, version);
        if (repaired == list) { return data; }
        return data instanceof NbtList ? repaired : DataConverterNbt.fromVanillaList(repaired);
    }

    static Object repairCompound(Object data, int version) {
        NbtCompound nbt = data instanceof NbtCompound compound ? compound : DataConverterNbt.toVanillaCompound((CompoundData) data);
        NbtCompound repaired = (NbtCompound) QuickLitematicaSchematicData.repair(nbt, version);
        if (repaired == nbt) { return data; }
        return data instanceof NbtCompound ? repaired : DataConverterNbt.fromVanillaCompound(repaired);
    }
}
