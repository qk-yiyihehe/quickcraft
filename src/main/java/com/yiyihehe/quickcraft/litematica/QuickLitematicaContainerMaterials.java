package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickMaterialCollector;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.malilib.QuickCraftGuiButtonAccess;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.GuiMainMenu.ButtonListenerChangeMenu;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 给 Litematica 的加载原理图页面补一个“容器材料列表”。
 * 这里按容器内容分组，而不是把所有容器物品直接拍平成一张总材料表。
 */
public final class QuickLitematicaContainerMaterials {
    public static final String BUTTON_KEY = "quickcraft.litematica.button.container_material_list";
    public static final String BUTTON_HOVER_KEY = "quickcraft.litematica.button.hover.container_material_list";

    static final int BUTTON_GAP = 4;
    static final int CONTAINER_COLUMN_WIDTH = 188;
    static final int COUNT_COLUMN_WIDTH = 58;
    static final int ACTION_COLUMN_WIDTH = 66;
    static final int ITEM_CELL_WIDTH = 34;
    static final int ITEM_CELL_HEIGHT = 20;
    static final int HEADER_HEIGHT = 22;
    private static final Set<String> NON_INVENTORY_BLOCK_ENTITY_IDS = new HashSet<>();
    private static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[] {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private QuickLitematicaContainerMaterials() {
    }

    /** 物品分组键沿用 malilib 的数量无关、组件敏感比较，版本后端负责原生键。 */
    abstract static class ItemKey {
        @Override
        public abstract boolean equals(Object other);

        @Override
        public abstract int hashCode();

        @Override
        public abstract String toString();
    }

    public interface ContainerMaterialRequestSource {
        List<QuickMaterialCollector.MaterialRequest> quickcraft$getReplacementMaterialRequests();
    }

    public static boolean shouldShowButton() {
        return QuickCraftConfigs.isLitematicaContainerMaterialListButtonVisible();
    }

    public static ButtonPlacement getButtonPlacement(GuiSchematicLoad gui, int buttonWidth) {
        int y = gui.getScreenHeight() - 26;
        List<ButtonBase> buttons = ((QuickCraftGuiButtonAccess) (Object) gui).quickcraft$getButtons();
        ButtonBase mainMenuButton = buttons.stream()
                .filter(button -> button.getY() == y)
                // 原版主菜单按钮固定在距右边缘 10 px 的位置。
                .filter(button -> button.getX() + button.getWidth() == gui.getScreenWidth() - 10)
                .reduce((first, second) -> second)
                .orElse(null);
        int mainMenuX = mainMenuButton != null ? mainMenuButton.getX() : gui.getScreenWidth() - 10;
        int x = buttons.stream()
                .filter(button -> button.getY() == y && button != mainMenuButton)
                .mapToInt(button -> button.getX() + button.getWidth() + BUTTON_GAP)
                .max()
                .orElse(12);

        if (x + buttonWidth <= mainMenuX - BUTTON_GAP) {
            return new ButtonPlacement(x, y);
        }

        // 文件浏览区结束于 height - 46；空间不足时换到预览区下方，不移动原生按钮。
        return new ButtonPlacement(
                Math.max(12, gui.getScreenWidth() - buttonWidth - 10),
                gui.getScreenHeight() - 46
        );
    }

    public static void openForEntry(GuiSchematicLoad gui, DirectoryEntry entry) {
        if (entry == null) {
            gui.addMessage(MessageType.ERROR, "quickcraft.litematica.error.no_schematic_selected");
            return;
        }

        Path file = entryPath(entry);
        if (!Files.exists(file) || !Files.isReadable(file)) {
            gui.addMessage(MessageType.ERROR, "litematica.error.schematic_load.cant_read_file", file.getFileName());
            return;
        }

        gui.setNextMessageType(MessageType.ERROR);
        LitematicaSchematic schematic = readSchematic(gui, entry);

        if (schematic == null) {
            return;
        }

        ContainerMaterialsData data = ContainerMaterialsData.create(schematic);
        ContainerMaterialList materialList = new ContainerMaterialList(data, gui, true);
        DataManager.setMaterialList(materialList);
        openMaterialListScreen(materialList);
    }

    public static void openForPlacement(SchematicPlacement placement, Screen parent) {
        ContainerMaterialList materialList = new ContainerMaterialList(
                ContainerMaterialsData.create(placement),
                parent,
                true
        );
        DataManager.setMaterialList(materialList);
        openMaterialListScreen(materialList);
    }

    public static void openDetailsForPlacement(SchematicPlacement placement, Screen parent) {
        ContainerMaterialList materialList = new ContainerMaterialList(
                ContainerMaterialsData.create(placement),
                parent,
                false
        );
        DataManager.setMaterialList(materialList);
        openDetailScreen(materialList);
    }

    public static void openForSchematic(LitematicaSchematic schematic, Collection<String> regions, Screen parent) {
        ContainerMaterialList materialList = new ContainerMaterialList(
                ContainerMaterialsData.create(schematic, regions),
                parent,
                true
        );
        DataManager.setMaterialList(materialList);
        openMaterialListScreen(materialList);
    }

    public static void openDetailsForSchematic(LitematicaSchematic schematic, Collection<String> regions, Screen parent) {
        ContainerMaterialList materialList = new ContainerMaterialList(
                ContainerMaterialsData.create(schematic, regions),
                parent,
                false
        );
        DataManager.setMaterialList(materialList);
        openDetailScreen(materialList);
    }

    private static void openMaterialListScreen(ContainerMaterialList materialList) {
        QuickLitematicaContainerMaterialsScreens.openMaterialListScreen(materialList);
    }

    private static void openDetailScreen(ContainerMaterialList materialList) {
        QuickLitematicaContainerMaterialsScreens.openDetailScreen(materialList);
    }

    private static LitematicaSchematic readSchematic(GuiSchematicLoad gui, DirectoryEntry entry) {
        FileType fileType = FileType.fromFile(entryPath(entry));

        return switch (fileType) {
            case LITEMATICA_SCHEMATIC -> LitematicaSchematic.createFromFile(entry.getDirectory(), entry.getName());
            case SCHEMATICA_SCHEMATIC -> WorldUtils.convertSchematicaSchematicToLitematicaSchematic(
                    entry.getDirectory(),
                    entry.getName(),
                    false,
                    gui
            );
            case VANILLA_STRUCTURE -> WorldUtils.convertStructureToLitematicaSchematic(entry.getDirectory(), entry.getName());
            case SPONGE_SCHEMATIC -> WorldUtils.convertSpongeSchematicToLitematicaSchematic(entry.getDirectory(), entry.getName());
            default -> {
                gui.addMessage(MessageType.ERROR, "litematica.error.schematic_load.unsupported_type", entryFileName(entry));
                yield null;
            }
        };
    }

    private static Path entryPath(DirectoryEntry entry) {
        return QuickLitematicaContainerMaterialsAccess.getEntryPath(entry);
    }

    private static String entryFileName(DirectoryEntry entry) {
        return QuickLitematicaContainerMaterialsAccess.getEntryFileName(entry);
    }

    private static RegistryWrapper.WrapperLookup getRegistryLookup() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.world != null ? client.world.getRegistryManager() : null;
    }

