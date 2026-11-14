package com.yiyihehe.quickcraft;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;

/** 1.21.5+ changes PlayerInventory's constructor; tests use the exact runtime signature. */
public final class QuickCraftTestInventories {
    private QuickCraftTestInventories() {
    }

    public static PlayerInventory newPlayerInventory(ClientPlayerEntity player) {
        try {
            return (PlayerInventory) PlayerInventory.class
                    .getConstructor(PlayerEntity.class)
                    .newInstance(player);
        } catch (NoSuchMethodException ignored) {
            try {
                Class<?> equipmentType = Class.forName("net.minecraft.entity.EntityEquipment");
                Object equipment = equipmentType.getConstructor().newInstance();
                return (PlayerInventory) PlayerInventory.class
                        .getConstructor(PlayerEntity.class, equipmentType)
                        .newInstance(player, equipment);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
