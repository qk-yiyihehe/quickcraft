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
 * 给 Litematica 原版 {@link BlockMismatch} 挂接容器内容差异。
 *
 * <p>验证器列表、HUD 选中项和悬浮库存预览都只认识 {@code BlockMismatch}。
 * QuickCraft 把容器差异附着到这个对象上，并在存在容器 key 时接管 equals/hashCode，
 * 避免同一个容器的不同槽位差异被原版“方块状态相同”逻辑合并。</p>
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
