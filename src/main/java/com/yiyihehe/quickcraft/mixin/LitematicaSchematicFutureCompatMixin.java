package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaPreview3D;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 未来版本 .litematic 兼容（Litematica 本体加载路径）。
 *
 * 放置投影、世界内投影渲染、材料清单等都由 Litematica 自己通过
 * {@code LitematicaSchematic.readFromNBT} 读取 .litematic；未知 id（例如未来版本的
 * iron_chain）会在 palette 解析时被原版静默变成 AIR。QuickCraft 的 3D 预览走的是自己
 * 的加载入口，覆盖不到这里，所以在本入口 HEAD 处先按白名单改写 NBT（与预览共用同一份
 * 逻辑、同一开关，幂等；开关关闭或未进世界时不做任何事）。
 */
@Mixin(value = LitematicaSchematic.class, remap = false)
public abstract class LitematicaSchematicFutureCompatMixin {
    @Inject(method = "readFromNBT", at = @At("HEAD"), remap = false)
    private void quickcraft$rewriteFutureLitematicIds(NbtCompound nbt, CallbackInfoReturnable<Boolean> cir) {
        QuickLitematicaPreview3D.rewriteSchematicNbtForFutureIds(nbt);
    }
}
