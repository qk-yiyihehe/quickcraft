package com.yiyihehe.quickcraft.litematica;

import com.chocohead.mm.api.ClassTinkerers;
import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.QuickCraft;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.MinecraftClientAccessor;
import net.fabricmc.loader.api.FabricLoader;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.EntitiesDataStorage;
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
import fi.dy.masa.malilib.util.game.BlockUtils;
import fi.dy.masa.malilib.util.nbt.NbtBlockUtils;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BrewingStandBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.CrafterScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * QuickCraft 的 Litematica 容器验证主类。
 * 这里统一收口原理图容器校验相关能力，包括：
 * 1. 容器库存和禁用槽位的比对与错填分类；
 * 2. 验证结果刷新、当前界面联动和容器错填重算；
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
    private static boolean suppressInventorySlotHighlights;
    private static BlockPos pendingContainerPos;
    private static BlockPos currentScreenContainerPos;
    private static HandledScreen<?> currentHandledScreen;
    private static long lastCurrentScreenRefreshTick = Long.MIN_VALUE;

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

    public static boolean shouldCheckInventories(BlockEntity expected, BlockEntity found) {
        return isEnabled()
                && expected instanceof Inventory expectedInventory
                && found instanceof Inventory foundInventory
                && expected.getType() == found.getType()
                && expectedInventory.size() == foundInventory.size();
    }

    public static Inventory getExpectedInventory(BlockEntity expectedBlockEntity, Inventory directInventory) {
        if (expectedBlockEntity == null || expectedBlockEntity.getWorld() == null) {
            return directInventory;
        }

        NbtCompound nbt = expectedBlockEntity.createNbtWithIdentifyingData(expectedBlockEntity.getWorld().getRegistryManager());

        if (nbt.contains("Items")) {
            Inventory nbtInventory = fi.dy.masa.malilib.util.InventoryUtils.getNbtInventory(
                    nbt,
                    directInventory != null ? directInventory.size() : -1,
                    expectedBlockEntity.getWorld().getRegistryManager()
            );

            if (nbtInventory != null) {
                return nbtInventory;
            }
        }

        return directInventory;
    }

    public static Inventory getActualInventory(World world, BlockPos pos, Inventory directInventory, Inventory expected) {
        if (world == null) {
            return null;
        }

        if (DataManager.getInstance().hasIntegratedServer()) {
            Inventory mergedOrDirect = getDirectInventory(world, pos, expected != null ? expected.size() : -1);
            return mergedOrDirect != null ? mergedOrDirect : directInventory;
        }

        EntitiesDataStorage storage = EntitiesDataStorage.getInstance();
        NbtCompound cachedNbt = storage.getFromBlockEntityCacheNbt(pos);

        if (cachedNbt != null && (cachedNbt.contains("Items") || isInventoryEmpty(expected))) {
            Inventory cachedInventory = storage.getBlockInventory(world, pos, true);

            if (cachedInventory != null) {
                return cachedInventory;
            }
        }

        // 多人没有实体数据时不要拿客户端空壳库存硬比，避免把未知误报成错误填充。
        storage.requestBlockEntity(world, pos);
        return null;
    }

    public static void requestInventoryData(World world, BlockPos pos) {
        if (world != null) {
            EntitiesDataStorage.getInstance().requestBlockEntity(world, pos);
        }
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
        return findMismatches(
                pos,
                expectedState,
                foundState,
                expectedBlockEntity,
                foundBlockEntity,
                getDisabledSlots(expectedBlockEntity),
                getDisabledSlots(foundBlockEntity),
                expected,
                found
        );
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
        Inventory expectedCopy = copyInventory(expected);
        Inventory foundCopy = copyInventory(found);
        List<SlotMismatch> slotMismatches = new ArrayList<>();
        boolean hasWrongItem = false;
        boolean hasMissing = false;
        boolean hasStateMismatch = false;
        boolean hasExpectedFilledSlot = false;
        boolean allExpectedFilledSlotsMissing = true;

        for (int slot = 0; slot < expected.size(); slot++) {
            ItemStack expectedStack = expected.getStack(slot);
            ItemStack foundStack = found.getStack(slot);
            SlotMismatchStatus status = getSlotMismatchStatus(expectedStack, foundStack);

            if (!expectedStack.isEmpty()) {
                hasExpectedFilledSlot = true;
                if (!foundStack.isEmpty()) {
                    allExpectedFilledSlotsMissing = false;
                }
            }

            if (status != null) {
                slotMismatches.add(new SlotMismatch(slot, status, expectedStack.copy(), foundStack.copy()));

                if (status == SlotMismatchStatus.WRONG || status == SlotMismatchStatus.EXTRA) {
                    hasWrongItem = true;
                } else if (status == SlotMismatchStatus.MISSING) {
                    hasMissing = true;
                } else if (status == SlotMismatchStatus.COUNT) {
                    hasStateMismatch = true;
                }
            }
        }

        if (!expectedDisabledSlots.equals(foundDisabledSlots)) {
            for (int slot : unionSlots(expectedDisabledSlots, foundDisabledSlots)) {
                if (expectedDisabledSlots.contains(slot) != foundDisabledSlots.contains(slot)) {
                    addSlotStatusIfEmpty(slotMismatches, slot, SlotMismatchStatus.LOCK_STATE, expected.getStack(slot), found.getStack(slot));
                }
            }

            hasStateMismatch = true;
        }

        if (slotMismatches.isEmpty()) {
            return List.of();
        }

        SlotMismatch first = slotMismatches.getFirst();
        MismatchType type;

        if (hasWrongItem) {
            type = WRONG_FILL;
        } else if (hasExpectedFilledSlot && allExpectedFilledSlotsMissing) {
            type = MISSING_FILL;
        } else if (hasStateMismatch) {
            type = WRONG_FILL_STATE;
        } else if (hasMissing) {
            type = MISSING_FILL;
        } else {
            type = WRONG_FILL;
        }

        return List.of(new ContainerMismatch(
                pos,
                expectedState,
                foundState,
                first.slot(),
                type,
                first.expectedStack(),
                first.foundStack(),
                expectedCopy,
                foundCopy,
                expectedDisabledSlots,
                foundDisabledSlots,
                List.copyOf(slotMismatches)
        ));
    }

    public static boolean inventoriesMatch(
            BlockEntity expectedBlockEntity,
            BlockEntity foundBlockEntity,
            Inventory expected,
            Inventory found
    ) {
        if (expected.size() != found.size()) {
            return false;
        }

        for (int slot = 0; slot < expected.size(); slot++) {
            if (getSlotMismatchStatus(expected.getStack(slot), found.getStack(slot)) != null) {
                return false;
            }
        }

        return getDisabledSlots(expectedBlockEntity).equals(getDisabledSlots(foundBlockEntity));
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
        Pair<Inventory, Inventory> inventories = mismatch != null ? mismatch.inventories() : null;

        if (inventories == null) {
            return;
        }

        renderInventoryOverlay(BlockInfoAlignment.CENTER, LeftRight.LEFT, 0, mismatch.type(), inventories.getLeft(), expectedState, expectedDisabledSlots, List.of(), false, mouseX, mouseY, mc, drawContext);
        renderInventoryOverlay(BlockInfoAlignment.CENTER, LeftRight.RIGHT, 0, mismatch.type(), inventories.getRight(), foundState, foundDisabledSlots, mismatch.slotMismatches(), true, mouseX, mouseY, mc, drawContext);
    }

    public static boolean shouldSuppressInventorySlotHighlights() {
        return suppressInventorySlotHighlights && isEnabled();
    }

    public static void setSuppressInventorySlotHighlights(boolean suppress) {
        suppressInventorySlotHighlights = suppress;
    }

    public static void clearCurrentHandledScreenBinding() {
        pendingContainerPos = null;
        currentScreenContainerPos = null;
        currentHandledScreen = null;
        lastCurrentScreenRefreshTick = Long.MIN_VALUE;
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
        GhostItemBuffer.drawGhostItem(context, client, stack, x, y, guiLeft, guiTop, alpha);
    }

    public static boolean beginHandledScreenGhostRender(DrawContext context, MinecraftClient client) {
        return GhostItemBuffer.beginHandledScreenGhostRender(context, client);
    }

    public static void endHandledScreenGhostRender(DrawContext context, int guiLeft, int guiTop, float alpha) {
        GhostItemBuffer.endHandledScreenGhostRender(context, guiLeft, guiTop, alpha);
    }

    public static void rememberContainerUse(MinecraftClient client, BlockHitResult hitResult) {
        if (!isEnabled() || client.world == null) {
            return;
        }

        World world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        BlockPos pos = hitResult.getBlockPos();
        BlockEntity blockEntity = world != null ? world.getBlockEntity(pos) : client.world.getBlockEntity(pos);

        if (blockEntity instanceof Inventory || getExpectedContainerAt(world, pos) != null) {
            pendingContainerPos = pos.toImmutable();
        }
    }

    public static SlotOverlay getSlotOverlayForScreen(HandledScreen<?> screen, Slot slot) {
        if (!areSlotHintsVisible()
                || QuickContainerCopy.shouldHideBackgroundHandledScreen()
                || slot.inventory instanceof PlayerInventory) {
            return null;
        }

        bindCurrentScreen(screen);
        refreshCurrentScreenVerifier(screen);

        if (currentScreenContainerPos == null) {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        World world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        ExpectedContainer expectedContainer = getExpectedContainerAt(world, currentScreenContainerPos);

        if (expectedContainer == null || slot.getIndex() < 0 || slot.getIndex() >= expectedContainer.inventory().size()) {
            return null;
        }

        ItemStack expectedStack = expectedContainer.inventory().getStack(slot.getIndex());
        SlotMismatchStatus status = getSlotMismatchStatus(expectedStack, slot.getStack());

        if (status == null && isSlotLockMismatch(
                expectedContainer.disabledSlots(),
                copyCrafterDisabledSlotsFromScreen(screen.getScreenHandler()),
                slot.getIndex()
        )) {
            status = SlotMismatchStatus.LOCK_STATE;
        }

        return status != null ? new SlotOverlay(status, expectedStack.copy()) : null;
    }

    private static SlotMismatchStatus getSlotMismatchStatus(ItemStack expectedStack, ItemStack foundStack) {
        boolean expectedEmpty = expectedStack.isEmpty();
        boolean foundEmpty = foundStack.isEmpty();

        if (expectedEmpty && foundEmpty) {
            return null;
        }
        if (!expectedEmpty && foundEmpty) {
            return SlotMismatchStatus.MISSING;
        }
        if (expectedEmpty) {
            return SlotMismatchStatus.EXTRA;
        }
        if (!ItemStack.areItemsAndComponentsEqual(expectedStack, foundStack)) {
            return SlotMismatchStatus.WRONG;
        }
        if (expectedStack.getCount() != foundStack.getCount()) {
            return SlotMismatchStatus.COUNT;
        }

        return null;
    }

    private static boolean isSlotLockMismatch(Set<Integer> expectedDisabledSlots, Set<Integer> foundDisabledSlots, int slot) {
        return expectedDisabledSlots.contains(slot) != foundDisabledSlots.contains(slot);
    }

    public static ExpectedContainer getExpectedContainerAt(World foundWorld, BlockPos pos) {
        if (foundWorld == null) {
            return null;
        }

        ExpectedContainer current = getExpectedContainerInternal(pos);

        if (current == null) {
            return null;
        }

        ExpectedContainer merged = getExpectedDoubleChestContainer(pos, current);

        return merged != null ? merged : current;
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshotAt(World foundWorld, BlockPos pos) {
        ExpectedContainer expected = getExpectedContainerAt(foundWorld, pos);
        if (expected == null) {
            return null;
        }

        QuickContainerCopy.PublicContainerType type = QuickContainerCopy.getPublicContainerType(
                expected.state().getBlock(),
                getChestType(expected.state())
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

    private static Inventory getDirectInventory(World world, BlockPos pos, int expectedSize) {
        Inventory merged = getMergedDoubleChestInventory(world, pos);

        if (merged != null && (expectedSize < 0 || merged.size() == expectedSize)) {
            return merged;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity instanceof Inventory inventory ? inventory : null;
    }

    public static Set<Integer> getDisabledSlots(BlockEntity blockEntity) {
        if (blockEntity != null && blockEntity.getWorld() != null) {
            NbtCompound nbt = blockEntity.createNbtWithIdentifyingData(blockEntity.getWorld().getRegistryManager());
            return getDisabledSlots(blockEntity, nbt);
        }

        if (blockEntity instanceof CrafterBlockEntity crafter) {
            return Set.copyOf(BlockUtils.getDisabledSlots(crafter));
        }

        return Set.of();
    }

    private static void addSlotStatusIfEmpty(
            List<SlotMismatch> slotMismatches,
            int slot,
            SlotMismatchStatus status,
            ItemStack expectedStack,
            ItemStack foundStack
    ) {
        for (SlotMismatch mismatch : slotMismatches) {
            if (mismatch.slot() == slot) {
                return;
            }
        }

        slotMismatches.add(new SlotMismatch(slot, status, expectedStack.copy(), foundStack.copy()));
    }

    private static Set<Integer> unionSlots(Set<Integer> left, Set<Integer> right) {
        java.util.HashSet<Integer> slots = new java.util.HashSet<>(left);
        slots.addAll(right);
        return slots;
    }

    private static void bindCurrentScreen(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.currentScreen != screen) {
            return;
        }
        if (currentHandledScreen != screen) {
            currentHandledScreen = screen;
            currentScreenContainerPos = pendingContainerPos;
            pendingContainerPos = null;
            lastCurrentScreenRefreshTick = Long.MIN_VALUE;
        }

        if (currentScreenContainerPos == null) {
            currentScreenContainerPos = getLookedAtInventoryPos(client);
        }
    }

    private static void refreshCurrentScreenVerifier(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world == null
                || currentScreenContainerPos == null
                || client.world.getTime() == lastCurrentScreenRefreshTick) {
            return;
        }

        lastCurrentScreenRefreshTick = client.world.getTime();
        Inventory foundInventory = copyContainerInventoryFromScreen(screen.getScreenHandler());

        if (foundInventory == null) {
            return;
        }

        Set<Integer> foundDisabledSlots = copyCrafterDisabledSlotsFromScreen(screen.getScreenHandler());

        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

        if (placement != null && placement.hasVerifier()) {
            ((VerifierExtension) placement.getSchematicVerifier())
                    .quickcraft$refreshContainerMismatchAt(currentScreenContainerPos, foundInventory, foundDisabledSlots);
        }
    }

    private static BlockPos getLookedAtInventoryPos(MinecraftClient client) {
        if (!(client.crosshairTarget instanceof BlockHitResult blockHitResult)
                || blockHitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        World world = fi.dy.masa.malilib.util.WorldUtils.getBestWorld(client);
        BlockEntity blockEntity = world != null
                ? world.getBlockEntity(blockHitResult.getBlockPos())
                : client.world.getBlockEntity(blockHitResult.getBlockPos());

        return blockEntity instanceof Inventory ? blockHitResult.getBlockPos().toImmutable() : null;
    }

    private static Inventory copyContainerInventoryFromScreen(ScreenHandler handler) {
        int maxIndex = -1;

        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory) && slot.getIndex() > maxIndex) {
                maxIndex = slot.getIndex();
            }
        }

        if (maxIndex < 0) {
            return null;
        }

        SimpleInventory inventory = new SimpleInventory(maxIndex + 1);

        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory)
                    && slot.getIndex() >= 0
                    && slot.getIndex() < inventory.size()) {
                inventory.setStack(slot.getIndex(), slot.getStack().copy());
            }
        }

        return inventory;
    }

    private static Set<Integer> copyCrafterDisabledSlotsFromScreen(ScreenHandler handler) {
        if (!(handler instanceof CrafterScreenHandler crafterHandler)) {
            return Set.of();
        }

        Set<Integer> disabledSlots = new HashSet<>();

        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory || slot.getIndex() < 0) {
                continue;
            }

            if (crafterHandler.isSlotDisabled(slot.id)) {
                disabledSlots.add(slot.getIndex());
            }
        }

        return disabledSlots;
    }

    private static ExpectedContainer getExpectedDoubleChestContainer(BlockPos pos, ExpectedContainer current) {
        ChestType chestType = getChestType(current.state());

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = pos.add(ChestBlock.getFacing(current.state()).getVector());
        ExpectedContainer adjacent = getExpectedContainerInternal(adjacentPos);

        if (adjacent == null) {
            return null;
        }

        Inventory currentInventory = current.inventory();
        Inventory adjacentInventory = adjacent.inventory();
        Inventory merged = chestType == ChestType.RIGHT
                ? mergeInventories(currentInventory, adjacentInventory)
                : mergeInventories(adjacentInventory, currentInventory);

        return new ExpectedContainer(
                pos,
                current.state(),
                current.blockEntity(),
                merged,
                Set.of()
        );
    }

    private static ExpectedContainer getExpectedContainerInternal(BlockPos worldPos) {
        LocalPlacementPos placementPos = getLocalPlacementPos(worldPos);

        if (placementPos == null) {
            return null;
        }

        Map<BlockPos, NbtCompound> blockEntities = placementPos.placement().getSchematic()
                .getBlockEntityMapForRegion(placementPos.region());

        if (blockEntities == null) {
            return null;
        }

        NbtCompound nbt = blockEntities.get(placementPos.pos());

        if (nbt == null) {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world == null) {
            return null;
        }

        BlockEntity blockEntity = BlockEntity.createFromNbt(
                placementPos.pos(),
                placementPos.rawState(),
                nbt,
                client.world.getRegistryManager()
        );

        if (!(blockEntity instanceof Inventory inventory)) {
            return null;
        }

        return new ExpectedContainer(
                worldPos,
                placementPos.worldState(),
                blockEntity,
                copyInventory(inventory),
                getDisabledSlots(blockEntity, nbt)
        );
    }

    private static LocalPlacementPos getLocalPlacementPos(BlockPos worldPos) {
        List<SchematicPlacementManager.PlacementPart> parts = DataManager.getSchematicPlacementManager()
                .getAllPlacementsTouchingChunk(worldPos);

        for (SchematicPlacementManager.PlacementPart part : parts) {
            if (!part.getBox().containsPos(worldPos)) {
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

    private static Inventory getMergedDoubleChestInventory(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        ChestType chestType = getChestType(state);

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = pos.add(ChestBlock.getFacing(state).getVector());
        BlockEntity current = world.getBlockEntity(pos);
        BlockEntity adjacent = world.getBlockEntity(adjacentPos);

        if (!(current instanceof Inventory currentInventory)
                || !(adjacent instanceof Inventory adjacentInventory)) {
            return null;
        }

        return chestType == ChestType.RIGHT
                ? mergeInventories(currentInventory, adjacentInventory)
                : mergeInventories(adjacentInventory, currentInventory);
    }

    private static Inventory mergeInventories(Inventory first, Inventory second) {
        SimpleInventory inventory = new SimpleInventory(first.size() + second.size());

        for (int i = 0; i < first.size(); i++) {
            inventory.setStack(i, first.getStack(i).copy());
        }

        for (int i = 0; i < second.size(); i++) {
            inventory.setStack(first.size() + i, second.getStack(i).copy());
        }

        return inventory;
    }

    private static ChestType getChestType(BlockState state) {
        return state.getBlock() instanceof ChestBlock ? state.get(ChestBlock.CHEST_TYPE) : ChestType.SINGLE;
    }

    private static Set<Integer> getDisabledSlots(BlockEntity blockEntity, NbtCompound nbt) {
        // 投影和实际世界都优先按 NBT 里的 disabled_slots 比较，避免两边来源不同导致合成器锁槽误判。
        if (nbt != null && nbt.contains("disabled_slots")) {
            return Set.copyOf(NbtBlockUtils.getDisabledSlotsFromNbt(nbt));
        }

        if (blockEntity instanceof CrafterBlockEntity crafter) {
            return Set.copyOf(BlockUtils.getDisabledSlots(crafter));
        }

        return Set.of();
    }

    private static SimpleInventory copyInventory(Inventory source) {
        SimpleInventory copy = new SimpleInventory(source.size());

        for (int i = 0; i < source.size(); i++) {
            copy.setStack(i, source.getStack(i).copy());
        }

        return copy;
    }

    private static void renderInventoryOverlay(
            BlockInfoAlignment align,
            LeftRight side,
            int offY,
            MismatchType mismatchType,
            Inventory inventory,
            BlockState state,
            Set<Integer> disabledSlots,
            List<SlotMismatch> slotMismatches,
            boolean renderGhostStacks,
            double mouseX,
            double mouseY,
            MinecraftClient mc,
            DrawContext drawContext
    ) {
        InventoryOverlay.InventoryRenderType type = getInventoryType(inventory, state);
        InventoryOverlay.InventoryProperties props = InventoryOverlay.getInventoryPropsTemp(type, inventory.size());
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

        fi.dy.masa.malilib.render.RenderUtils.color(1f, 1f, 1f, 1f);
        InventoryOverlay.renderInventoryBackground(type, xInv, yInv, props.slotsPerRow, props.totalSlots, mc);
        drawSlotHighlights(drawContext, type, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, slotMismatches);
        InventoryOverlay.renderInventoryStacks(type, inventory, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, 0, inventory.size(), disabledSlots, mc, drawContext);

        if (renderGhostStacks) {
            drawMissingGhostStacks(drawContext, mc, type, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, slotMismatches);
        }
    }

    private static void drawSlotHighlights(
            DrawContext drawContext,
            InventoryOverlay.InventoryRenderType type,
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
            DrawContext drawContext,
            MinecraftClient mc,
            InventoryOverlay.InventoryRenderType type,
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
            drawOutline(drawContext, x, y, 16, 16, mismatch.status().borderColor());
        }
    }

    private static SlotPosition getInventoryOverlaySlotPosition(
            InventoryOverlay.InventoryRenderType type,
            int xSlots,
            int ySlots,
            int slotsPerRow,
            int slot
    ) {
        // 炉子类和酿造台在 malilib 的 InventoryOverlay 里不是普通网格槽位。
        if (type == InventoryOverlay.InventoryRenderType.FURNACE) {
            return switch (slot) {
                case 0 -> new SlotPosition(xSlots + 8, ySlots + 8);
                case 1 -> new SlotPosition(xSlots + 8, ySlots + 44);
                case 2 -> new SlotPosition(xSlots + 68, ySlots + 26);
                default -> getGridSlotPosition(xSlots, ySlots, slotsPerRow, slot);
            };
        }

        if (type == InventoryOverlay.InventoryRenderType.BREWING_STAND) {
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

    private static void drawOutline(DrawContext drawContext, int x, int y, int width, int height, int color) {
        drawContext.fill(x, y, x + width, y + 1, color);
        drawContext.fill(x, y + height - 1, x + width, y + height, color);
        drawContext.fill(x, y + 1, x + 1, y + height - 1, color);
        drawContext.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    private static InventoryOverlay.InventoryRenderType getInventoryType(Inventory inventory, BlockState state) {
        if (state != null) {
            if (state.getBlock() instanceof AbstractFurnaceBlock) {
                return InventoryOverlay.InventoryRenderType.FURNACE;
            }
            if (state.getBlock() instanceof BrewingStandBlock) {
                return InventoryOverlay.InventoryRenderType.BREWING_STAND;
            }
            if (state.getBlock() instanceof CrafterBlock) {
                return InventoryOverlay.InventoryRenderType.CRAFTER;
            }
            if (state.getBlock() instanceof DispenserBlock) {
                return InventoryOverlay.InventoryRenderType.DISPENSER;
            }
            if (state.getBlock() instanceof HopperBlock) {
                return InventoryOverlay.InventoryRenderType.HOPPER;
            }
        }

        return switch (inventory.size()) {
            case 3 -> InventoryOverlay.InventoryRenderType.FURNACE;
            case 5 -> InventoryOverlay.InventoryRenderType.HOPPER;
            case 9 -> InventoryOverlay.InventoryRenderType.DISPENSER;
            case 27 -> InventoryOverlay.InventoryRenderType.FIXED_27;
            case 54 -> InventoryOverlay.InventoryRenderType.FIXED_54;
            default -> InventoryOverlay.InventoryRenderType.GENERIC;
        };
    }

    private static boolean isInventoryEmpty(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStack(slot).isEmpty()) {
                return false;
            }
        }

        return true;
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

        int quickcraft$getWrongInventoryCount();

        int quickcraft$getContainerMismatchCount(MismatchType type);

        int quickcraft$getExpectedContainerCount();

        int quickcraft$getCheckedContainerCount();

        int quickcraft$getPendingContainerCount();

        void quickcraft$refreshContainerMismatchAt(BlockPos pos, Inventory foundInventory, Set<Integer> foundDisabledSlots);
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

    private static final class GhostItemBuffer {
        private static final RenderLayer TRANSPARENCY_LAYER = RenderLayer.of(
                QuickCraft.MOD_ID + "_ghost_item_transparency",
                VertexFormats.POSITION_TEXTURE,
                VertexFormat.DrawMode.QUADS,
                1536,
                false,
                true,
                RenderLayer.MultiPhaseParameters.builder()
                        .texture(RenderPhase.NO_TEXTURE)
                        .program(RenderPhase.POSITION_TEXTURE_PROGRAM)
                        .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                        .build(false)
        );
        private static SimpleFramebuffer framebuffer;
        private static Framebuffer previousFramebuffer;

        private GhostItemBuffer() {
        }

        private static boolean beginHandledScreenGhostRender(DrawContext context, MinecraftClient client) {
            if (client == null) {
                return false;
            }

            SimpleFramebuffer ghostFramebuffer = getFramebuffer(client);
            // 先把当前 GUI 的批处理刷到主帧缓冲，避免背景/文字误进幽灵缓冲。
            context.draw();
            ghostFramebuffer.clear();
            previousFramebuffer = client.getFramebuffer();
            ((MinecraftClientAccessor) client).quickcraft$setFramebuffer(ghostFramebuffer);
            return true;
        }

        private static void endHandledScreenGhostRender(DrawContext context, int guiLeft, int guiTop, float alpha) {
            if (previousFramebuffer == null || framebuffer == null) {
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            MatrixStack matrices = context.getMatrices();
            context.draw();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);

            matrices.push();
            matrices.translate(-guiLeft, -guiTop, 0.0F);
            ((MinecraftClientAccessor) client).quickcraft$setFramebuffer(previousFramebuffer);
            drawFramebuffer(context, framebuffer);
            matrices.pop();

            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            previousFramebuffer = null;
        }

        private static void drawGhostItem(
                DrawContext context,
                MinecraftClient client,
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

            float clampedAlpha = Math.max(0.0F, Math.min(1.0F, alpha));
            if (!beginHandledScreenGhostRender(context, client)) {
                return;
            }

            context.drawItem(stack, x, y);
            context.drawStackOverlay(client.textRenderer, stack, x, y);
            context.draw();
            endHandledScreenGhostRender(context, guiLeft, guiTop, clampedAlpha);
        }

        private static SimpleFramebuffer getFramebuffer(MinecraftClient client) {
            Window window = client.getWindow();

            if (framebuffer == null) {
                framebuffer = new SimpleFramebuffer(
                        window.getFramebufferWidth(),
                        window.getFramebufferHeight(),
                        true
                );
                framebuffer.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            }

            if (framebuffer.textureWidth != window.getFramebufferWidth()
                    || framebuffer.textureHeight != window.getFramebufferHeight()) {
                framebuffer.resize(window.getFramebufferWidth(), window.getFramebufferHeight());
                framebuffer.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            }

            return framebuffer;
        }

        private static void drawFramebuffer(DrawContext context, Framebuffer ghostFramebuffer) {
            RenderSystem.setShaderTexture(0, ghostFramebuffer.getColorAttachment());
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(
                            0.0F,
                            (float) context.getScaledWindowWidth(),
                            (float) context.getScaledWindowHeight(),
                            0.0F,
                            1000.0F,
                            21000.0F
                    ),
                    ProjectionType.ORTHOGRAPHIC
            );

            Matrix4f posMat = context.getMatrices().peek().getPositionMatrix();
            float u1 = 0.0F;
            float u2 = ghostFramebuffer.textureWidth / (float) ghostFramebuffer.textureWidth;
            float v1 = ghostFramebuffer.textureHeight / (float) ghostFramebuffer.textureHeight;
            float v2 = 0.0F;

            context.draw(vertexConsumerProvider -> {
                VertexConsumer vertexConsumer = vertexConsumerProvider.getBuffer(TRANSPARENCY_LAYER);
                vertexConsumer.vertex(posMat, 0.0F, 0.0F, 0.0F).texture(u1, v1);
                vertexConsumer.vertex(posMat, 0.0F, context.getScaledWindowHeight(), 0.0F).texture(u1, v2);
                vertexConsumer.vertex(posMat, context.getScaledWindowWidth(), context.getScaledWindowHeight(), 0.0F).texture(u2, v2);
                vertexConsumer.vertex(posMat, context.getScaledWindowWidth(), 0.0F, 0.0F).texture(u2, v1);
            });
            RenderSystem.restoreProjectionMatrix();
        }
    }
}
