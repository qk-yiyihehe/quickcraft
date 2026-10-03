package com.yiyihehe.quickcraft.litematica;

import com.chocohead.mm.api.ClassTinkerers;
import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.util.SchematicUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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

    public static Inventory getExpectedInventory(BlockEntity expectedBlockEntity, Inventory directInventory) {
        return QuickLitematicaContainerInventory.getExpectedInventory(expectedBlockEntity, directInventory);
    }

    public static Inventory getActualInventory(World world, BlockPos pos, Inventory directInventory, Inventory expected) {
        return QuickLitematicaContainerInventory.getActualInventory(world, pos, directInventory, expected);
    }

    public static ActualInventoryReadStatus getLastActualInventoryReadStatus() {
        return QuickLitematicaContainerInventory.getLastActualInventoryReadStatus();
    }

    public static String getItemStackSignature(ItemStack stack) {
        return QuickLitematicaContainerInventory.getItemStackSignature(stack);
    }

    public static void requestInventoryData(World world, BlockPos pos) {
        QuickLitematicaContainerInventory.requestInventoryData(world, pos);
    }

    public static boolean requestInventoryDataChunk(World world, ChunkPos chunkPos, int minY, int maxY) {
        return QuickLitematicaContainerInventory.requestInventoryDataChunk(world, chunkPos, minY, maxY);
    }

    public static List<ContainerMismatch> findMismatches(
            BlockPos pos,
            BlockState expectedState,
            BlockState foundState,
            BlockEntity expectedBlockEntity,
            BlockEntity foundBlockEntity,
            Inventory expected,
            Inventory found
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
            Inventory expected,
            Inventory found
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
            MinecraftClient mc,
            DrawContext drawContext
    ) {
        QuickLitematicaVerifierRenderer.renderInventoryPair(
                mismatch,
                expectedState,
                foundState,
                expectedDisabledSlots,
                foundDisabledSlots,
                mouseX,
                mouseY,
                mc,
                drawContext
        );
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

    public static void drawGhostItems(
            DrawContext context,
            MinecraftClient client,
            List<GhostItemDraw> items,
            int guiLeft,
            int guiTop,
            float alpha
    ) {
        QuickLitematicaVerifierRenderer.drawGhostItems(context, client, items, guiLeft, guiTop, alpha);
    }

    public static void drawGhostItem(
            DrawContext context,
            MinecraftClient client,
            ItemStack stack,
            int x,
            int y,
            int guiLeft,
            int guiTop,
            float alpha
    ) {
        QuickLitematicaVerifierRenderer.drawGhostItem(context, client, stack, x, y, guiLeft, guiTop, alpha);
    }

    public static boolean beginHandledScreenGhostRender(DrawContext context, MinecraftClient client) {
        return QuickLitematicaVerifierRenderer.beginHandledScreenGhostRender(context, client);
    }

    public static void endHandledScreenGhostRender(DrawContext context, int guiLeft, int guiTop, float alpha) {
        QuickLitematicaVerifierRenderer.endHandledScreenGhostRender(context, guiLeft, guiTop, alpha);
    }

    public static void rememberContainerUse(MinecraftClient client, BlockHitResult hitResult) {
        QuickLitematicaContainerScreenBinding.rememberContainerUse(client, hitResult);
    }

    public static SlotOverlay getSlotOverlayForScreen(HandledScreen<?> screen, Slot slot) {
        return QuickLitematicaContainerScreenBinding.getSlotOverlayForScreen(screen, slot);
    }

    public static ExpectedContainer getExpectedContainerAt(World foundWorld, BlockPos pos) {
        return getExpectedContainerAt(foundWorld, pos, null);
    }

    public static ExpectedContainer getExpectedContainerAt(
            World foundWorld,
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

    public static ExpectedContainer getExpectedContainerPartAt(SchematicPlacement placement, BlockPos pos) {
        return placement != null ? getExpectedContainerInternal(pos, placement) : null;
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshotAt(World foundWorld, BlockPos pos) {
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

        List<ItemStack> templates = new ArrayList<>(expected.inventory().size());
        List<Boolean> disabledStates = new ArrayList<>(expected.inventory().size());
        for (int i = 0; i < expected.inventory().size(); i++) {
            ItemStack stack = expected.inventory().getStack(i);
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

        BlockPos adjacentPos = pos.add(ChestBlock.getFacing(current.state()).getVector());
        ExpectedContainer adjacent = getExpectedContainerInternal(adjacentPos, placementFilter);

        if (adjacent == null) {
            return null;
        }

        Inventory currentInventory = current.inventory();
        Inventory adjacentInventory = adjacent.inventory();
        Inventory merged = chestType == ChestType.RIGHT
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

        BlockPos adjacentPos = pos.add(ChestBlock.getFacing(current.state()).getVector());
        ExpectedContainer adjacent = getExpectedContainerInternal(adjacentPos, placementFilter);
        return adjacent != null && QuickLitematicaContainerInventory.getChestType(adjacent.state()) != ChestType.SINGLE
                ? adjacentPos
                : null;
    }

    private static ExpectedContainer getExpectedContainerInternal(
            BlockPos worldPos,
            @Nullable SchematicPlacement placementFilter
    ) {
        LocalPlacementPos placementPos = getLocalPlacementPos(worldPos, placementFilter);

        if (placementPos == null) {
            return null;
        }

        NbtCompound nbt = QuickLitematicaVerifierAccess.getSchematicBlockEntityNbt(
                placementPos.placement().getSchematic(),
                placementPos.region(),
                placementPos.pos()
        );

        if (nbt == null) {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        World clientWorld = client.world;

        if (clientWorld == null) {
            return null;
        }

        BlockEntity blockEntity = BlockEntity.createFromNbt(
                placementPos.pos(),
                placementPos.rawState(),
                nbt,
                clientWorld.getRegistryManager()
        );

        if (!(blockEntity instanceof Inventory inventory)) {
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
            BlockPos worldPos,
            @Nullable SchematicPlacement placementFilter
    ) {
        List<SchematicPlacementManager.PlacementPart> parts = DataManager.getSchematicPlacementManager()
                .getAllPlacementsTouchingChunk(worldPos);

        for (SchematicPlacementManager.PlacementPart part : parts) {
            if ((placementFilter != null && part.getPlacement() != placementFilter)
                    || !part.getBox().containsPos(worldPos)) {
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

        BlockRotation rotationCombined = schematicPlacement.getRotation().rotate(placement.getRotation());
        BlockMirror mirrorMain = schematicPlacement.getMirror();
        BlockMirror mirrorSub = placement.getMirror();

        if (mirrorSub != BlockMirror.NONE
                && (schematicPlacement.getRotation() == BlockRotation.CLOCKWISE_90
                || schematicPlacement.getRotation() == BlockRotation.COUNTERCLOCKWISE_90)) {
            mirrorSub = mirrorSub == BlockMirror.FRONT_BACK ? BlockMirror.LEFT_RIGHT : BlockMirror.FRONT_BACK;
        }

        if (mirrorMain != BlockMirror.NONE) {
            state = state.mirror(mirrorMain);
        }
        if (mirrorSub != BlockMirror.NONE) {
            state = state.mirror(mirrorSub);
        }
        if (rotationCombined != BlockRotation.NONE) {
            state = state.rotate(rotationCombined);
        }

        return state;
    }

    public static boolean isInventoryEmpty(Inventory inventory) {
        return QuickLitematicaContainerInventory.isInventoryEmpty(inventory);
    }

    public record ContainerMismatch(
            BlockPos pos,
            BlockState expectedState,
            BlockState foundState,
            int slot,
            MismatchType type,
            ItemStack expectedStack,
            ItemStack foundStack,
            Inventory expectedInventory,
            Inventory foundInventory,
            Set<Integer> expectedDisabledSlots,
            Set<Integer> foundDisabledSlots,
            List<SlotMismatch> slotMismatches
    ) {
        public Pair<Inventory, Inventory> inventories() {
            return Pair.of(this.expectedInventory, this.foundInventory);
        }

        public ContainerMismatchKey key() {
            return new ContainerMismatchKey(this.type, this.pos, this.slotMismatchSignature());
        }

        private String slotMismatchSignature() {
            StringBuilder builder = new StringBuilder();

            for (SlotMismatch mismatch : this.slotMismatches) {
                builder.append(mismatch.slot())
                        .append(':')
                        .append(mismatch.status().name())
                        .append(';');
            }

            return builder.toString();
        }
    }

    public record ExpectedContainer(
            BlockPos pos,
            BlockState state,
            BlockEntity blockEntity,
            Inventory inventory,
            Set<Integer> disabledSlots
    ) {
    }

    public enum ActualInventoryReadStatus {
        NOT_READ,
        NO_WORLD,
        DIRECT_INVENTORY,
        INTEGRATED_DIRECT,
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

    public record GhostItemDraw(ItemStack stack, int x, int y) {
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

        List<ContainerMismatch> quickcraft$refreshContainerMismatchAt(BlockPos pos, Inventory foundInventory, Set<Integer> foundDisabledSlots);
    }

    /**
     * 给 Litematica 的 BlockMismatch 挂接容器校验附加数据。
     * 这里保存容器对比结果、库存引用和禁用槽位信息，供列表与悬浮预览复用。
     */
    public interface BlockMismatchExtension {
        void quickcraft$setInventories(Pair<Inventory, Inventory> inventories);

        Pair<Inventory, Inventory> quickcraft$getInventories();

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
}
