package com.yiyihehe.quickcraft.mixin;

import com.yiyihehe.quickcraft.QuickContainerLock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.kyrptonaught.quickshulker.QuickShulkerMod", remap = false)
public abstract class QuickShulkerHandOpenMixin {
    // The first return accepts the client's use; the other returns handle the server or pass.
    @Inject(method = "lambda$onInitialize$0", at = @At(value = "RETURN", ordinal = 0))
    private static void quickcraft$prepareContainerLock(PlayerEntity player, World world, Hand hand,
                                                        CallbackInfoReturnable<?> cir) {
        QuickContainerLock.prepareQuickShulkerHandOpen(player, hand);
    }
}
