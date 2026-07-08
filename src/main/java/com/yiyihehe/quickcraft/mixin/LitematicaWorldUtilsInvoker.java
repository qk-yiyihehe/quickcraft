package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 持续轻松放置调用 Litematica 私有放置动作的入口。
 *
 * <p>{@code WorldUtils.doEasyPlaceAction} 是 Litematica 1.21-1.21.1 内部方法，
 * 直接复用它可以保留原模组的方块选择、替换和方向处理。目标方法失效时，
 * QuickCraft 的长按轻松放置会无法编译或运行时 mixin 应用失败。</p>
 */
@Mixin(value = WorldUtils.class, remap = false)
public interface LitematicaWorldUtilsInvoker {
    @Invoker("doEasyPlaceAction")
    static ActionResult quickcraft$doEasyPlaceAction(MinecraftClient client) {
        throw new AssertionError();
    }
}
