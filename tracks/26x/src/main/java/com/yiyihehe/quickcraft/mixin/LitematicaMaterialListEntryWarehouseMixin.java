package com.yiyihehe.quickcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.yiyihehe.quickcraft.compat.voidwarehouse.QuickLitematicaVoidWarehouseMaterials;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaProjectionMaterials.WarehouseAvailability;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(value = MaterialListEntry.class, remap = false)
public abstract class LitematicaMaterialListEntryWarehouseMixin implements WarehouseAvailability {
    @Unique private ItemStack quickcraft$warehouseTarget;

    @Override
    public void quickcraft$setWarehouseTarget(ItemStack target) {
        this.quickcraft$warehouseTarget = target;
    }

    @Override
    public List<ItemStack> quickcraft$getWarehouseTargets() {
        var target = this.quickcraft$warehouseTarget;
        return target == null ? List.of() : List.of(target);
    }

    // 原版可独立刷新背包计数，异步回执到达后直接读取仓库快照。
    @ModifyReturnValue(method = "getCountAvailable", at = @At("RETURN"))
    private int quickcraft$includeWarehouseStock(int available) {
        var target = this.quickcraft$warehouseTarget;
        return target == null ? available : QuickLitematicaVoidWarehouseMaterials.addProjectionAvailable(available, target);
    }
}
