package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaSchematicData;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.HashMap;
import java.util.Map;

/** 上游转换完成后仍可能留下旧库存；在方块实体和实体被游戏解析前补救。 */
@Mixin(value = LitematicaSchematic.class, remap = false)
public abstract class LitematicaSchematicDataMixin {
    @Inject(method = "convertEntities_to_1_20_5",
            at = @At("RETURN"), cancellable = true)
    private void quickcraft$repairList(CallbackInfoReturnable<Object> cir) {
        int version = ((LitematicaSchematic) (Object) this).getMetadata().getMinecraftDataVersion();
        Object list = cir.getReturnValue();
        Object repaired = QuickLitematicaSchematicData.repairLitematicaList(list, version);
        if (repaired != list) { cir.setReturnValue(repaired); }
    }

    @Inject(method = "convertTileEntities_to_1_20_5", at = @At("RETURN"), cancellable = true)
    private void quickcraft$repairInventory(CallbackInfoReturnable<Map<BlockPos, Object>> cir) {
        int version = ((LitematicaSchematic) (Object) this).getMetadata().getMinecraftDataVersion();
        Map<BlockPos, Object> source = cir.getReturnValue();
        Map<BlockPos, Object> repaired = source;
        for (var entry : source.entrySet()) {
            Object nbt = QuickLitematicaSchematicData.repairLitematicaCompound(entry.getValue(), version);
            if (nbt != entry.getValue()) {
                if (repaired == source) { repaired = new HashMap<>(source); }
                repaired.put(entry.getKey(), nbt);
            }
        }
        if (repaired != source) { cir.setReturnValue(repaired); }
    }
}
