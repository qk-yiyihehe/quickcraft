package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;

/** 26.2+ 的信标效果能力：多人服务器使用合法的一级生命恢复组合。 */
final class QuickBeaconEffectAccess {
    private QuickBeaconEffectAccess() {
    }

    static boolean allowRegenerationTwo(Minecraft client) {
        return !client.isMultiplayerServer();
    }

    static boolean isRegenerationOneName(String normalized) {
        return switch (normalized) {
            case "regeneration1", "regenerationi", "regen1", "regeni", "生命恢复1", "恢复1" -> true;
            default -> false;
        };
    }

    static QuickBeacon.BeaconEffectTarget regenerationTarget(boolean allowRegenerationTwo) {
        return QuickBeacon.BeaconEffectTarget.regeneration(allowRegenerationTwo);
    }
}
