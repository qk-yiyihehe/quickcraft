package com.yiyihehe.quickcraft.litematica;

import com.chocohead.mm.api.ClassTinkerers;
import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.fabricmc.loader.api.FabricLoader;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.util.BlockInfoAlignment;
import fi.dy.masa.litematica.util.SchematicUtils;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.InventoryOverlayType;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * QuickCraft 的 Litematica 容器验证主类。
 * 这里统一收口原理图容器校验相关能力，包括：
 * 1. 对外功能契约、错填类型和原理图位置解析；
 * 2. 委托库存读取、物品比较和当前屏幕绑定；
 * 3. 容器界面里的幽灵物品辅助渲染；
 * 4. 提供给 mixin 挂接的 verifier / mismatch 扩展接口；
 * 5. 需要尽早注册的验证类型注入逻辑。
 *
 * TechUtils 只作为交互参考，这里不依赖它的任何 API。
 */
public final class QuickLitematicaContainerVerifier {
    public static final MismatchType WRONG_FILL = ClassTinkerers.getEnum(
            MismatchType.class,
            EarlyRiser.WRONG_FILL_ENUM
    );
    public static final MismatchType MISSING_FILL = ClassTinkerers.getEnum(
            MismatchType.class,
            EarlyRiser.MISSING_FILL_ENUM
    );
    public static final MismatchType EXTRA_FILL = ClassTinkerers.getEnum(
            MismatchType.class,
            EarlyRiser.EXTRA_FILL_ENUM
    );
    public static final MismatchType WRONG_FILL_STATE = ClassTinkerers.getEnum(
            MismatchType.class,
            EarlyRiser.WRONG_FILL_STATE_ENUM
    );

    private QuickLitematicaContainerVerifier() {
    }

    public static boolean isEnabled() {
        return QuickCraftConfigs.isLitematicaContainerVerifierEnabled();
    }

    public static boolean areSlotHintsVisible() {
        return isEnabled() && QuickCraftConfigs.isLitematicaContainerSlotHintsVisible();
    }

    public static boolean isContainerMismatchType(MismatchType type) {
        return type == WRONG_FILL
                || type == MISSING_FILL
                || type == EXTRA_FILL
                || type == WRONG_FILL_STATE;
    }

    public static List<MismatchType> getContainerMismatchTypes() {
        return List.of(WRONG_FILL, MISSING_FILL, WRONG_FILL_STATE);
    }

    public static Container getExpectedInventory(BlockEntity expectedBlockEntity, Container directInventory) {
        return QuickLitematicaContainerInventory.getExpectedInventory(expectedBlockEntity, directInventory);
    }

    public static Container getActualInventory(Level world, BlockPos pos, Container directInventory, Container expected) {
        return QuickLitematicaContainerInventory.getActualInventory(world, pos, directInventory, expected);
    }

    public static ActualInventoryReadStatus getLastActualInventoryReadStatus() {
        return QuickLitematicaContainerInventory.getLastActualInventoryReadStatus();
    }

    public static String getItemStackSignature(ItemStack stack) {
        return QuickLitematicaContainerInventory.getItemStackSignature(stack);
    }

    public static void requestInventoryData(Level world, BlockPos pos) {
        QuickLitematicaContainerInventory.requestInventoryData(world, pos);
    }

    public static boolean requestInventoryDataChunk(Level world, ChunkPos chunkPos, int minY, int maxY) {
        return QuickLitematicaContainerInventory.requestInventoryDataChunk(world, chunkPos, minY, maxY);
    }

    public static List<ContainerMismatch> findMismatches(
            BlockPos pos,
            BlockState expectedState,
            BlockState foundState,
            BlockEntity expectedBlockEntity,
            BlockEntity foundBlockEntity,
            Container expected,
            Container found
    ) {
        return QuickLitematicaContainerComparison.findMismatches(pos, expectedState, foundState, expectedBlockEntity, foundBlockEntity, expected, found);
    }

    public static List<ContainerMismatch> findMismatches(
            BlockPos pos,
            BlockState expectedState,
            BlockState foundState,
            BlockEntity expectedBlockEntity,
            BlockEntity foundBlockEntity,
            Set<Integer> expectedDisabledSlots,
            Set<Integer> foundDisabledSlots,
            Container expected,
            Container found
    ) {
        return QuickLitematicaContainerComparison.findMismatches(pos, expectedState, foundState, expectedBlockEntity, foundBlockEntity, expectedDisabledSlots, foundDisabledSlots, expected, found);
    }

