package com.yiyihehe.quickcraft.litematica;

import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.BrewingStandBlock;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.inventory.Inventory;

final class QuickLitematicaContainerOverlayLayout {
    private QuickLitematicaContainerOverlayLayout() {
    }

    static InventoryOverlayKind getInventoryType(Inventory inventory, BlockState state) {
        if (state != null) {
            if (state.getBlock() instanceof AbstractFurnaceBlock) {
                return InventoryOverlayKind.FURNACE;
            }
            if (state.getBlock() instanceof BrewingStandBlock) {
                return InventoryOverlayKind.BREWING_STAND;
            }
            if (state.getBlock() instanceof CrafterBlock) {
                return InventoryOverlayKind.CRAFTER;
            }
            if (state.getBlock() instanceof DispenserBlock) {
                return InventoryOverlayKind.DISPENSER;
            }
            if (state.getBlock() instanceof HopperBlock) {
                return InventoryOverlayKind.HOPPER;
            }
        }

        return switch (inventory.size()) {
            case 3 -> InventoryOverlayKind.FURNACE;
            case 5 -> InventoryOverlayKind.HOPPER;
            case 9 -> InventoryOverlayKind.DISPENSER;
            case 27 -> InventoryOverlayKind.FIXED_27;
            case 54 -> InventoryOverlayKind.FIXED_54;
            default -> InventoryOverlayKind.GENERIC;
        };
    }

    static SlotPosition getInventoryOverlaySlotPosition(
            InventoryOverlayKind type,
            int xSlots,
            int ySlots,
            int slotsPerRow,
            int slot
    ) {
        if (type == InventoryOverlayKind.FURNACE) {
            return switch (slot) {
                case 0 -> new SlotPosition(xSlots + 8, ySlots + 8);
                case 1 -> new SlotPosition(xSlots + 8, ySlots + 44);
                case 2 -> new SlotPosition(xSlots + 68, ySlots + 26);
                default -> getGridSlotPosition(xSlots, ySlots, slotsPerRow, slot);
            };
        }

        if (type == InventoryOverlayKind.BREWING_STAND) {
            return switch (slot) {
                case 0 -> new SlotPosition(xSlots + 47, ySlots + 42);
                case 1 -> new SlotPosition(xSlots + 70, ySlots + 49);
                case 2 -> new SlotPosition(xSlots + 93, ySlots + 42);
                case 3 -> new SlotPosition(xSlots + 70, ySlots + 8);
                case 4 -> new SlotPosition(xSlots + 8, ySlots + 8);
                default -> getGridSlotPosition(xSlots, ySlots, slotsPerRow, slot);
            };
        }

        return getGridSlotPosition(xSlots, ySlots, slotsPerRow, slot);
    }

    private static SlotPosition getGridSlotPosition(int xSlots, int ySlots, int slotsPerRow, int slot) {
        return new SlotPosition(
                xSlots + (slot % slotsPerRow) * 18,
                ySlots + (slot / slotsPerRow) * 18
        );
    }

    enum InventoryOverlayKind {
        FURNACE,
        BREWING_STAND,
        CRAFTER,
        DISPENSER,
        HOPPER,
        FIXED_27,
        FIXED_54,
        GENERIC
    }

    record SlotPosition(int x, int y) {
    }
}
