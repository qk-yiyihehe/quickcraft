package com.yiyihehe.quickcraft.litematica;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

/** Litematica 的原生 NBT 转换入口。 */
final class QuickLitematicaSchematicDataAccess {
    private QuickLitematicaSchematicDataAccess() { }

    static Object repairList(Object data, int version) {
        return QuickLitematicaSchematicData.repair((NbtList) data, version);
    }

    static Object repairCompound(Object data, int version) {
        return QuickLitematicaSchematicData.repair((NbtCompound) data, version);
    }
}
