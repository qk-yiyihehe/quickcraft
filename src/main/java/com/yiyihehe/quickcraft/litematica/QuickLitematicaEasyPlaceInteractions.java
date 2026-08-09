package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.block.AbstractCauldronBlock;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BeaconBlock;
import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.BellBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.BrewingStandBlock;
import net.minecraft.block.BulbBlock;
import net.minecraft.block.CakeBlock;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CartographyTableBlock;
import net.minecraft.block.CaveVines;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ChiseledBookshelfBlock;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DecoratedPotBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.DropperBlock;
import net.minecraft.block.DragonEggBlock;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.EnchantingTableBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.GrindstoneBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.JukeboxBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.LoomBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.RedstoneOreBlock;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.SmithingTableBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.TntBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.VaultBlock;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.GlassBottleItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.HoneycombItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShearsItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * 统一决定 Litematica 轻松放置何时应让出右键给原版。
 * 这里只控制是否绕过轻松放置；物品条件、方块状态和服务端结果仍完全由原版交互链处理。
 */
public final class QuickLitematicaEasyPlaceInteractions {
    private QuickLitematicaEasyPlaceInteractions() {
    }

    public static boolean shouldAllowVanillaUse(MinecraftClient client) {
        if (!QuickCraftConfigs.areEasyPlaceVanillaInteractionsAllowed()
                || client == null
                || client.player == null
                || client.world == null
                || client.currentScreen != null) {
            return false;
        }

        HitResult hitResult = client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || blockHitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        net.minecraft.block.BlockState blockState = client.world.getBlockState(blockHitResult.getBlockPos());
        Block block = blockState.getBlock();
        ItemStack heldStack = client.player.getMainHandStack();
        return isScreenInteraction(block)
                || isRedstoneInteraction(block)
                || isFunctionalBlockInteraction(block)
                || isFluidInteraction(blockState, heldStack)
                || isToolInteraction(block, heldStack)
                || isDecorationInteraction(block, heldStack)
                || isSurvivalInteraction(block)
                || isSpecialInteraction(client, block, heldStack);
    }

    private static boolean isScreenInteraction(Block block) {
        return QuickCraftConfigs.areEasyPlaceInteractionScreensAllowed()
                && (block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof EnderChestBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof DispenserBlock
                || block instanceof DropperBlock
                || block instanceof HopperBlock
                || block instanceof CrafterBlock
                || block instanceof AbstractFurnaceBlock
                || block instanceof BrewingStandBlock
                || block instanceof CraftingTableBlock
                || block instanceof StonecutterBlock
                || block instanceof AnvilBlock
                || block instanceof GrindstoneBlock
                || block instanceof SmithingTableBlock
                || block instanceof CartographyTableBlock
                || block instanceof LoomBlock
                || block instanceof EnchantingTableBlock
                || block instanceof BeaconBlock
                || block instanceof LecternBlock);
    }

    private static boolean isRedstoneInteraction(Block block) {
        return QuickCraftConfigs.areEasyPlaceRedstoneInteractionsAllowed()
                && (block instanceof LeverBlock
                || block instanceof ButtonBlock
                || block instanceof DoorBlock
                || block instanceof TrapdoorBlock
                || block instanceof FenceGateBlock
                || block instanceof BulbBlock
                || block instanceof BellBlock
                || block instanceof RepeaterBlock
                || block instanceof ComparatorBlock
                || block instanceof DaylightDetectorBlock
                || block instanceof NoteBlock
                || block instanceof RedstoneOreBlock);
    }

    private static boolean isFunctionalBlockInteraction(Block block) {
        return QuickCraftConfigs.areEasyPlaceFunctionalBlockInteractionsAllowed()
                && (block instanceof ComposterBlock
                || block instanceof JukeboxBlock
                || block instanceof ChiseledBookshelfBlock
                || block instanceof DecoratedPotBlock);
    }

    private static boolean isFluidInteraction(net.minecraft.block.BlockState blockState, ItemStack heldStack) {
        if (!QuickCraftConfigs.areEasyPlaceFluidInteractionsAllowed()) {
            return false;
        }

        Block block = blockState.getBlock();
        return (block instanceof Waterloggable
                && ((heldStack.isOf(Items.WATER_BUCKET))
                || (heldStack.isOf(Items.BUCKET)
                && blockState.contains(Properties.WATERLOGGED)
                && blockState.get(Properties.WATERLOGGED))))
                || (block instanceof AbstractCauldronBlock
                && (heldStack.getItem() instanceof BucketItem || heldStack.getItem() instanceof GlassBottleItem));
    }

