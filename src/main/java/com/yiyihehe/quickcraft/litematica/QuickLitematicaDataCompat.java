package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Litematica 0.28.5+ stores schematic block entity and entity data as MaLiLib data tags.
 * Minecraft APIs used by QuickCraft still consume vanilla NBT, so conversion stays at this boundary.
 */
final class QuickLitematicaDataCompat {
    private static final Method ENTITY_NBT_METHOD = findMethod("nbt");
    private static final Field ENTITY_NBT_FIELD = ENTITY_NBT_METHOD == null ? findField("nbt") : null;
    private static final Method ENTITY_POS_METHOD = findMethod("posVec");
    private static final Field ENTITY_POS_FIELD = ENTITY_POS_METHOD == null ? findField("posVec") : null;

    private QuickLitematicaDataCompat() {
    }

    static CompoundTag toVanillaNbt(Object data) {
        if (data instanceof CompoundTag nbt) {
            return nbt;
        }
        if (data instanceof CompoundData compoundData) {
            return DataConverterNbt.toVanillaCompound(compoundData);
        }
        throw new IllegalArgumentException("Unsupported Litematica NBT type: " + data.getClass().getName());
    }

    static CompoundTag entityNbt(EntityInfo info) {
        return toVanillaNbt(readEntityMember(info, ENTITY_NBT_METHOD, ENTITY_NBT_FIELD, "nbt"));
    }

    static Vec3 entityPos(EntityInfo info) {
        return (Vec3) readEntityMember(info, ENTITY_POS_METHOD, ENTITY_POS_FIELD, "posVec");
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
