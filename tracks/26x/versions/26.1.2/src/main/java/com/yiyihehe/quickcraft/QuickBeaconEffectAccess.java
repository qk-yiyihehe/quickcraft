package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;

/** 26.1.2 的信标效果能力：生命恢复始终使用二级信标组合。 */
final class QuickBeaconEffectAccess {
    private QuickBeaconEffectAccess() {
    }

    static boolean allowRegenerationTwo(Minecraft client) {
        return true;
    }

    static boolean isRegenerationOneName(String normalized) {
        return false;
    }

    static QuickBeacon.BeaconEffectTarget regenerationTarget(boolean allowRegenerationTwo) {
        return QuickBeacon.BeaconEffectTarget.levelTwo(MobEffects.REGENERATION);
    }
}