    private static List<ContainerGroup> createContainerGroups(
            LitematicaSchematic schematic,
            Collection<String> regions,
            @Nullable SchematicPlacement placement,
            BlockInfoListType materialListType
    ) {
        RegistryWrapper.WrapperLookup registryLookup = getRegistryLookup();
        GroupAccumulator accumulator = new GroupAccumulator();
        boolean renderLayers = placement != null && materialListType == BlockInfoListType.RENDER_LAYERS;

        if (registryLookup == null) {
            return List.of();
        }

        for (String regionName : regions) {
            addBlockEntityContainers(schematic, regionName, registryLookup, accumulator, placement, renderLayers);
            addEntityContainers(schematic, regionName, registryLookup, accumulator, placement, renderLayers);
        }

        return accumulator.toGroups();
    }

    private static void addBlockEntityContainers(
            LitematicaSchematic schematic,
            String regionName,
            RegistryWrapper.WrapperLookup registryLookup,
            GroupAccumulator accumulator,
            @Nullable SchematicPlacement placement,
            boolean renderLayers
    ) {
        Map<BlockPos, NbtCompound> blockEntities = QuickLitematicaContainerMaterialsAccess.getBlockEntities(schematic, regionName);

        if (blockEntities == null || blockEntities.isEmpty()) {
            return;
        }

        LitematicaBlockStateContainer stateContainer = schematic.getSubRegionContainer(regionName);
        Set<BlockPos> consumed = new HashSet<>();

        for (Map.Entry<BlockPos, NbtCompound> entry : blockEntities.entrySet()) {
            BlockPos pos = entry.getKey();

            if (consumed.contains(pos)) {
                continue;
            }

            NbtCompound nbt = entry.getValue();
            if (nbt == null) {
                continue;
            }

            BlockState state = getState(stateContainer, pos);
            List<ItemStack> stacks = readBlockEntityItems(pos, state, nbt, registryLookup);
            if (stacks == null) {
                continue;
            }

            BlockPos pairedChestPos = findPairedChest(pos, state, stateContainer, blockEntities, consumed);

            if (renderLayers
                    && !isWithinRenderLayer(placement, schematic, regionName, pos)
                    && (pairedChestPos == null || !isWithinRenderLayer(placement, schematic, regionName, pairedChestPos))) {
                continue;
            }

            if (stacks.isEmpty() && pairedChestPos == null) {
                continue;
            }

            consumed.add(pos);

            if (pairedChestPos != null) {
                NbtCompound pairedNbt = blockEntities.get(pairedChestPos);
                BlockState pairedState = getState(stateContainer, pairedChestPos);
                List<ItemStack> pairedStacks = readBlockEntityItems(
                        pairedChestPos,
                        pairedState,
                        pairedNbt,
                        registryLookup
                );
                if (pairedStacks != null) {
                    stacks.addAll(pairedStacks);
                }
                consumed.add(pairedChestPos);
            }

            ContainerDescriptor descriptor = describeBlockContainer(state, nbt, pairedChestPos != null);
            addContainer(accumulator, descriptor, stacks);
        }
    }

