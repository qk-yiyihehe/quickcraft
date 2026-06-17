package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 拿到 GuiSchematicVerifier 内部的 verifier 实例。
 * 供 QuickCraft 的界面 mixin 读取扩展后的容器校验状态。
 */
@Mixin(value = GuiSchematicVerifier.class, remap = false)
public interface LitematicaGuiSchematicVerifierAccessor {
    @Accessor("verifier")
    SchematicVerifier quickcraft$getVerifier();
}
