package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.util.EasyPlaceUtils;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 调用重写路径的单次放置；调用方必须维护 EasyPlaceUtils.isHandling，避免交互回调递归。 */
@Mixin(value = EasyPlaceUtils.class, remap = false)
public interface LitematicaEasyPlaceUtilsInvoker {
    @Invoker("handleEasyPlace")
    static InteractionResult quickcraft$handleEasyPlace() {
        throw new AssertionError();
    }
}