    private static void addEntityContainers(
            LitematicaSchematic schematic,
            String regionName,
            RegistryWrapper.WrapperLookup registryLookup,
            GroupAccumulator accumulator,
            @Nullable SchematicPlacement placement,
            boolean renderLayers
    ) {
        List<EntityInfo> entities = schematic.getEntityListForRegion(regionName);

        if (entities == null || entities.isEmpty()) {
            return;
        }

        for (EntityInfo info : entities) {
            NbtCompound nbt = QuickLitematicaContainerMaterialsAccess.getEntityNbt(info);
            if (nbt == null || !containsItemsList(nbt)) {
                continue;
            }
            if (renderLayers && !isWithinRenderLayer(placement, schematic, regionName, QuickLitematicaContainerMaterialsAccess.getEntityPos(info))) {
                continue;
            }

            List<ItemStack> stacks = readItems(nbt, registryLookup);

            if (stacks.isEmpty()) {
                continue;
            }

            ContainerDescriptor descriptor = describeEntityContainer(readString(nbt, "id"));
            addContainer(accumulator, descriptor, stacks);
        }
    }

    private static boolean isWithinRenderLayer(
            SchematicPlacement placement,
            LitematicaSchematic schematic,
            String regionName,
            BlockPos localPos
    ) {
        BlockPos regionSize = schematic.getAreaSize(regionName);
        SubRegionPlacement regionPlacement = placement.getRelativeSubRegionPlacement(regionName);

        if (regionSize == null || regionPlacement == null) {
            return false;
        }

        BlockPos regionPos = regionPlacement.getPos();
        BlockPos posEndRel = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).add(regionPos);
        BlockPos posMinRel = PositionUtils.getMinCorner(regionPos, posEndRel);
        BlockPos relative = posMinRel.subtract(regionPos).add(localPos);
        relative = PositionUtils.getTransformedBlockPos(relative, placement.getMirror(), placement.getRotation());
        relative = PositionUtils.getTransformedBlockPos(relative, regionPlacement.getMirror(), regionPlacement.getRotation());
        BlockPos worldPos = placement.getOrigin()
                .add(PositionUtils.getTransformedBlockPos(regionPos, placement.getMirror(), placement.getRotation()))
                .add(relative);
        return DataManager.getRenderLayerRange().isPositionWithinRange(worldPos.getX(), worldPos.getY(), worldPos.getZ());
    }

    private static boolean isWithinRenderLayer(
            SchematicPlacement placement,
            LitematicaSchematic schematic,
            String regionName,
            Vec3d localPos
    ) {
        BlockPos regionSize = schematic.getAreaSize(regionName);
        SubRegionPlacement regionPlacement = placement.getRelativeSubRegionPlacement(regionName);

        if (regionSize == null || regionPlacement == null) {
            return false;
        }

        BlockPos regionPos = regionPlacement.getPos();
        Vec3d transformed = PositionUtils.getTransformedPosition(localPos, placement.getMirror(), placement.getRotation());
        transformed = PositionUtils.getTransformedPosition(transformed, regionPlacement.getMirror(), regionPlacement.getRotation());
        BlockPos transformedRegionPos = PositionUtils.getTransformedBlockPos(regionPos, placement.getMirror(), placement.getRotation());
        Vec3d worldPos = new Vec3d(
                placement.getOrigin().getX() + transformedRegionPos.getX() + transformed.x,
                placement.getOrigin().getY() + transformedRegionPos.getY() + transformed.y,
                placement.getOrigin().getZ() + transformedRegionPos.getZ() + transformed.z
        );
        return DataManager.getRenderLayerRange().isPositionWithinRange(
                (int) Math.floor(worldPos.x),
                (int) Math.floor(worldPos.y),
                (int) Math.floor(worldPos.z)
        );
    }

    private static void addContainer(GroupAccumulator accumulator, ContainerDescriptor descriptor, List<ItemStack> stacks) {
        List<ItemStack> slotStacks = copyStacks(stacks);
        List<ItemCount> contents = countStacks(slotStacks);

        if (contents.isEmpty()) {
            return;
        }

        accumulator.add(descriptor.stack(), descriptor.displayName(), null, descriptor.signatureKey(), contents, slotStacks, 1, false);
        addStoredShulkerGroups(accumulator, descriptor.displayName(), descriptor.signatureKey(), contents, 0);
    }

    private static void addStoredShulkerGroups(
            GroupAccumulator accumulator,
            String sourceName,
            String sourceKey,
            List<ItemCount> containerContents,
            int depth
    ) {
        if (depth >= 4) {
            return;
        }

        for (ItemCount item : containerContents) {
            if (!isShulkerBox(item.stack())) {
                continue;
            }

            List<ItemStack> shulkerSlotStacks = readStoredShulkerStacks(item.stack());
            List<ItemCount> shulkerContents = countStacks(shulkerSlotStacks);

            if (shulkerContents.isEmpty()) {
                continue;
            }

            ItemStack shulkerStack = item.stack().copy();
            shulkerStack.setCount(1);
            String shulkerName = shulkerStack.getName().getString();
            String shulkerSource = StringUtils.translate("quickcraft.litematica.label.source", sourceName);
            String nestedSourceName = sourceName + " / " + shulkerName;
            String nestedSourceKey = sourceKey + "/" + itemSignature(shulkerStack);

            accumulator.add(shulkerStack, shulkerName, shulkerSource, nestedSourceKey, shulkerContents, shulkerSlotStacks, item.count(), true);
            addStoredShulkerGroups(accumulator, nestedSourceName, nestedSourceKey, shulkerContents, depth + 1);
        }
    }

    private static List<ItemStack> readItems(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        if (nbt == null || !containsItemsList(nbt)) {
            return List.of();
        }

        List<ItemStack> stacks = new ArrayList<>();
        NbtList items = readCompoundList(nbt, "Items");

        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = itemStackFromNbt(registryLookup, readCompound(items, i));

            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }

        return stacks;
    }

    private static ItemStack itemStackFromNbt(RegistryWrapper.WrapperLookup registryLookup, NbtCompound nbt) {
        return QuickLitematicaContainerMaterialsAccess.itemStackFromNbt(registryLookup, nbt);
    }

    private static boolean containsItemsList(NbtCompound nbt) {
        return QuickLitematicaContainerMaterialsAccess.containsItemsList(nbt);
    }

    private static String readString(NbtCompound nbt, String key) {
        return QuickLitematicaContainerMaterialsAccess.readString(nbt, key);
    }

    private static NbtList readCompoundList(NbtCompound nbt, String key) {
        return QuickLitematicaContainerMaterialsAccess.readCompoundList(nbt, key);
    }

    private static NbtCompound readCompound(NbtList list, int index) {
        return QuickLitematicaContainerMaterialsAccess.readCompound(list, index);
    }

    @Nullable
    private static List<ItemStack> readBlockEntityItems(
            BlockPos pos,
            BlockState state,
            NbtCompound nbt,
            RegistryWrapper.WrapperLookup registryLookup
    ) {
        if (nbt == null || registryLookup == null) {
            return null;
        }

        String blockEntityId = readString(nbt, "id");
        if (NON_INVENTORY_BLOCK_ENTITY_IDS.contains(blockEntityId)) {
            return null;
        }

        BlockEntity blockEntity = BlockEntity.createFromNbt(pos, state, nbt, registryLookup);
        if (!(blockEntity instanceof Inventory inventory)) {
            if (!blockEntityId.isEmpty()) {
                NON_INVENTORY_BLOCK_ENTITY_IDS.add(blockEntityId);
            }
            return null;
        }

        List<ItemStack> stacks = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        return stacks;
    }

    private static List<ItemCount> countStacks(List<ItemStack> stacks) {
        Object2IntOpenHashMap<ItemKey> counts = new Object2IntOpenHashMap<>();
        Map<ItemKey, ItemStack> displayStacks = new HashMap<>();

        for (ItemStack stack : stacks) {
            addStackCount(counts, displayStacks, stack, stack.getCount());
        }

        return toItemCounts(counts, displayStacks);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                copies.add(stack.copy());
            }
        }
        return copies;
    }

    private static List<ItemStack> readStoredShulkerStacks(ItemStack shulkerStack) {
        List<ItemStack> stacks = new ArrayList<>();
        DefaultedList<ItemStack> storedItems = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(shulkerStack);

        for (ItemStack storedStack : storedItems) {
            if (!storedStack.isEmpty()) {
                stacks.add(storedStack.copy());
            }
        }

        return stacks;
    }

    private static void addStackCount(
            Object2IntOpenHashMap<ItemKey> counts,
            Map<ItemKey, ItemStack> displayStacks,
            ItemStack stack,
            int count
    ) {
        ItemStack displayStack = stack.copy();
        displayStack.setCount(1);
        ItemKey key = QuickLitematicaContainerMaterialsAccess.createItemKey(displayStack);
        counts.addTo(key, count);
        displayStacks.putIfAbsent(key, displayStack);
    }

    private static List<ItemCount> toItemCounts(Object2IntOpenHashMap<ItemKey> counts, Map<ItemKey, ItemStack> displayStacks) {
        List<ItemCount> result = new ArrayList<>();

        for (ItemKey type : counts.keySet()) {
            ItemStack stack = displayStacks.get(type);

            if (stack != null && !stack.isEmpty()) {
                result.add(new ItemCount(stack, counts.getInt(type), itemSignature(stack)));
            }
        }

        result.sort(Comparator.comparing(ItemCount::signature));
        return result;
    }

    private static BlockState getState(LitematicaBlockStateContainer container, BlockPos pos) {
        if (container == null || pos == null) {
            return null;
        }

        Vec3i size = container.getSize();
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        if (x < 0 || y < 0 || z < 0 || x >= size.getX() || y >= size.getY() || z >= size.getZ()) {
            return null;
        }

        return container.get(x, y, z);
    }

    private static BlockPos findPairedChest(
            BlockPos pos,
            BlockState state,
            LitematicaBlockStateContainer stateContainer,
            Map<BlockPos, NbtCompound> blockEntities,
            Set<BlockPos> consumed
    ) {
        if (!(state != null && state.getBlock() instanceof ChestBlock)) {
            return null;
        }

        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            BlockPos otherPos = pos.offset(direction);

            if (consumed.contains(otherPos) || !blockEntities.containsKey(otherPos)) {
                continue;
            }

            BlockState otherState = getState(stateContainer, otherPos);

            if (otherState != null
                    && otherState.getBlock() == state.getBlock()
                    && otherState.getBlock() instanceof ChestBlock
                    && otherState.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                return otherPos;
            }
        }

        return null;
    }

    private static ContainerDescriptor describeBlockContainer(
            BlockState state,
            NbtCompound nbt,
            boolean largeChest
    ) {
        ItemStack stack = stackFromState(state);

        if (stack.isEmpty()) {
            stack = stackFromBlockEntityId(readString(nbt, "id"));
        }

        String displayName = stack.getName().getString();
        String signatureKey = itemSignature(stack);

        if (largeChest) {
            String key = stack.isOf(Items.TRAPPED_CHEST)
                    ? "quickcraft.litematica.container.large_trapped_chest"
                    : "quickcraft.litematica.container.large_chest";
            displayName = StringUtils.translate(key);
            signatureKey = "large_chest:" + signatureKey;
        }

        return new ContainerDescriptor(stack, displayName, signatureKey);
    }

    private static ContainerDescriptor describeEntityContainer(String id) {
        ItemStack stack = switch (id) {
            case "minecraft:hopper_minecart", "minecraft:minecart_hopper" -> new ItemStack(Items.HOPPER_MINECART);
            case "minecraft:chest_minecart", "minecraft:minecart_chest" -> new ItemStack(Items.CHEST_MINECART);
            default -> new ItemStack(Items.CHEST_MINECART);
        };

        return new ContainerDescriptor(stack, stack.getName().getString(), "entity:" + id + ":" + itemSignature(stack));
    }

    private static ItemStack stackFromState(BlockState state) {
        if (state == null || state.isAir()) {
            return ItemStack.EMPTY;
        }

        Item item = state.getBlock().asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static ItemStack stackFromBlockEntityId(String id) {
        return switch (id) {
            case "minecraft:barrel" -> new ItemStack(Items.BARREL);
            case "minecraft:chest" -> new ItemStack(Items.CHEST);
            case "minecraft:trapped_chest" -> new ItemStack(Items.TRAPPED_CHEST);
            case "minecraft:hopper" -> new ItemStack(Items.HOPPER);
            case "minecraft:dispenser" -> new ItemStack(Items.DISPENSER);
            case "minecraft:dropper" -> new ItemStack(Items.DROPPER);
            case "minecraft:furnace" -> new ItemStack(Items.FURNACE);
            case "minecraft:blast_furnace" -> new ItemStack(Items.BLAST_FURNACE);
            case "minecraft:smoker" -> new ItemStack(Items.SMOKER);
            case "minecraft:brewing_stand" -> new ItemStack(Items.BREWING_STAND);
            case "minecraft:crafter" -> new ItemStack(Items.CRAFTER);
            case "minecraft:shulker_box" -> new ItemStack(Items.SHULKER_BOX);
            default -> new ItemStack(Items.CHEST);
        };
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static String itemSignature(ItemStack stack) {
        return QuickLitematicaContainerMaterialsAccess.createItemKey(stack).toString();
    }

    private static String contentSignature(List<ItemCount> contents) {
        StringBuilder builder = new StringBuilder();

        for (ItemCount item : contents) {
            builder.append(item.signature()).append('#').append(item.count()).append(';');
        }

        return builder.toString();
    }

    static int getGroupHeight(ContainerGroup group, int rowWidth) {
        int columns = QuickLitematicaContainerMaterialsLayout.contentColumns(rowWidth);
        int rows = Math.max(1, (group.contents().size() + columns - 1) / columns);
        int contentHeight = rows * ITEM_CELL_HEIGHT + 8;
        return Math.max(group.sourceLabel() == null ? 30 : 40, contentHeight);
    }

    static String fitText(String text, int maxWidth) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }

        String suffix = "...";
        int suffixWidth = client.textRenderer.getWidth(suffix);

        while (!text.isEmpty() && client.textRenderer.getWidth(text) + suffixWidth > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }

        return text + suffix;
    }

    static String formatCount(int count) {
        if (count >= 1_000_000) {
            return count / 1_000_000 + "m";
        }
        if (count >= 10_000) {
            return count / 1_000 + "k";
        }

        return String.valueOf(count);
    }

    public record ButtonPlacement(int x, int y) {
    }

    private record ContainerDescriptor(ItemStack stack, String displayName, String signatureKey) {
    }

    record ItemCount(ItemStack stack, int count, String signature) {
        int totalCount(int multiplier) {
            return this.count * multiplier;
        }
    }

    record ContainerGroup(
            ItemStack containerStack,
            String containerName,
            String sourceLabel,
            int containerCount,
            List<ItemCount> contents,
            List<ItemStack> materialRequestStacks,
            boolean nestedShulker,
            String signature
    ) {
    }

    private static final class GroupAccumulator {
        private final Map<String, GroupBuilder> groups = new LinkedHashMap<>();

        private void add(
                ItemStack stack,
                String displayName,
                String sourceLabel,
                String sourceKey,
                List<ItemCount> contents,
                List<ItemStack> slotStacks,
                int count,
                boolean nestedShulker
        ) {
            String signature = (nestedShulker ? "shulker:" : "container:")
                    + sourceKey + "|" + itemSignature(stack) + "|" + contentSignature(contents);
            GroupBuilder builder = this.groups.computeIfAbsent(
                    signature,
                    key -> new GroupBuilder(stack.copy(), displayName, sourceLabel, contents, nestedShulker, signature)
            );
            builder.containerCount += count;
            builder.addMaterialRequestStacks(slotStacks, count);
        }

        private List<ContainerGroup> toGroups() {
            List<ContainerGroup> result = new ArrayList<>();

            for (GroupBuilder builder : this.groups.values()) {
                result.add(builder.toGroup());
            }

            result.sort(Comparator
                    .comparing(ContainerGroup::nestedShulker)
                    .thenComparing(ContainerGroup::containerName)
                    .thenComparing(ContainerGroup::signature));
            return result;
        }
    }

    private static final class GroupBuilder {
        private final ItemStack stack;
        private final String displayName;
        private final String sourceLabel;
        private final List<ItemCount> contents;
        private final List<ItemStack> materialRequestStacks = new ArrayList<>();
        private final boolean nestedShulker;
        private final String signature;
        private int containerCount;

        private GroupBuilder(
                ItemStack stack,
                String displayName,
                String sourceLabel,
                List<ItemCount> contents,
                boolean nestedShulker,
                String signature
        ) {
            this.stack = stack;
            this.displayName = displayName;
            this.sourceLabel = sourceLabel;
            this.contents = List.copyOf(contents);
            this.nestedShulker = nestedShulker;
            this.signature = signature;
        }

        private void addMaterialRequestStacks(List<ItemStack> slotStacks, int count) {
            for (int i = 0; i < count; i++) {
                this.materialRequestStacks.addAll(copyStacks(slotStacks));
            }
        }

        private ContainerGroup toGroup() {
            ItemStack displayStack = this.stack.copy();
            displayStack.setCount(1);
            return new ContainerGroup(
                    displayStack,
                    this.displayName,
                    this.sourceLabel,
                    this.containerCount,
                    this.contents,
                    copyStacks(this.materialRequestStacks),
                    this.nestedShulker,
                    this.signature
            );
        }
    }

    static final class ContainerMaterialsData {
        final LitematicaSchematic schematic;
        private final List<String> regions;
        final SchematicPlacement placement;
        private final Set<String> ignoredGroupSignatures = new HashSet<>();
        private List<ContainerGroup> groups;

        private ContainerMaterialsData(
                LitematicaSchematic schematic,
                List<String> regions,
                SchematicPlacement placement
        ) {
            this.schematic = schematic;
            this.regions = regions;
            this.placement = placement;
            this.groups = List.of();
        }

        private static ContainerMaterialsData create(LitematicaSchematic schematic) {
            return new ContainerMaterialsData(schematic, List.copyOf(schematic.getAreas().keySet()), null);
        }

        private static ContainerMaterialsData create(LitematicaSchematic schematic, Collection<String> regions) {
            return new ContainerMaterialsData(schematic, List.copyOf(regions), null);
        }

        private static ContainerMaterialsData create(SchematicPlacement placement) {
            return new ContainerMaterialsData(
                    placement.getSchematic(),
                    List.copyOf(placement.getEnabledRelativeSubRegionPlacements().keySet()),
                    placement
            );
        }

        void refresh(BlockInfoListType materialListType) {
            this.groups = createContainerGroups(this.schematic, this.regions, this.placement, materialListType);
        }

        List<ContainerGroup> visibleGroups() {
            return this.groups.stream()
                    .filter(group -> !this.ignoredGroupSignatures.contains(group.signature()))
                    .toList();
        }

        private List<QuickMaterialCollector.MaterialRequest> createReplacementMaterialRequests(int multiplier) {
            Object2IntOpenHashMap<ItemKey> counts = new Object2IntOpenHashMap<>();
            Map<ItemKey, ItemStack> displayStacks = new HashMap<>();
            int effectiveMultiplier = Math.max(1, multiplier);

            for (ContainerGroup group : this.visibleGroups()) {
                for (ItemStack stack : group.materialRequestStacks()) {
                    ItemStack replacement = QuickLitematicaContainerReplacements.applyToStack(stack);
                    if (!replacement.isEmpty()) {
                        addStackCount(counts, displayStacks, replacement, replacement.getCount() * effectiveMultiplier);
                    }
                }
            }

            List<QuickMaterialCollector.MaterialRequest> requests = new ArrayList<>();
            for (ItemCount item : toItemCounts(counts, displayStacks)) {
                requests.add(new QuickMaterialCollector.MaterialRequest(item.stack().copy(), item.count()));
            }
            return requests;
        }

        void ignoreGroup(ContainerGroup group) {
            this.ignoredGroupSignatures.add(group.signature());
        }

        void clearIgnoredGroups() {
            this.ignoredGroupSignatures.clear();
        }

        private String materialTitle() {
            return StringUtils.translate(
                    "quickcraft.litematica.title.container_material_list",
                    this.schematic.getMetadata().getName(),
                    this.regions.size(),
                    this.schematic.getAreas().size()
            );
        }

        String detailTitle() {
            return StringUtils.translate(
                    "quickcraft.litematica.title.container_material_details",
                    this.schematic.getMetadata().getName(),
                    this.regions.size(),
                    this.schematic.getAreas().size()
            );
        }

        int totalVisibleContainerCount() {
            int total = 0;

            for (ContainerGroup group : this.visibleGroups()) {
                total += group.containerCount();
            }

            return total;
        }

        private Object2IntOpenHashMap<ItemKey> createWorldMissingCounts(
                Object2IntOpenHashMap<ItemKey> totalCounts,
                @Nullable QuickLitematicaContainerVerifier.VerifierExtension verifier
        ) {
            if (this.placement == null
                    || verifier == null
                    || verifier.quickcraft$getExpectedContainerCount() <= 0
                    || verifier.quickcraft$getCheckedContainerCount() < verifier.quickcraft$getExpectedContainerCount()) {
                return null;
            }

            Object2IntOpenHashMap<ItemKey> missing = new Object2IntOpenHashMap<>();

            for (QuickLitematicaContainerVerifier.ContainerMismatch mismatch : verifier.quickcraft$getContainerMismatches()) {
                for (QuickLitematicaContainerVerifier.SlotMismatch slotMismatch : mismatch.slotMismatches()) {
                    int count = getMissingCount(slotMismatch);

                    if (count > 0) {
                        addMissingStackCounts(missing, slotMismatch.expectedStack(), count);
                    }
                }
            }

            for (ItemStack stack : verifier.quickcraft$getMissingContainerStacks()) {
                if (!stack.isEmpty()) {
                    addMissingStackCounts(missing, stack, stack.getCount());
                }
            }

            for (ItemKey type : missing.keySet()) {
                missing.put(type, Math.min(missing.getInt(type), totalCounts.getInt(type)));
            }

            return missing;
        }

        private static void addMissingStackCounts(
                Object2IntOpenHashMap<ItemKey> missing,
                ItemStack stack,
                int count
        ) {
            addMissingStackCounts(missing, stack, count, 0);
        }

        private static void addMissingStackCounts(
                Object2IntOpenHashMap<ItemKey> missing,
                ItemStack stack,
                int count,
                int depth
        ) {
            if (stack.isEmpty() || count <= 0) {
                return;
            }

            ItemStack displayStack = stack.copy();
            displayStack.setCount(1);
            missing.addTo(QuickLitematicaContainerMaterialsAccess.createItemKey(displayStack), count);

            if (depth >= 4 || !isShulkerBox(stack)) {
                return;
            }

            for (ItemStack nestedStack : readStoredShulkerStacks(stack)) {
                addMissingStackCounts(
                        missing,
                        nestedStack,
                        count * nestedStack.getCount(),
                        depth + 1
                );
            }
        }

        private static int getMissingCount(QuickLitematicaContainerVerifier.SlotMismatch mismatch) {
            ItemStack expected = mismatch.expectedStack();
            ItemStack found = mismatch.foundStack();

            return switch (mismatch.status()) {
                case MISSING, WRONG -> expected.getCount();
                case COUNT -> Math.max(0, expected.getCount() - found.getCount());
                case EXTRA, LOCK_STATE -> 0;
            };
        }
    }

    static final class ContainerMaterialList extends MaterialListBase
            implements ContainerMaterialRequestSource, ICompletionListener {
        final ContainerMaterialsData data;
        final Screen parent;
        private SchematicVerifier containerVerifier;
        private Object2IntOpenHashMap<ItemKey> worldMissingCounts;
        private boolean materialEntriesInitialized;

        private ContainerMaterialList(ContainerMaterialsData data, Screen parent, boolean initializeMaterialEntries) {
            this.data = data;
            this.parent = parent;
            if (initializeMaterialEntries) {
                this.reCreateMaterialList();
            } else {
                this.data.refresh(BlockInfoListType.ALL);
            }
        }

        @Override
        public String getName() {
            return this.data.schematic.getMetadata().getName();
        }

        @Override
        public String getTitle() {
            return this.data.materialTitle();
        }

        @Override
        public boolean supportsRenderLayers() {
            return this.data.placement != null;
        }

        @Override
        public void setMaterialListType(BlockInfoListType type) {
            super.setMaterialListType(this.supportsRenderLayers() ? type : BlockInfoListType.ALL);
        }

        @Override
        public void reCreateMaterialList() {
            this.data.refresh(this.getMaterialListType());
            this.initializeMaterialEntries();
        }

        private void initializeMaterialEntries() {
            this.materialEntriesInitialized = true;

            if (this.data.placement == null) {
                this.containerVerifier = null;
                this.worldMissingCounts = null;
                this.setMaterialListEntries(this.createMaterialEntries());
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

            if (client.world == null || schematicWorld == null) {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.error.generic.schematic_world_not_loaded");
                return;
            }

            if (this.containerVerifier != null) {
                return;
            }

            this.worldMissingCounts = null;
            this.containerVerifier = new SchematicVerifier();
            this.containerVerifier.toggleShouldRenderInfoHUD();
            ((QuickLitematicaContainerVerifier.VerifierExtension) this.containerVerifier)
                    .quickcraft$setContainerOnly(true);
            this.containerVerifier.startVerification(client.world, schematicWorld, this.data.placement, this);
            InfoUtils.showGuiOrInGameMessage(MessageType.INFO, "litematica.message.scheduled_task_added");
        }

        @Override
        public void onTaskCompleted() {
            if (this.containerVerifier == null) {
                return;
            }
            this.setMaterialListEntries(this.createMaterialEntries());
            this.containerVerifier = null;
        }

        @Override
        public void onTaskAborted() {
            this.containerVerifier = null;
        }

        void ensureMaterialEntriesInitialized() {
            if (!this.materialEntriesInitialized) {
                this.initializeMaterialEntries();
            }
        }

        void refreshContainerData() {
            this.data.refresh(this.getMaterialListType());
            this.invalidateMaterialEntries();
        }

        void ignoreContainerGroup(ContainerGroup group) {
            this.data.ignoreGroup(group);
            this.invalidateMaterialEntries();
        }

        void invalidateMaterialEntries() {
            this.materialEntriesInitialized = false;
            this.worldMissingCounts = null;
            this.setMaterialListEntries(List.of());
        }

        @Override
        public void clearIgnored() {
            this.data.clearIgnoredGroups();
            super.clearIgnored();
            this.setMaterialListEntries(this.createMaterialEntries());
        }

        private List<MaterialListEntry> createMaterialEntries() {
            Object2IntOpenHashMap<ItemKey> counts = new Object2IntOpenHashMap<>();
            Map<ItemKey, ItemStack> displayStacks = new HashMap<>();

            for (ContainerGroup group : this.data.visibleGroups()) {
                for (ItemCount item : group.contents()) {
                    ItemStack displayStack = item.stack().copy();
                    displayStack.setCount(1);
                    ItemKey key = QuickLitematicaContainerMaterialsAccess.createItemKey(displayStack);
                    counts.addTo(key, item.totalCount(group.containerCount()));
                    displayStacks.putIfAbsent(key, displayStack);
                }
            }

            QuickLitematicaContainerVerifier.VerifierExtension verifier =
                    this.containerVerifier instanceof QuickLitematicaContainerVerifier.VerifierExtension extension
                            ? extension
                            : null;
            Object2IntOpenHashMap<ItemKey> worldMissing = this.data.createWorldMissingCounts(counts, verifier);
            if (worldMissing != null) {
                this.worldMissingCounts = new Object2IntOpenHashMap<>(worldMissing);
            } else if (this.worldMissingCounts != null) {
                worldMissing = new Object2IntOpenHashMap<>(this.worldMissingCounts);
                for (ItemKey type : worldMissing.keySet()) {
                    worldMissing.put(type, Math.min(worldMissing.getInt(type), counts.getInt(type)));
                }
            }

            if (this.data.placement != null && worldMissing == null) {
                return List.of();
            }

            List<MaterialListEntry> entries = new ArrayList<>();

            for (ItemKey type : counts.keySet()) {
                ItemStack stack = displayStacks.get(type);

                if (stack != null && !stack.isEmpty()) {
                    int count = counts.getInt(type);
                    int missing = worldMissing != null ? worldMissing.getInt(type) : count;
                    entries.add(new MaterialListEntry(stack, count, missing, 0, 0));
                }
            }

            entries.sort(Comparator.comparing(entry -> itemSignature(entry.getStack())));
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                MaterialListUtils.updateAvailableCounts(entries, client.player);
            }
            return entries;
        }

        @Override
        public List<QuickMaterialCollector.MaterialRequest> quickcraft$getReplacementMaterialRequests() {
            return this.data.createReplacementMaterialRequests(this.getMultiplier());
        }
    }
}

