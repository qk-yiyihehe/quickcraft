package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.BlockMismatchExtension;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ContainerMismatch;
import com.yiyihehe.quickcraft.litematica.QuickLitematicaContainerVerifier.ContainerMismatchKey;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import net.minecraft.inventory.Inventory;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * 给原版 BlockMismatch 实例挂接 QuickCraft 的容器错填附加数据。
 * 这样列表项、统计和悬浮预览都能复用同一份容器对比结果。
 */
@Mixin(value = BlockMismatch.class, remap = false)
public class LitematicaBlockMismatchMixin implements BlockMismatchExtension {
    @Unique
    private Pair<Inventory, Inventory> quickcraft$inventories;
    @Unique
    private ContainerMismatch quickcraft$containerMismatch;
    @Unique
    private ContainerMismatchKey quickcraft$containerMismatchKey;
    @Unique
    private Set<Integer> quickcraft$expectedDisabledSlots = Set.of();
    @Unique
    private Set<Integer> quickcraft$foundDisabledSlots = Set.of();

    @Override
    public void quickcraft$setInventories(Pair<Inventory, Inventory> inventories) {
        this.quickcraft$inventories = inventories;
    }

    @Override
    public Pair<Inventory, Inventory> quickcraft$getInventories() {
        return this.quickcraft$inventories;
    }

    @Override
    public void quickcraft$setContainerMismatch(ContainerMismatch mismatch) {
        this.quickcraft$containerMismatch = mismatch;
        this.quickcraft$setContainerMismatchKey(mismatch.key());
        this.quickcraft$setInventories(mismatch.inventories());
        this.quickcraft$setDisabledSlots(mismatch.expectedDisabledSlots(), mismatch.foundDisabledSlots());
    }

    @Override
    public ContainerMismatch quickcraft$getContainerMismatch() {
        return this.quickcraft$containerMismatch;
    }

    @Override
    public void quickcraft$setContainerMismatchKey(ContainerMismatchKey key) {
        this.quickcraft$containerMismatchKey = key;
    }

    @Override
    public ContainerMismatchKey quickcraft$getContainerMismatchKey() {
        return this.quickcraft$containerMismatchKey;
    }

    @Override
    public void quickcraft$setDisabledSlots(Set<Integer> expectedDisabledSlots, Set<Integer> foundDisabledSlots) {
        this.quickcraft$expectedDisabledSlots = Set.copyOf(expectedDisabledSlots);
        this.quickcraft$foundDisabledSlots = Set.copyOf(foundDisabledSlots);
    }

    @Override
    public Set<Integer> quickcraft$getExpectedDisabledSlots() {
        return this.quickcraft$expectedDisabledSlots;
    }

    @Override
    public Set<Integer> quickcraft$getFoundDisabledSlots() {
        return this.quickcraft$foundDisabledSlots;
    }

    @Inject(method = "hashCode", at = @At("HEAD"), cancellable = true)
    private void quickcraft$hashContainerMismatchKey(CallbackInfoReturnable<Integer> cir) {
        if (this.quickcraft$containerMismatchKey != null) {
            cir.setReturnValue(this.quickcraft$containerMismatchKey.hashCode());
        }
    }

    @Inject(method = "equals", at = @At("HEAD"), cancellable = true)
    private void quickcraft$equalsContainerMismatchKey(Object obj, CallbackInfoReturnable<Boolean> cir) {
        if (this.quickcraft$containerMismatchKey == null) {
            return;
        }
        if (!(obj instanceof BlockMismatchExtension extension)) {
            cir.setReturnValue(false);
            return;
        }

        cir.setReturnValue(this.quickcraft$containerMismatchKey.equals(extension.quickcraft$getContainerMismatchKey()));
    }
}
