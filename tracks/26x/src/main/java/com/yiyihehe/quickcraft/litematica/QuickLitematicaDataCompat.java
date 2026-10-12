package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 声明范围内的 Litematica 同时存在原生 NBT 与 MaLiLib 数据标签接口。
 * 游戏 API 使用原生 NBT；复制、读写与实体构造在此按实际依赖协议转换。
 */
final class QuickLitematicaDataCompat {
    private static final Method ENTITY_NBT_METHOD = findMethod("nbt");
    private static final Field ENTITY_NBT_FIELD = ENTITY_NBT_METHOD == null ? findField("nbt") : null;
    private static final Method ENTITY_POS_METHOD = findMethod("posVec");
    private static final Field ENTITY_POS_FIELD = ENTITY_POS_METHOD == null ? findField("posVec") : null;
    private static final boolean USES_DATA_TAGS = ENTITY_NBT_METHOD != null
            ? ENTITY_NBT_METHOD.getReturnType() == CompoundData.class
            : ENTITY_NBT_FIELD != null && ENTITY_NBT_FIELD.getType() == CompoundData.class;
    private static final Method CREATE_ENTITY_NBT_METHOD = findEntityMethod("createEntityAndPassengersFromNBT", CompoundTag.class);
    private static final Method CREATE_ENTITY_DATA_METHOD = findEntityMethod("createEntityAndPassengersFromData", CompoundData.class);

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

    static Entity createEntity(CompoundTag nbt, Level world) {
        try {
            if (CREATE_ENTITY_NBT_METHOD != null) {
                return (Entity) CREATE_ENTITY_NBT_METHOD.invoke(null, nbt, world);
            }
            if (CREATE_ENTITY_DATA_METHOD != null) {
                return (Entity) CREATE_ENTITY_DATA_METHOD.invoke(null, DataConverterNbt.fromVanillaCompound(nbt), world);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to create Litematica preview entity", exception);
        }
        throw new IllegalStateException("Unsupported Litematica entity creation API");
    }

    // 同一声明范围同时包含旧版原生 NBT 与新版数据标签，写入必须跟随读取到的协议。
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void setBlockEntities(fi.dy.masa.litematica.schematic.LitematicaSchematic schematic,
                                 String region, java.util.Map<net.minecraft.core.BlockPos, CompoundTag> values) {
        java.util.Map nativeMap = schematic.getBlockEntityMapForRegion(region);
        if (nativeMap == null) { return; }
        nativeMap.clear();
        values.forEach((pos, nbt) -> nativeMap.put(pos, !USES_DATA_TAGS
                ? nbt : DataConverterNbt.fromVanillaCompound(nbt)));
    }

    static EntityInfo withEntityNbt(EntityInfo info, CompoundTag nbt) {
        try {
            if (!USES_DATA_TAGS) {
                return EntityInfo.class.getConstructor(Vec3.class, CompoundTag.class)
                        .newInstance(entityPos(info), nbt);
            }
            return EntityInfo.class.getConstructor(Vec3.class, CompoundData.class)
                    .newInstance(entityPos(info), DataConverterNbt.fromVanillaCompound(nbt));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to write Litematica EntityInfo NBT", exception);
        }
    }

    static CompoundTag schematicNbt(fi.dy.masa.litematica.schematic.LitematicaSchematic schematic) {
        try {
            Method write = findDataWriter(fi.dy.masa.litematica.schematic.LitematicaSchematic.class);
            return toVanillaNbt(write.invoke(schematic));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to export Litematica schematic data", exception);
        }
    }

    static fi.dy.masa.litematica.schematic.LitematicaSchematic copySchematic(
            fi.dy.masa.litematica.schematic.LitematicaSchematic schematic, java.nio.file.Path path) {
        try {
            CompoundTag nbt = schematicNbt(schematic).copy();
            Class<?> tagType = findDataWriter(fi.dy.masa.litematica.schematic.LitematicaSchematic.class).getReturnType();
            return fi.dy.masa.litematica.schematic.LitematicaSchematic.class
                    .getConstructor(java.nio.file.Path.class, tagType, fi.dy.masa.litematica.util.FileType.class)
                    .newInstance(path, tagType == CompoundTag.class ? nbt : DataConverterNbt.fromVanillaCompound(nbt),
                            fi.dy.masa.litematica.util.FileType.LITEMATICA_SCHEMATIC);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to copy Litematica schematic data", exception);
        }
    }

    static fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer copyBlockContainer(
            fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer original) {
        try {
            var palette = original.getPalette();
            var write = findDataWriter(fi.dy.masa.litematica.schematic.container.ILitematicaBlockStatePalette.class);
            Class<?> listType = write.getReturnType();
            var size = original.getSize();
            return (fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer)
                    fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer.class
                    .getMethod("createFrom", listType, long[].class, net.minecraft.core.BlockPos.class)
                    .invoke(null, write.invoke(palette), original.getBackingLongArray().clone(),
                            new net.minecraft.core.BlockPos(size.getX(), size.getY(), size.getZ()));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to copy Litematica block palette", exception);
        }
    }

    private static Method findDataWriter(Class<?> owner) throws NoSuchMethodException {
        try {
            return owner.getMethod("writeToData");
        } catch (NoSuchMethodException ignored) {
            return owner.getMethod("writeToNBT");
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

    private static Method findEntityMethod(String name, Class<?> dataType) {
        try {
            return EntityUtils.class.getMethod(name, dataType, Level.class);
        } catch (NoSuchMethodException ignored) {
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
