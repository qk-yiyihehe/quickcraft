package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BundleItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 在创造模式中按主手和副手组合生成装满内容的物品容器。
 */
public final class QuickCreativePacking {
    // 输入深度为 1 时生成结果最多两层；更深层会按容器槽位数指数放大物品组件数据。
    private static final int MAX_SAFE_INPUT_NESTING_DEPTH = 1;

    private QuickCreativePacking() {
    }

    public static boolean handleHotkey(MinecraftClient client) {
        if (!QuickCraftConfigs.isCreativePackingEnabled()
                || client == null
                || client.player == null
                || client.interactionManager == null) {
            return false;
        }

        ClientPlayerEntity player = client.player;
        if (!player.isCreative()) {
            sendError(player, "not_creative");
            return false;
        }

        ItemStack mainHand = player.getMainHandStack();
        if (mainHand.isEmpty()) {
            sendError(player, "no_main_hand_item");
            return false;
        }
        int nestingDepth = getContentsNestingDepth(mainHand);
        if (!QuickCraftConfigs.areCreativePackingNestedContainersAllowed() && nestingDepth > 0) {
            sendError(player, "nested_containers_disabled");
            return false;
        }
        if (nestingDepth > MAX_SAFE_INPUT_NESTING_DEPTH) {
            sendError(player, "nesting_depth_limit");
            return false;
        }

        ItemStack result = createPackedStack(client, mainHand, player.getOffHandStack());
        int selectedSlot = player.getInventory().getSelectedSlot();
        player.getInventory().setStack(selectedSlot, result.copy());
        client.interactionManager.clickCreativeStack(result, 36 + selectedSlot);
        player.playerScreenHandler.sendContentUpdates();
        return true;
    }

    private static ItemStack createPackedStack(MinecraftClient client, ItemStack mainHand, ItemStack offHand) {
        ItemStack fullStack = hasContents(mainHand) ? mainHand.copy() : mainHand.copyWithCount(mainHand.getMaxCount());
        if (isShulkerBox(mainHand)) {
            return packIntoOffHandOrFallback(client, fullStack, offHand, QuickCreativePacking::fillChest);
        }

        return packIntoOffHandOrFallback(client, fullStack, offHand, QuickCreativePacking::fillShulker);
    }

    private static ItemStack packIntoOffHandOrFallback(
            MinecraftClient client,
            ItemStack contents,
            ItemStack offHand,
            PackingFallback fallback
    ) {
        if (offHand.isEmpty()) {
            return fallback.create(client, contents);
        }
        if (offHand.getItem() instanceof BundleItem) {
            return fillBundle(contents);
        }
        if (offHand.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof BlockEntityProvider provider) {
            BlockEntity blockEntity = provider.createBlockEntity(BlockPos.ORIGIN, blockItem.getBlock().getDefaultState());
            if (blockEntity instanceof LootableContainerBlockEntity container) {
                return fillContainer(client, contents, blockItem, container);
            }
        }
        return fallback.create(client, contents);
    }

    private static ItemStack fillShulker(MinecraftClient client, ItemStack contents) {
        Block block = ShulkerBoxBlock.get((DyeColor) null);
        return fillContainer(
                client,
                contents,
                block.asItem(),
                new ShulkerBoxBlockEntity(BlockPos.ORIGIN, block.getDefaultState())
        );
    }

    private static ItemStack fillChest(MinecraftClient client, ItemStack contents) {
        Block chest = Blocks.CHEST;
        return fillContainer(
                client,
                contents,
                chest.asItem(),
                new ChestBlockEntity(BlockPos.ORIGIN, chest.getDefaultState())
        );
    }

    private static ItemStack fillContainer(
            MinecraftClient client,
            ItemStack contents,
            Item containerItem,
            LootableContainerBlockEntity container
    ) {
        List<ItemStack> stacks = new ArrayList<>(container.size());
        for (int slot = 0; slot < container.size(); slot++) {
            stacks.add(contents.copy());
        }

        ItemStack result = containerItem.getDefaultStack();
        result.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(stacks));
        return result;
    }

    private static ItemStack fillBundle(ItemStack contents) {
        BundleContentsComponent.Builder builder = new BundleContentsComponent.Builder(BundleContentsComponent.DEFAULT);
        for (int stack = 0; stack < QuickCraftConfigs.getCreativePackingBundleStacks(); stack++) {
            ItemStack remaining = contents.copy();
            if (builder.add(remaining) == 0) {
                break;
            }
        }

        ItemStack bundle = Items.BUNDLE.getDefaultStack();
        bundle.set(DataComponentTypes.BUNDLE_CONTENTS, builder.build());
        return bundle;
    }

    private static boolean hasContents(ItemStack stack) {
        return hasContainerContents(stack)
                || !stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT).isEmpty();
    }

    private static boolean hasContainerContents(ItemStack stack) {
        ContainerComponent contents = stack.get(DataComponentTypes.CONTAINER);
        return contents != null && contents.iterateNonEmpty().iterator().hasNext();
    }

    private static int getContentsNestingDepth(ItemStack stack) {
        int depth = 0;

        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null && container.iterateNonEmpty().iterator().hasNext()) {
            depth = 1;
            for (ItemStack nested : container.iterateNonEmpty()) {
                depth = Math.max(depth, 1 + getContentsNestingDepth(nested));
            }
        }

        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) {
            depth = Math.max(depth, 1);
            for (ItemStack nested : bundle.iterate()) {
                depth = Math.max(depth, 1 + getContentsNestingDepth(nested));
            }
        }

        return depth;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static void sendError(ClientPlayerEntity player, String key) {
        player.sendMessage(Text.translatable("quickcraft.message.creative_packing." + key).formatted(Formatting.RED), true);
    }

    @FunctionalInterface
    private interface PackingFallback {
        ItemStack create(MinecraftClient client, ItemStack contents);
    }
}
