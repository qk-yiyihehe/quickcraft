package com.yiyihehe.quickcraft;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

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

    public static boolean handleHotkey(Minecraft client) {
        if (!QuickCraftConfigs.isCreativePackingEnabled()
                || client == null
                || client.player == null
                || client.gameMode == null) {
            return false;
        }

        LocalPlayer player = client.player;
        if (!player.hasInfiniteMaterials()) {
            sendError(player, "not_creative");
            return false;
        }

        ItemStack mainHand = player.getMainHandItem();
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

        ItemStack result = createPackedStack(client, mainHand, player.getOffhandItem());
        int selectedSlot = player.getInventory().getSelectedSlot();
        player.getInventory().setItem(selectedSlot, result.copy());
        client.gameMode.handleCreativeModeItemAdd(result, 36 + selectedSlot);
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    private static ItemStack createPackedStack(Minecraft client, ItemStack mainHand, ItemStack offHand) {
        ItemStack fullStack = hasContents(mainHand) ? mainHand.copy() : mainHand.copyWithCount(mainHand.getMaxStackSize());
        if (isShulkerBox(mainHand)) {
            return packIntoOffHandOrFallback(client, fullStack, offHand, QuickCreativePacking::fillChest);
        }

        return packIntoOffHandOrFallback(client, fullStack, offHand, QuickCreativePacking::fillShulker);
    }

    private static ItemStack packIntoOffHandOrFallback(
            Minecraft client,
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
                && blockItem.getBlock() instanceof EntityBlock provider) {
            BlockEntity blockEntity = provider.newBlockEntity(BlockPos.ZERO, blockItem.getBlock().defaultBlockState());
            if (blockEntity instanceof RandomizableContainerBlockEntity container) {
                return fillContainer(client, contents, blockItem, container);
            }
        }
        return fallback.create(client, contents);
    }

    private static ItemStack fillShulker(Minecraft client, ItemStack contents) {
        Block block = Blocks.SHULKER_BOX;
        return fillContainer(
                client,
                contents,
                block.asItem(),
                new ShulkerBoxBlockEntity(BlockPos.ZERO, block.defaultBlockState())
        );
    }

    private static ItemStack fillChest(Minecraft client, ItemStack contents) {
        Block chest = Blocks.CHEST;
        return fillContainer(
                client,
                contents,
                chest.asItem(),
                new ChestBlockEntity(BlockPos.ZERO, chest.defaultBlockState())
        );
    }

    private static ItemStack fillContainer(
            Minecraft client,
            ItemStack contents,
            Item containerItem,
            RandomizableContainerBlockEntity container
    ) {
        List<ItemStack> stacks = new ArrayList<>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            stacks.add(contents.copy());
        }

        ItemStack result = containerItem.getDefaultInstance();
        result.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks));
        return result;
    }

    private static ItemStack fillBundle(ItemStack contents) {
        BundleContents.Mutable builder = new BundleContents.Mutable(BundleContents.EMPTY);
        for (int stack = 0; stack < QuickCraftConfigs.getCreativePackingBundleStacks(); stack++) {
            ItemStack remaining = contents.copy();
            if (builder.tryInsert(remaining) == 0) {
                break;
            }
        }

        ItemStack bundle = Items.BUNDLE.getDefaultInstance();
        bundle.set(DataComponents.BUNDLE_CONTENTS, builder.toImmutable());
        return bundle;
    }

    private static boolean hasContents(ItemStack stack) {
        return hasContainerContents(stack)
                || stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).size() > 0;
    }

    private static boolean hasContainerContents(ItemStack stack) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        return contents != null && contents.nonEmptyItems().iterator().hasNext();
    }

    private static int getContentsNestingDepth(ItemStack stack) {
        int depth = 0;

        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null && container.nonEmptyItems().iterator().hasNext()) {
            depth = 1;
            for (var nestedTemplate : container.nonEmptyItems()) {
                depth = Math.max(depth, 1 + getContentsNestingDepth(nestedTemplate.create()));
            }
        }

        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && bundle.size() > 0) {
            depth = Math.max(depth, 1);
            for (ItemStack nested : bundle.itemCopyStream().toList()) {
                depth = Math.max(depth, 1 + getContentsNestingDepth(nested));
            }
        }

        return depth;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static void sendError(LocalPlayer player, String key) {
        player.sendOverlayMessage(Component.translatable("quickcraft.message.creative_packing." + key)
                .withStyle(ChatFormatting.RED));
    }

    @FunctionalInterface
    private interface PackingFallback {
        ItemStack create(Minecraft client, ItemStack contents);
    }
}
