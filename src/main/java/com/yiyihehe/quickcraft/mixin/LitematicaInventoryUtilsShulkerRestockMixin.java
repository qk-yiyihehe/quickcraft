package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaShulkerMaterialRestock;
import fi.dy.masa.litematica.util.InventoryUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管 Litematica 0.19.60 轻松放置的实际物品选取入口。
 * WorldUtils 已在此处解析出投影的准确材料；若调用点消失，投影命中时将不再触发潜影盒补料。
 */
@Mixin(value = InventoryUtils.class, remap = false)
public final class LitematicaInventoryUtilsShulkerRestockMixin {
    @Inject(method = "schematicWorldPickBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$restockMissingEasyPlaceMaterial(
            ItemStack stack,
            BlockPos position,
            World schematicWorld,
            MinecraftClient client,
            CallbackInfo ci
    ) {
        if (QuickLitematicaShulkerMaterialRestock.requestMissingMaterial(stack)) {
            ci.cancel();
        }
    }
}