    public static void renderInventoryPair(
            ContainerMismatch mismatch,
            BlockState expectedState,
            BlockState foundState,
            Set<Integer> expectedDisabledSlots,
            Set<Integer> foundDisabledSlots,
            int mouseX,
            int mouseY,
            Minecraft mc,
            GuiGraphicsExtractor drawContext
    ) {
        if (mismatch == null) {
            return;
        }

        Pair<Container, Container> inventories = mismatch.inventories();

        if (inventories == null) {
            return;
        }

        renderInventoryOverlay(BlockInfoAlignment.CENTER, LeftRight.LEFT, 0, mismatch.type(), inventories.getLeft(), expectedState, expectedDisabledSlots, List.of(), false, mouseX, mouseY, mc, drawContext);
        renderInventoryOverlay(BlockInfoAlignment.CENTER, LeftRight.RIGHT, 0, mismatch.type(), inventories.getRight(), foundState, foundDisabledSlots, mismatch.slotMismatches(), true, mouseX, mouseY, mc, drawContext);
    }

    public static boolean shouldSuppressInventorySlotHighlights() {
        return QuickLitematicaContainerScreenBinding.shouldSuppressInventorySlotHighlights();
    }

    public static void setSuppressInventorySlotHighlights(boolean suppress) {
        QuickLitematicaContainerScreenBinding.setSuppressInventorySlotHighlights(suppress);
    }

    public static void clearCurrentHandledScreenBinding() {
        QuickLitematicaContainerScreenBinding.clearCurrentHandledScreenBinding();
    }

    public static void drawGhostItem(
            GuiGraphicsExtractor context,
            Minecraft client,
            ItemStack stack,
            int x,
            int y,
            int guiLeft,
            int guiTop,
            float alpha
    ) {
        GhostItemBuffer.drawGhostItem(context, client, stack, x, y, guiLeft, guiTop, alpha);
    }

    public static boolean beginHandledScreenGhostRender(GuiGraphicsExtractor context, Minecraft client) {
        return client != null;
    }

    public static void endHandledScreenGhostRender(GuiGraphicsExtractor context, int guiLeft, int guiTop, float alpha) {
    }

    public static void rememberContainerUse(Minecraft client, BlockHitResult hitResult) {
        QuickLitematicaContainerScreenBinding.rememberContainerUse(client, hitResult);
    }

    public static SlotOverlay getSlotOverlayForScreen(AbstractContainerScreen<?> screen, Slot slot) {
        return QuickLitematicaContainerScreenBinding.getSlotOverlayForScreen(screen, slot);
    }

    public static ExpectedContainer getExpectedContainerAt(Level foundWorld, BlockPos pos) {
        return getExpectedContainerAt(foundWorld, pos, null);
    }

