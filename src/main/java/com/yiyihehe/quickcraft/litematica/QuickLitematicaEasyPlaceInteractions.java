package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickFreeCameraInteractions;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.util.RayTraceUtils.RayTraceWrapper;
import fi.dy.masa.litematica.util.RayTraceUtils.RayTraceWrapper.HitType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BottleItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 统一决定 Litematica 轻松放置何时应让出右键给原版。
 * 这里只控制是否绕过轻松放置；物品条件、方块状态和服务端结果仍完全由原版交互链处理。
 */
public final class QuickLitematicaEasyPlaceInteractions {
    private QuickLitematicaEasyPlaceInteractions() {
    }

    public static boolean shouldAllowVanillaUse(Minecraft client) {
        if (!QuickCraftConfigs.areEasyPlaceVanillaInteractionsAllowed()
                || client == null
                || client.player == null
                || client.level == null
                || client.screen != null) {
            return false;
        }

        if (isSchematicBlockCloser(client)) {
            return false;
        }

        HitResult hitResult = client.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || blockHitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        BlockState blockState = client.level.getBlockState(blockHitResult.getBlockPos());
        Block block = blockState.getBlock();
        ItemStack heldStack = client.player.getMainHandItem();
        return isScreenInteraction(block)
                || isRedstoneInteraction(block)
                || isFunctionalBlockInteraction(block)
                || isFluidInteraction(blockState, heldStack)
                || isToolInteraction(block, heldStack)
                || isDecorationInteraction(block, heldStack)
                || isSurvivalInteraction(block)
                || isSpecialInteraction(client, block, heldStack);
    }

    private static boolean isSchematicBlockCloser(Minecraft client) {
        net.minecraft.world.entity.Entity traceEntity = QuickFreeCameraInteractions.getEasyPlaceTraceEntity(client, client.player);
        boolean targetFluids = Configs.InfoOverlays.INFO_OVERLAYS_TARGET_FLUIDS.getBooleanValue();
        RayTraceWrapper trace = RayTraceUtils.getGenericTrace(
                client.level,
                traceEntity,
                WorldUtils.getValidBlockRange(client),
                true,
                targetFluids,
                false
        );
        return trace != null && trace.getHitType() == HitType.SCHEMATIC_BLOCK;
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
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || block instanceof CopperBulbBlock
                || block instanceof BellBlock
                || block instanceof RepeaterBlock
                || block instanceof ComparatorBlock
                || block instanceof DaylightDetectorBlock
                || block instanceof NoteBlock
                || block instanceof RedStoneOreBlock);
    }

    private static boolean isFunctionalBlockInteraction(Block block) {
        return QuickCraftConfigs.areEasyPlaceFunctionalBlockInteractionsAllowed()
                && (block instanceof ComposterBlock
                || block instanceof JukeboxBlock
                || block instanceof ChiseledBookShelfBlock
                || block instanceof DecoratedPotBlock);
    }

    private static boolean isFluidInteraction(BlockState blockState, ItemStack heldStack) {
        if (!QuickCraftConfigs.areEasyPlaceFluidInteractionsAllowed()) {
            return false;
        }

        Block block = blockState.getBlock();
        return (blockState.hasProperty(BlockStateProperties.WATERLOGGED)
                && (heldStack.is(Items.WATER_BUCKET)
                || (heldStack.is(Items.BUCKET)
                && blockState.getValue(BlockStateProperties.WATERLOGGED))))
                || (block instanceof AbstractCauldronBlock
                && (heldStack.getItem() instanceof BucketItem || heldStack.getItem() instanceof BottleItem));
    }

    private static boolean isToolInteraction(Block block, ItemStack heldStack) {
        if (!QuickCraftConfigs.areEasyPlaceToolInteractionsAllowed()) {
            return false;
        }

        if (heldStack.getItem() instanceof AxeItem) {
            return isStrippableBlock(block) || block instanceof WeatheringCopper
                    || HoneycombItem.WAX_OFF_BY_BLOCK.get().containsKey(block);
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
                && (block instanceof SignBlock
                || block instanceof FlowerPotBlock
                || block instanceof CampfireBlock
                || block instanceof CandleBlock
                || block instanceof CandleCakeBlock
                || block instanceof FenceBlock && heldStack.is(Items.LEAD)
                || heldStack.getItem() instanceof HoneycombItem
                && HoneycombItem.WAXABLES.get().containsKey(block));
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

    private static boolean isSpecialInteraction(Minecraft client, Block block, ItemStack heldStack) {
        if (!QuickCraftConfigs.areEasyPlaceSpecialInteractionsAllowed()) {
            return false;
        }

        return block instanceof EndPortalFrameBlock && heldStack.is(Items.ENDER_EYE)
                || block instanceof RespawnAnchorBlock && heldStack.is(Items.GLOWSTONE)
                || block instanceof VaultBlock
                && (heldStack.is(Items.TRIAL_KEY) || heldStack.is(Items.OMINOUS_TRIAL_KEY))
                || block instanceof DragonEggBlock
                || QuickCraftConfigs.areEasyPlaceDangerousInteractionsAllowed()
                && block instanceof TntBlock
                && (heldStack.is(Items.FLINT_AND_STEEL) || heldStack.is(Items.FIRE_CHARGE))
                || QuickCraftConfigs.areEasyPlaceAdminInteractionsAllowed()
                && client.player != null && client.player.isCreative()
                && (block instanceof CommandBlock
                || block instanceof JigsawBlock
                || block instanceof StructureBlock
                || block instanceof LightBlock);
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