    private static boolean isToolInteraction(Block block, ItemStack heldStack) {
        if (!QuickCraftConfigs.areEasyPlaceToolInteractionsAllowed()) {
            return false;
        }

        if (heldStack.getItem() instanceof AxeItem) {
            return isStrippableBlock(block) || block instanceof net.minecraft.block.Oxidizable
                    || HoneycombItem.WAXED_TO_UNWAXED_BLOCKS.get().containsKey(block);
        }
        if (heldStack.getItem() instanceof ShovelItem) {
            return block == Blocks.GRASS_BLOCK
                    || block == Blocks.DIRT
                    || block == Blocks.PODZOL
                    || block == Blocks.COARSE_DIRT
                    || block == Blocks.MYCELIUM
                    || block == Blocks.ROOTED_DIRT
                    || block instanceof CampfireBlock;
        }
        if (heldStack.getItem() instanceof HoeItem) {
            return block == Blocks.GRASS_BLOCK
                    || block == Blocks.DIRT_PATH
                    || block == Blocks.DIRT
                    || block == Blocks.COARSE_DIRT
                    || block == Blocks.ROOTED_DIRT;
        }
        return heldStack.getItem() instanceof ShearsItem && block == Blocks.PUMPKIN;
    }

    private static boolean isDecorationInteraction(Block block, ItemStack heldStack) {
        return QuickCraftConfigs.areEasyPlaceDecorationInteractionsAllowed()
                && (block instanceof AbstractSignBlock
                || block instanceof FlowerPotBlock
                || block instanceof CampfireBlock
                || block instanceof CandleBlock
                || block instanceof CandleCakeBlock
                || block instanceof FenceBlock && heldStack.isOf(Items.LEAD)
                || heldStack.getItem() instanceof HoneycombItem
                && HoneycombItem.UNWAXED_TO_WAXED_BLOCKS.get().containsKey(block));
    }

    private static boolean isSurvivalInteraction(Block block) {
        return QuickCraftConfigs.areEasyPlaceSurvivalInteractionsAllowed()
                && (block instanceof SweetBerryBushBlock
                || block instanceof CaveVines
                || block instanceof BeehiveBlock
                || block instanceof CakeBlock
                || block instanceof CampfireBlock
                || block instanceof BedBlock);
    }

    private static boolean isSpecialInteraction(MinecraftClient client, Block block, ItemStack heldStack) {
        if (!QuickCraftConfigs.areEasyPlaceSpecialInteractionsAllowed()) {
            return false;
        }

        return block instanceof EndPortalFrameBlock && heldStack.isOf(Items.ENDER_EYE)
                || block instanceof RespawnAnchorBlock && heldStack.isOf(Items.GLOWSTONE)
                || block instanceof VaultBlock
                && (heldStack.isOf(Items.TRIAL_KEY) || heldStack.isOf(Items.OMINOUS_TRIAL_KEY))
                || block instanceof DragonEggBlock
                || QuickCraftConfigs.areEasyPlaceDangerousInteractionsAllowed()
                && block instanceof TntBlock
                && (heldStack.isOf(Items.FLINT_AND_STEEL) || heldStack.isOf(Items.FIRE_CHARGE))
                || QuickCraftConfigs.areEasyPlaceAdminInteractionsAllowed()
                && client.player != null && client.player.isCreative()
                && (block instanceof net.minecraft.block.CommandBlock
                || block instanceof net.minecraft.block.JigsawBlock
                || block instanceof net.minecraft.block.StructureBlock
                || block instanceof net.minecraft.block.LightBlock);
    }

    private static boolean isStrippableBlock(Block block) {
        return block == Blocks.OAK_LOG
                || block == Blocks.OAK_WOOD
                || block == Blocks.SPRUCE_LOG
                || block == Blocks.SPRUCE_WOOD
                || block == Blocks.BIRCH_LOG
                || block == Blocks.BIRCH_WOOD
                || block == Blocks.JUNGLE_LOG
                || block == Blocks.JUNGLE_WOOD
                || block == Blocks.ACACIA_LOG
                || block == Blocks.ACACIA_WOOD
                || block == Blocks.DARK_OAK_LOG
                || block == Blocks.DARK_OAK_WOOD
                || block == Blocks.MANGROVE_LOG
                || block == Blocks.MANGROVE_WOOD
                || block == Blocks.CHERRY_LOG
                || block == Blocks.CHERRY_WOOD
                || block == Blocks.CRIMSON_STEM
                || block == Blocks.CRIMSON_HYPHAE
                || block == Blocks.WARPED_STEM
                || block == Blocks.WARPED_HYPHAE
                || block == Blocks.BAMBOO_BLOCK;
    }
}