    public static ExpectedContainer getExpectedContainerAt(
            Level foundWorld,
            BlockPos pos,
            @Nullable SchematicPlacement placementFilter
    ) {
        if (foundWorld == null) {
            return null;
        }

        ExpectedContainer current = getExpectedContainerInternal(pos, placementFilter);

        if (current == null) {
            return null;
        }

        ExpectedContainer merged = getExpectedDoubleChestContainer(pos, current, placementFilter);

        return merged != null ? merged : current;
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshotAt(Level foundWorld, BlockPos pos) {
        ExpectedContainer expected = getExpectedContainerAt(foundWorld, pos);
        if (expected == null) {
            return null;
        }

        QuickContainerCopy.PublicContainerType type = QuickContainerCopy.getPublicContainerType(
                expected.state().getBlock(),
                QuickLitematicaContainerInventory.getChestType(expected.state())
        );
        if (type == null) {
            return null;
        }

        List<ItemStack> templates = new ArrayList<>(expected.inventory().getContainerSize());
        List<Boolean> disabledStates = new ArrayList<>(expected.inventory().getContainerSize());
        for (int i = 0; i < expected.inventory().getContainerSize(); i++) {
            ItemStack stack = expected.inventory().getItem(i);
            templates.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            disabledStates.add(expected.disabledSlots().contains(i));
        }

        return new QuickContainerCopy.TemplateSnapshot(type, templates, disabledStates);
    }

    public static Set<Integer> getDisabledSlots(BlockEntity blockEntity) {
        return QuickLitematicaContainerInventory.getDisabledSlots(blockEntity);
    }

    private static ExpectedContainer getExpectedDoubleChestContainer(
            BlockPos pos,
            ExpectedContainer current,
            @Nullable SchematicPlacement placementFilter
    ) {
        ChestType chestType = QuickLitematicaContainerInventory.getChestType(current.state());

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = ChestBlock.getConnectedBlockPos(pos, current.state());
        ExpectedContainer adjacent = getExpectedContainerInternal(adjacentPos, placementFilter);

        if (adjacent == null) {
            return null;
        }

        Container currentInventory = current.inventory();
        Container adjacentInventory = adjacent.inventory();
        Container merged = chestType == ChestType.RIGHT
                ? QuickLitematicaContainerInventory.mergeInventories(currentInventory, adjacentInventory)
                : QuickLitematicaContainerInventory.mergeInventories(adjacentInventory, currentInventory);

        return new ExpectedContainer(
                pos,
                current.state(),
                current.blockEntity(),
                merged,
                Set.of()
        );
    }

    public static BlockPos getExpectedDoubleChestAdjacentPos(BlockPos pos) {
        return getExpectedDoubleChestAdjacentPos(pos, null);
    }

    public static BlockPos getExpectedDoubleChestAdjacentPos(
            BlockPos pos,
            @Nullable SchematicPlacement placementFilter
    ) {
        ExpectedContainer current = getExpectedContainerInternal(pos, placementFilter);

        if (current == null || QuickLitematicaContainerInventory.getChestType(current.state()) == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = ChestBlock.getConnectedBlockPos(pos, current.state());
        ExpectedContainer adjacent = getExpectedContainerInternal(adjacentPos, placementFilter);
        return adjacent != null && QuickLitematicaContainerInventory.getChestType(adjacent.state()) != ChestType.SINGLE
                ? adjacentPos
                : null;
    }

    public static ExpectedContainer getExpectedContainerPartAt(SchematicPlacement placement, BlockPos pos) {
        return placement != null ? getExpectedContainerInternal(pos, placement) : null;
    }

    private static ExpectedContainer getExpectedContainerInternal(
            BlockPos worldPos, @Nullable SchematicPlacement placementFilter) {
        LocalPlacementPos placementPos = getLocalPlacementPos(worldPos, placementFilter);

        if (placementPos == null) {
            return null;
        }

        Map<BlockPos, ?> blockEntities = placementPos.placement().getSchematic()
                .getBlockEntityMapForRegion(placementPos.region());

        if (blockEntities == null) {
            return null;
        }

        Object data = blockEntities.get(placementPos.pos());

        if (data == null) {
            return null;
        }

        CompoundTag nbt = QuickLitematicaDataCompat.toVanillaNbt(data);

        Minecraft client = Minecraft.getInstance();
        Level clientWorld = client.level;

        if (clientWorld == null) {
            return null;
        }

        BlockEntity blockEntity = BlockEntity.loadStatic(
                placementPos.pos(),
                placementPos.rawState(),
                nbt,
                clientWorld.registryAccess()
        );

        if (!(blockEntity instanceof Container inventory)) {
            return null;
        }

        return new ExpectedContainer(
                worldPos,
                placementPos.worldState(),
                blockEntity,
                QuickLitematicaContainerInventory.copyInventory(inventory),
                QuickLitematicaContainerInventory.getDisabledSlots(blockEntity, nbt)
        );
    }

    private static LocalPlacementPos getLocalPlacementPos(
            BlockPos worldPos, @Nullable SchematicPlacement placementFilter) {
        List<SchematicPlacementManager.PlacementPart> parts = DataManager.getSchematicPlacementManager()
                .getAllPlacementsTouchingChunk(worldPos);

        for (SchematicPlacementManager.PlacementPart part : parts) {
            if ((placementFilter != null && part.getPlacement() != placementFilter)
                    || !QuickLitematicaPlacementAccess.contains(part, worldPos)) {
                continue;
            }

            SchematicPlacement placement = part.getPlacement();
            String region = part.getSubRegionName();
            LitematicaSchematic schematic = placement.getSchematic();
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);
            SubRegionPlacement subRegionPlacement = placement.getRelativeSubRegionPlacement(region);

            if (container == null || subRegionPlacement == null) {
                continue;
            }

            BlockPos schematicPos = SchematicUtils.getSchematicContainerPositionFromWorldPosition(
                    worldPos,
                    schematic,
                    region,
                    placement,
                    subRegionPlacement,
                    container
            );

            if (schematicPos == null) {
                continue;
            }

            BlockState rawState = container.get(schematicPos.getX(), schematicPos.getY(), schematicPos.getZ());
            BlockState worldState = getTransformedWorldState(rawState, placement, region);

            return new LocalPlacementPos(schematicPos, region, placement, rawState, worldState);
        }

        return null;
    }

    private static BlockState getTransformedWorldState(BlockState state, SchematicPlacement schematicPlacement, String region) {
        SubRegionPlacement placement = schematicPlacement.getRelativeSubRegionPlacement(region);

        if (placement == null) {
            return state;
        }

        Rotation rotationCombined = schematicPlacement.getRotation().getRotated(placement.getRotation());
        Mirror mirrorMain = schematicPlacement.getMirror();
        Mirror mirrorSub = placement.getMirror();

        if (mirrorSub != Mirror.NONE
                && (schematicPlacement.getRotation() == Rotation.CLOCKWISE_90
                || schematicPlacement.getRotation() == Rotation.COUNTERCLOCKWISE_90)) {
            mirrorSub = mirrorSub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        }

        if (mirrorMain != Mirror.NONE) {
            state = state.mirror(mirrorMain);
        }
        if (mirrorSub != Mirror.NONE) {
            state = state.mirror(mirrorSub);
        }
        if (rotationCombined != Rotation.NONE) {
            state = state.rotate(rotationCombined);
        }

        return state;
    }

    private static void renderInventoryOverlay(
            BlockInfoAlignment align,
            LeftRight side,
            int offY,
            MismatchType mismatchType,
            Container inventory,
            BlockState state,
            Set<Integer> disabledSlots,
            List<SlotMismatch> slotMismatches,
            boolean renderGhostStacks,
            double mouseX,
            double mouseY,
            Minecraft mc,
            GuiGraphicsExtractor drawContext
    ) {
        InventoryOverlayType type = getInventoryType(inventory, state);
        InventoryOverlay.InventoryProperties props = InventoryOverlay.getInventoryPropsTemp(type, inventory.getContainerSize());
        GuiContext guiContext = GuiContext.fromGuiGraphics(drawContext);
        int xInv = 0;
        int yInv = 0;

        switch (align) {
            case CENTER -> {
                xInv = fi.dy.masa.malilib.util.GuiUtils.getScaledWindowWidth() / 2 - (props.width / 2);
                yInv = fi.dy.masa.malilib.util.GuiUtils.getScaledWindowHeight() / 2 - props.height - offY;
            }
            case TOP_CENTER -> {
                xInv = fi.dy.masa.malilib.util.GuiUtils.getScaledWindowWidth() / 2 - (props.width / 2);
                yInv = offY;
            }
        }

        if (side == LeftRight.LEFT) {
            xInv -= props.width / 2 + 4;
        } else if (side == LeftRight.RIGHT) {
            xInv += props.width / 2 + 4;
        }

        InventoryOverlay.renderInventoryBackground(guiContext, type, xInv, yInv, props.slotsPerRow, props.totalSlots);
        drawSlotHighlights(drawContext, type, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, slotMismatches);
        InventoryOverlay.renderInventoryStacks(guiContext, type, inventory, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, 0, inventory.getContainerSize(), disabledSlots);

        if (renderGhostStacks) {
            drawMissingGhostStacks(drawContext, mc, type, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, slotMismatches);
        }
    }

    private static void drawSlotHighlights(
            GuiGraphicsExtractor drawContext,
            InventoryOverlayType type,
            int xSlots,
            int ySlots,
            int slotsPerRow,
            List<SlotMismatch> slotMismatches
    ) {
        for (SlotMismatch mismatch : slotMismatches) {
            SlotPosition pos = getInventoryOverlaySlotPosition(type, xSlots, ySlots, slotsPerRow, mismatch.slot());
            int x = pos.x();
            int y = pos.y();
            drawContext.fill(x, y, x + 16, y + 16, mismatch.status().fillColor());
            drawOutline(drawContext, x, y, 16, 16, mismatch.status().borderColor());
        }
    }

    private static void drawMissingGhostStacks(
            GuiGraphicsExtractor drawContext,
            Minecraft mc,
            InventoryOverlayType type,
            int xSlots,
            int ySlots,
            int slotsPerRow,
            List<SlotMismatch> slotMismatches
    ) {
        for (SlotMismatch mismatch : slotMismatches) {
            if (mismatch.status() != SlotMismatchStatus.MISSING || mismatch.expectedStack().isEmpty()) {
                continue;
            }

            SlotPosition pos = getInventoryOverlaySlotPosition(type, xSlots, ySlots, slotsPerRow, mismatch.slot());
            int x = pos.x();
            int y = pos.y();
            drawGhostItem(
                    drawContext,
                    mc,
                    mismatch.expectedStack(),
                    x,
                    y,
                    0,
                    0,
                    QuickLitematicaVerifierPalette.ghostItemAlpha()
            );
            drawContext.fill(x, y, x + 16, y + 16, mismatch.status().ghostMaskColor());
            drawOutline(drawContext, x, y, 16, 16, mismatch.status().borderColor());
        }
    }

    private static SlotPosition getInventoryOverlaySlotPosition(
            InventoryOverlayType type,
            int xSlots,
            int ySlots,
            int slotsPerRow,
            int slot
    ) {
        // 炉子类和酿造台在 malilib 的 InventoryOverlay 里不是普通网格槽位。
        if (type == InventoryOverlayType.FURNACE) {
            return switch (slot) {
                case 0 -> new SlotPosition(xSlots + 8, ySlots + 8);
                case 1 -> new SlotPosition(xSlots + 8, ySlots + 44);
                case 2 -> new SlotPosition(xSlots + 68, ySlots + 26);
                default -> getGridSlotPosition(xSlots, ySlots, slotsPerRow, slot);
            };
        }

        if (type == InventoryOverlayType.BREWING_STAND) {
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

    private static void drawOutline(GuiGraphicsExtractor drawContext, int x, int y, int width, int height, int color) {
        drawContext.fill(x, y, x + width, y + 1, color);
        drawContext.fill(x, y + height - 1, x + width, y + height, color);
        drawContext.fill(x, y + 1, x + 1, y + height - 1, color);
        drawContext.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    private static InventoryOverlayType getInventoryType(Container inventory, BlockState state) {
        if (state != null) {
            if (state.getBlock() instanceof AbstractFurnaceBlock) {
                return InventoryOverlayType.FURNACE;
            }
            if (state.getBlock() instanceof BrewingStandBlock) {
                return InventoryOverlayType.BREWING_STAND;
            }
            if (state.getBlock() instanceof CrafterBlock) {
                return InventoryOverlayType.CRAFTER;
            }
            if (state.getBlock() instanceof DispenserBlock) {
                return InventoryOverlayType.DISPENSER;
            }
            if (state.getBlock() instanceof HopperBlock) {
                return InventoryOverlayType.HOPPER;
            }
        }

        return switch (inventory.getContainerSize()) {
            case 3 -> InventoryOverlayType.FURNACE;
            case 5 -> InventoryOverlayType.HOPPER;
            case 9 -> InventoryOverlayType.DISPENSER;
            case 27 -> InventoryOverlayType.FIXED_27;
            case 54 -> InventoryOverlayType.FIXED_54;
            default -> InventoryOverlayType.GENERIC;
        };
    }

    public record ContainerMismatch(
            BlockPos pos,
            BlockState expectedState,
            BlockState foundState,
            int slot,
            MismatchType type,
            ItemStack expectedStack,
            ItemStack foundStack,
            Container expectedInventory,
            Container foundInventory,
            Set<Integer> expectedDisabledSlots,
            Set<Integer> foundDisabledSlots,
            List<SlotMismatch> slotMismatches
    ) {
        public Pair<Container, Container> inventories() {
            return Pair.of(this.expectedInventory, this.foundInventory);
        }

        public ContainerMismatchKey key() {
            return new ContainerMismatchKey(this.type, this.pos, this.slotMismatchSignature());
        }

        private String slotMismatchSignature() {
            StringBuilder builder = new StringBuilder();

            builder.append(this.type.ordinal())
                    .append('|')
                    .append(this.expectedState)
                    .append('|')
                    .append(this.foundState)
                    .append('|')
                    .append(this.expectedDisabledSlots.stream().sorted().toList())
                    .append('|')
                    .append(this.foundDisabledSlots.stream().sorted().toList())
                    .append('|');

            for (SlotMismatch mismatch : this.slotMismatches) {
                builder.append(mismatch.slot())
                        .append(':')
                        .append(mismatch.status().name())
                        .append(':')
                        .append(mismatch.expectedStack().getCount())
                        .append(':')
                        .append(mismatch.foundStack().getCount())
                        .append(':')
                        .append(QuickLitematicaContainerInventory.getItemStackSignature(mismatch.expectedStack()))
                        .append(':')
                        .append(QuickLitematicaContainerInventory.getItemStackSignature(mismatch.foundStack()))
                        .append(';');
            }

            return builder.toString();
        }
    }

    public record ExpectedContainer(
            BlockPos pos,
            BlockState state,
            BlockEntity blockEntity,
            Container inventory,
            Set<Integer> disabledSlots
    ) {
    }

    public enum ActualInventoryReadStatus {
        NOT_READ,
        NO_WORLD,
        INTEGRATED_DIRECT,
        DIRECT_INVENTORY,
        NO_DIRECT_INVENTORY,
        CACHE_INVENTORY,
        NO_CACHE_NBT,
        CACHE_WITHOUT_ITEMS,
        CACHE_PARSE_FAILED
    }

    private record LocalPlacementPos(
            BlockPos pos,
            String region,
            SchematicPlacement placement,
            BlockState rawState,
            BlockState worldState
    ) {
    }

    public record ContainerMismatchKey(
            MismatchType type,
            BlockPos pos,
            String slotSignature
    ) {
    }

    public record SlotMismatch(
            int slot,
            SlotMismatchStatus status,
            ItemStack expectedStack,
            ItemStack foundStack
    ) {
    }

    private record SlotPosition(int x, int y) {
    }

    public record SlotOverlay(
            SlotMismatchStatus status,
            ItemStack expectedStack
    ) {
        public int fillColor() {
            return this.status.fillColor();
        }

        public int borderColor() {
            return this.status.borderColor();
        }

        public int ghostMaskColor() {
            return this.status.ghostMaskColor();
        }

    }

    /**
     * 给 Litematica 原版验证器补充 QuickCraft 容器校验状态访问接口。
     * 供界面、列表和交互逻辑读取容器错填统计与刷新入口。
     */
    public interface VerifierExtension {
        List<BlockMismatch> quickcraft$getSelectedInventoryMismatches();

        List<ContainerMismatch> quickcraft$getContainerMismatches();

        List<ItemStack> quickcraft$getMissingContainerStacks();

        int quickcraft$getWrongInventoryCount();

        int quickcraft$getContainerMismatchCount(MismatchType type);

        int quickcraft$getExpectedContainerCount();

        int quickcraft$getCheckedContainerCount();

        int quickcraft$getPendingContainerCount();

        void quickcraft$setContainerOnly(boolean containerOnly);

        boolean quickcraft$isContainerOnly();

        void quickcraft$collectContainerInventories(
                ChunkAccess chunkClient,
                ChunkAccess chunkSchematic,
                int minX,
                int minY,
                int minZ,
                int maxX,
                int maxY,
                int maxZ
        );

        List<ContainerMismatch> quickcraft$refreshContainerMismatchAt(BlockPos pos, Container foundInventory, Set<Integer> foundDisabledSlots);
    }

    /**
     * 给 Litematica 的 BlockMismatch 挂接容器校验附加数据。
     * 这里保存容器对比结果、库存引用和禁用槽位信息，供列表与悬浮预览复用。
     */
    public interface BlockMismatchExtension {
        void quickcraft$setInventories(Pair<Container, Container> inventories);

        Pair<Container, Container> quickcraft$getInventories();

        void quickcraft$setContainerMismatch(ContainerMismatch mismatch);

        ContainerMismatch quickcraft$getContainerMismatch();

        void quickcraft$setContainerMismatchKey(ContainerMismatchKey key);

        ContainerMismatchKey quickcraft$getContainerMismatchKey();

        void quickcraft$setDisabledSlots(Set<Integer> expectedDisabledSlots, Set<Integer> foundDisabledSlots);

        Set<Integer> quickcraft$getExpectedDisabledSlots();

        Set<Integer> quickcraft$getFoundDisabledSlots();
    }

    public enum SlotMismatchStatus {
        MISSING,
        WRONG,
        EXTRA,
        COUNT,
        LOCK_STATE;

        public int fillColor() {
            return QuickLitematicaVerifierPalette.slotFillColor(this.mismatchType());
        }

        public int borderColor() {
            return QuickLitematicaVerifierPalette.slotBorderColor(this.mismatchType());
        }

        public int ghostMaskColor() {
            return QuickLitematicaVerifierPalette.ghostMaskColor(this.mismatchType());
        }

        private MismatchType mismatchType() {
            return switch (this) {
                case MISSING -> MISSING_FILL;
                case WRONG -> WRONG_FILL;
                case EXTRA -> EXTRA_FILL;
                case COUNT, LOCK_STATE -> WRONG_FILL_STATE;
            };
        }
    }

    /**
     * 在 Litematica 类加载前补充验证器枚举。
     * 这样容器填充错误可以复用原版验证器按钮、列表、高亮和 HUD。
     */
    public static class EarlyRiser implements Runnable {
        public static final String WRONG_FILL_ENUM = "QUICKCRAFT_WRONG_FILL";
        public static final String MISSING_FILL_ENUM = "QUICKCRAFT_MISSING_FILL";
        public static final String EXTRA_FILL_ENUM = "QUICKCRAFT_EXTRA_FILL";
        public static final String WRONG_FILL_STATE_ENUM = "QUICKCRAFT_WRONG_FILL_STATE";

        @Override
        public void run() {
            if (!FabricLoader.getInstance().isModLoaded("litematica")) {
                return;
            }

            ClassTinkerers.enumBuilder(
                            "fi.dy.masa.litematica.schematic.verifier.SchematicVerifier$MismatchType",
                            int.class,
                            String.class,
                            String.class
                    )
                    .addEnum(
                            WRONG_FILL_ENUM,
                            QuickLitematicaVerifierPalette.wrongFillRgb(),
                            "litematica.gui.label.schematic_verifier_display_type.quickcraft_wrong_fill",
                            QuickLitematicaVerifierPalette.wrongFillFormattingCode()
                    )
                    .addEnum(
                            MISSING_FILL_ENUM,
                            QuickLitematicaVerifierPalette.missingFillRgb(),
                            "litematica.gui.label.schematic_verifier_display_type.quickcraft_missing_fill",
                            QuickLitematicaVerifierPalette.missingFillFormattingCode()
                    )
                    .addEnum(
                            EXTRA_FILL_ENUM,
                            QuickLitematicaVerifierPalette.extraFillRgb(),
                            "litematica.gui.label.schematic_verifier_display_type.quickcraft_extra_fill",
                            QuickLitematicaVerifierPalette.extraFillFormattingCode()
                    )
                    .addEnum(
                            WRONG_FILL_STATE_ENUM,
                            QuickLitematicaVerifierPalette.wrongFillStateRgb(),
                            "litematica.gui.label.schematic_verifier_display_type.quickcraft_wrong_fill_state",
                            QuickLitematicaVerifierPalette.wrongFillStateFormattingCode()
                    )
                    .build();
        }
    }

    private static final class GhostItemBuffer {
        // 1.21.6+ 无法复用旧帧缓冲合成；使用 1.21 缺失色的提亮色覆盖物品，避免模拟透明时发黑。
        private static final int MISSING_SLOT_GHOST_MASK = 0xD0E2FF;

        private GhostItemBuffer() {
        }

        private static void drawGhostItem(
                GuiGraphicsExtractor context,
                Minecraft client,
                ItemStack stack,
                int x,
                int y,
                int guiLeft,
                int guiTop,
                float alpha
        ) {
            if (stack.isEmpty()) {
                return;
            }

            context.item(stack, x, y);
            context.itemDecorations(client.font, stack, x, y);
            int maskAlpha = Math.round((1.0F - Math.max(0.0F, Math.min(1.0F, alpha))) * 255.0F);
            context.fill(x, y, x + 16, y + 16, (maskAlpha << 24) | (MISSING_SLOT_GHOST_MASK & 0x00FFFFFF));
        }
    }
}
