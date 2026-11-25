package com.yiyihehe.quickcraft.litematica;

import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaShulkerPickBlockTest {
    @Test
    void woodenSwordIsAllowedWhenOnlyToolFilterIsEnabled() {
        Inventory inventory = inventoryWith(new ItemStack(Items.WOODEN_SWORD));

        assertThat(select(inventory, List.of(0), 0, false, true)).isZero();
    }

    @Test
    void miningToolIsRejectedByToolFilter() {
        Inventory inventory = inventoryWith(new ItemStack(Items.WOODEN_PICKAXE));

        assertThat(select(inventory, List.of(0), 0, false, true)).isEqualTo(-1);
    }

    @Test
    void durabilityFilterStillRejectsDamageableItems() {
        Inventory inventory = inventoryWith(new ItemStack(Items.WOODEN_SWORD));

        assertThat(select(inventory, List.of(0), 0, true, true)).isEqualTo(-1);
    }

    @Test
    void laterAllowedSlotIsUsedWhenFirstConfiguredSlotHasMiningTool() {
        Inventory inventory = inventoryWith(new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.WOODEN_SWORD));

        assertThat(select(inventory, List.of(0, 1), 0, false, true)).isEqualTo(1);
    }

    @Test
    void noSuitableSlotReturnsMinusOne() {
        Inventory inventory = inventoryWith(new ItemStack(Items.SHULKER_BOX));

        assertThat(select(inventory, List.of(0), 0, false, true)).isEqualTo(-1);
    }

    private static int select(
            Inventory inventory,
            List<Integer> configuredSlots,
            int selectedSlot,
            boolean avoidDamageable,
            boolean avoidTools
    ) {
        return QuickLitematicaShulkerMaterialRestock.selectLitematicaPickBlockTarget(
                inventory,
                configuredSlots,
                selectedSlot,
                avoidDamageable,
                avoidTools
        );
    }

    private static Inventory inventoryWith(ItemStack... stacks) {
        Inventory inventory = new Inventory(
                mock(net.minecraft.world.entity.player.Player.class),
                new EntityEquipment()
        );
        for (int slot = 0; slot < stacks.length; slot++) {
            inventory.setItem(slot, stacks[slot]);
        }
        return inventory;
    }
}
