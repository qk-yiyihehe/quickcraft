package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Litematica 0.26.13+ stores schematic block entity and entity data as MaLiLib data tags.
 * Minecraft APIs used by QuickCraft still consume vanilla NBT, so conversion stays at this boundary.
 */
final class QuickLitematicaDataCompat {
    private static final Method ENTITY_NBT_METHOD = findMethod("nbt");
    private static final Field ENTITY_NBT_FIELD = ENTITY_NBT_METHOD == null ? findField("nbt") : null;
    private static final Method ENTITY_POS_METHOD = findMethod("posVec");
    private static final Field ENTITY_POS_FIELD = ENTITY_POS_METHOD == null ? findField("posVec") : null;
    private static final boolean USES_DATA_TAGS = ENTITY_NBT_METHOD != null
            ? ENTITY_NBT_METHOD.getReturnType() == CompoundData.class
            : ENTITY_NBT_FIELD != null && ENTITY_NBT_FIELD.getType() == CompoundData.class;

    private QuickLitematicaDataCompat() {
    }

    static NbtCompound toVanillaNbt(Object data) {
        if (data instanceof NbtCompound nbt) {
            return nbt;
        }
        if (data instanceof CompoundData compoundData) {
            return DataConverterNbt.toVanillaCompound(compoundData);
        }
        throw new IllegalArgumentException("Unsupported Litematica NBT type: " + data.getClass().getName());
    }

    static NbtCompound entityNbt(EntityInfo info) {
        return toVanillaNbt(readEntityMember(info, ENTITY_NBT_METHOD, ENTITY_NBT_FIELD, "nbt"));
    }

    static Vec3d entityPos(EntityInfo info) {
        return (Vec3d) readEntityMember(info, ENTITY_POS_METHOD, ENTITY_POS_FIELD, "posVec");
    }

    // 同一声明范围同时包含旧版原生 NBT 与新版数据标签，写入必须跟随读取到的协议。
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void setBlockEntities(fi.dy.masa.litematica.schematic.LitematicaSchematic schematic,
                                 String region, java.util.Map<net.minecraft.util.math.BlockPos, NbtCompound> values) {
        java.util.Map nativeMap = schematic.getBlockEntityMapForRegion(region);
        if (nativeMap == null) { return; }
        nativeMap.clear();
        values.forEach((pos, nbt) -> nativeMap.put(pos, !USES_DATA_TAGS
                ? nbt : DataConverterNbt.fromVanillaCompound(nbt)));
    }

    static EntityInfo withEntityNbt(EntityInfo info, NbtCompound nbt) {
        try {
            if (!USES_DATA_TAGS) {
                return EntityInfo.class.getConstructor(Vec3d.class, NbtCompound.class)
                        .newInstance(entityPos(info), nbt);
            }
            return EntityInfo.class.getConstructor(Vec3d.class, CompoundData.class)
                    .newInstance(entityPos(info), DataConverterNbt.fromVanillaCompound(nbt));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to write Litematica EntityInfo NBT", exception);
        }
    }

    private static Method findMethod(String name) {
        try {
            return EntityInfo.class.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Field findField(String name) {
        try {
            return EntityInfo.class.getField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static Object readEntityMember(EntityInfo info, Method method, Field field, String name) {
        try {
            if (method != null) {
                return method.invoke(info);
            }
            if (field != null) {
                return field.get(info);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read Litematica EntityInfo." + name, exception);
        }
        throw new IllegalStateException("Unsupported Litematica EntityInfo member: " + name);
    }
}
