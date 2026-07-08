package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 原理图验证器界面持有的 {@link SchematicVerifier} accessor。
 *
 * <p>容器校验统计存在验证器实例上，而按钮和列表注入点只拿得到
 * {@link GuiSchematicVerifier}。字段变化时，容器分类按钮和统计文案会无法读取结果。</p>
 */
@Mixin(value = GuiSchematicVerifier.class, remap = false)
public interface LitematicaGuiSchematicVerifierAccessor {
    @Accessor("verifier")
    SchematicVerifier quickcraft$getVerifier();
}
