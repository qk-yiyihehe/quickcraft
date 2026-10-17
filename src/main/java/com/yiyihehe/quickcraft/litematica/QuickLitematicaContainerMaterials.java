package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickMaterialCollector;
import com.yiyihehe.quickcraft.malilib.QuickCraftGuiButtonAccess;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.util.FileType;
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
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
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
import net.minecraft.util.math.Vec3i;

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
import java.util.Optional;

/**
 * 给 Litematica 的加载原理图页面补一个“容器材料列表”。
 * 这里按容器内容分组，而不是把所有容器物品直接拍平成一张总材料表。
 */
public final class QuickLitematicaContainerMaterials {
    public static final String BUTTON_KEY = "quickcraft.litematica.button.container_material_list";
    public static final String BUTTON_HOVER_KEY = "quickcraft.litematica.button.hover.container_material_list";

    static final int BUTTON_GAP = 4;
    static final int CONTAINER_COLUMN_WIDTH = 144;
    static final int COUNT_COLUMN_WIDTH = 58;
    static final int ACTION_COLUMN_WIDTH = 66;
    static final int ITEM_CELL_WIDTH = 20;
    static final int ITEM_CELL_HEIGHT = 28;
    static final int HEADER_HEIGHT = 22;
    private static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[] {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private QuickLitematicaContainerMaterials() {
    }

    abstract static class ItemKey {
        @Override public abstract boolean equals(Object other);
        @Override public abstract int hashCode();
        @Override public abstract String toString();
    }

    public interface ContainerMaterialRequestSource {
        List<QuickMaterialCollector.MaterialRequest> quickcraft$getReplacementMaterialRequests();
        List<MaterialListEntry> quickcraft$getHudMaterialsAll(boolean refresh);
        List<MaterialListEntry> quickcraft$getHudMaterialsMissing();
    }

    public interface ContainerReplacementBindingSource {
        String quickcraft$getPersistentContextKey();
        void quickcraft$toggleSelectedMaterial(ItemStack source);
        boolean quickcraft$isMaterialSelected(ItemStack source);
        QuickLitematicaMaterialReplacementScreen.ReplacementBinding quickcraft$createSummaryReplacementBinding(ItemStack source);

        Optional<ItemStack> quickcraft$getSummaryReplacementTarget(ItemStack source);

        String quickcraft$getSummaryReplacementLabel(ItemStack source);

        boolean quickcraft$hasSummaryReplacement(ItemStack source);

        void quickcraft$clearSummaryReplacement(ItemStack source);

        void quickcraft$clearAllContainerRulesForContext();
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

        Path file = QuickLitematicaContainerMaterialsAccess.getEntryPath(entry);
        if (!Files.exists(file) || !Files.isReadable(file)) {
            gui.addMessage(MessageType.ERROR, "litematica.error.schematic_load.cant_read_file", file.getFileName());
            return;
        }

        gui.setNextMessageType(MessageType.ERROR);
        LitematicaSchematic schematic = readSchematic(gui, entry);

        if (schematic == null) {
            return;
        }

        ContainerMaterialsData data = ContainerMaterialsData.create(
                schematic,
                List.copyOf(schematic.getAreas().keySet()),
                QuickLitematicaMaterialLists.getContainerReplacementContextKey(schematic)
        );
        ContainerMaterialList materialList = new ContainerMaterialList(data, gui);
        QuickLitematicaMaterialNavigation.forContainers(materialList).show(QuickLitematicaMaterialNavigation.View.CONTAINERS);
    }

    public static void saveCopy(MaterialListBase materialList) {
        ContainerMaterialsData data;
        if (materialList instanceof ContainerMaterialList containers) {
            data = containers.data;
        } else if (materialList instanceof com.yiyihehe.quickcraft.mixin.LitematicaMaterialListPlacementAccessor accessor) {
            var placement = accessor.quickcraft$getPlacement();
            data = ContainerMaterialsData.create(placement, QuickLitematicaMaterialLists.getContainerReplacementContextKey(placement));
        } else if (materialList instanceof com.yiyihehe.quickcraft.mixin.LitematicaMaterialListSchematicAccessor accessor) {
            data = ContainerMaterialsData.create(accessor.quickcraft$getSchematic(), accessor.quickcraft$getRegions(),
                    QuickLitematicaMaterialLists.getContainerReplacementContextKey(accessor.quickcraft$getSchematic()));
        } else { return; }
        saveCopy(data);
    }

    private static void saveCopy(ContainerMaterialsData data) {
        var sourceFile = QuickLitematicaContainerMaterialsAccess.schematicPath(data.liveSchematic);
        if (sourceFile == null) {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "quickcraft.litematica.error.copy_no_source");
            return;
        }
        try {
            Path source = sourceFile.toAbsolutePath();
            var copy = createContainerReplacementCopy(data.schematic, data.schematic.getAreas().keySet(), data.replacementContextKey);
            QuickLitematicaProjectionMaterials.replaceBlocks(copy, data.replacementContextKey);
            var output = copy.writeToNBT();
            Path destination = writeNumberedCopy(source, output);
            InfoUtils.showGuiOrInGameMessage(MessageType.INFO, "quickcraft.litematica.message.copy_saved", destination.toString());
        } catch (Exception exception) {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "quickcraft.litematica.error.copy_failed", exception.getMessage());
        }
    }

    static LitematicaSchematic createContainerReplacementCopy(LitematicaSchematic schematic,
                                                              Collection<String> regions, String context) {
        return createContainerReplacementCopy(schematic, regions, context, false);
    }

    static LitematicaSchematic createContainerReplacementCopy(LitematicaSchematic schematic,
                                                              Collection<String> regions, String context, boolean globals) {
        var copy = QuickLitematicaContainerMaterialsAccess.copySchematic(schematic);
        if (!globals && context.isBlank()) { return copy; }
        RegistryWrapper.WrapperLookup lookup = getRegistryLookup();
        if (lookup == null) { throw new IllegalStateException("Registry lookup unavailable"); }
        for (String region : regions) {
            var entities = QuickLitematicaContainerMaterialsAccess.getBlockEntities(copy, region);
            var states = copy.getSubRegionContainer(region);
            Set<BlockPos> consumed = new HashSet<>();
            if (entities != null) {
                for (var entry : entities.entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (consumed.contains(pos)) { continue; }
                    var slots = readItems(entry.getValue(), lookup);
                    if (slots == null || slots.isEmpty()) { continue; }
                    int firstSize = slots.size();
                    BlockState state = getState(states, pos);
                    BlockPos paired = findPairedChest(pos, state, states, entities, consumed);
                    consumed.add(pos);
                    if (paired != null) {
                        var pairedSlots = readItems(entities.get(paired), lookup);
                        if (pairedSlots == null) { continue; }
                        slots.addAll(pairedSlots);
                        consumed.add(paired);
                    }
                    var descriptor = describeBlockContainer(state, entry.getValue(), paired != null);
                    var replaced = exportSlotStacks(descriptor.stack(), slots, context, lookup, 0, globals);
                    writeSlotStacks(entry.getValue(), replaced.subList(0, firstSize), lookup);
                    if (paired != null) { writeSlotStacks(entities.get(paired), replaced.subList(firstSize, replaced.size()), lookup); }
                }
            }
            if (entities != null) { QuickLitematicaContainerMaterialsAccess.setBlockEntities(copy, region, entities); }
            var entityList = copy.getEntityListForRegion(region);
            if (entityList != null) {
                for (int entityIndex = 0; entityIndex < entityList.size(); entityIndex++) {
                    var entity = entityList.get(entityIndex);
                    var entityNbt = QuickLitematicaContainerMaterialsAccess.getEntityNbt(entity);
                    var slots = readItems(entityNbt, lookup);
                    if (slots != null && !slots.isEmpty()) {
                        writeSlotStacks(entityNbt, exportSlotStacks(describeEntityContainer(QuickLitematicaContainerMaterialsAccess.readString(entityNbt, "id")).stack(),
                                slots, context, lookup, 0, globals), lookup);
                        QuickLitematicaContainerMaterialsAccess.setEntityNbt(entityList, entityIndex, entityNbt);
                    }
                }
            }
        }
        return copy;
    }

    static Path writeNumberedCopy(Path source, NbtCompound output) throws java.io.IOException {
        String fileName = source.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        for (int number = 1; ; number++) {
            Path destination = source.getParent().resolve(stem + "_副本_" + number + ".litematic");
            java.io.OutputStream stream;
            try {
                stream = Files.newOutputStream(destination, java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException ignored) { continue; }
            try (stream) {
                net.minecraft.nbt.NbtIo.writeCompressed(output, stream);
                return destination;
            } catch (java.io.IOException exception) {
                try { Files.deleteIfExists(destination); }
                catch (java.io.IOException cleanupFailure) { exception.addSuppressed(cleanupFailure); }
                throw exception;
            }
        }
    }

    private static List<ItemStack> exportSlotStacks(ItemStack container, List<ItemStack> slots, String context,
                                                    RegistryWrapper.WrapperLookup lookup, int depth, boolean globals) {
        String group = QuickLitematicaContainerReplacements.containerGroupSignature(container, slots);
        var replaced = globals ? QuickLitematicaGlobalReplacements.containerSlots(slots, context, group)
                : getReplacedSlotStacks(slots, context, group);
        if (depth < 4) {
            for (ItemStack stack : replaced) {
                if (isShulkerBox(stack)) {
                    var nested = readStoredShulkerStacks(stack);
                    if (!nested.isEmpty()) {
                        var contents = exportSlotStacks(stack, nested, context, lookup, depth + 1, globals);
                        for (int slot = 0; slot < nested.size(); slot++) {
                            if (hasStackChange(nested.get(slot), contents.get(slot))) {
                                stack.set(net.minecraft.component.DataComponentTypes.CONTAINER,
                                        net.minecraft.component.type.ContainerComponent.fromStacks(contents));
                                break;
                            }
                        }
                    }
                }
            }
        }
        return replaced;
    }

    private static void writeSlotStacks(NbtCompound container, List<ItemStack> slots, RegistryWrapper.WrapperLookup lookup) {
        NbtList items = new NbtList();
        for (int slot = 0; slot < slots.size(); slot++) {
            if (!slots.get(slot).isEmpty()) {
                NbtCompound item = (NbtCompound) ItemStack.OPTIONAL_CODEC.encodeStart(lookup.getOps(net.minecraft.nbt.NbtOps.INSTANCE), slots.get(slot)).getOrThrow();
                item.putByte("Slot", (byte) slot);
                items.add(item);
            }
        }
        container.put("Items", items);
    }

    static int getDefaultButtonWidth(GuiSchematicLoad gui, String translationKey) {
        return gui.getStringWidth(StringUtils.translate(translationKey)) + 10;
    }

    private static LitematicaSchematic readSchematic(GuiSchematicLoad gui, DirectoryEntry entry) {
        FileType fileType = FileType.fromFile(QuickLitematicaContainerMaterialsAccess.getEntryPath(entry));

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
                gui.addMessage(MessageType.ERROR, "litematica.error.schematic_load.unsupported_type", QuickLitematicaContainerMaterialsAccess.getEntryFileName(entry));
                yield null;
            }
        };
    }

    private static RegistryWrapper.WrapperLookup getRegistryLookup() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.world != null ? client.world.getRegistryManager() : null;
    }

    private static List<ContainerGroup> createContainerGroups(LitematicaSchematic schematic, Collection<String> regions) {
        RegistryWrapper.WrapperLookup registryLookup = getRegistryLookup();
        GroupAccumulator accumulator = new GroupAccumulator();

        if (registryLookup == null) {
            return List.of();
        }

        for (String regionName : regions) {
            addBlockEntityContainers(schematic, regionName, registryLookup, accumulator);
            addEntityContainers(schematic, regionName, registryLookup, accumulator);
        }

        return accumulator.toGroups();
    }

    private static void addBlockEntityContainers(
            LitematicaSchematic schematic,
            String regionName,
            RegistryWrapper.WrapperLookup registryLookup,
            GroupAccumulator accumulator
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
            List<ItemStack> stacks = readItemsForStatistics(nbt, registryLookup);

            if (stacks == null || stacks.isEmpty()) {
                continue;
            }

            BlockState state = getState(stateContainer, pos);
            BlockPos pairedChestPos = findPairedChest(pos, state, stateContainer, blockEntities, consumed);
            ContainerDescriptor descriptor = describeBlockContainer(state, nbt, pairedChestPos != null);

            consumed.add(pos);

            if (pairedChestPos != null) {
                NbtCompound pairedNbt = blockEntities.get(pairedChestPos);
                var pairedStacks = readItemsForStatistics(pairedNbt, registryLookup);
                if (pairedStacks == null) { continue; }
                stacks.addAll(pairedStacks);
                consumed.add(pairedChestPos);
            }

            addContainer(accumulator, descriptor, stacks);
        }
    }

    private static void addEntityContainers(
            LitematicaSchematic schematic,
            String regionName,
            RegistryWrapper.WrapperLookup registryLookup,
            GroupAccumulator accumulator
    ) {
        List<EntityInfo> entities = schematic.getEntityListForRegion(regionName);

        if (entities == null || entities.isEmpty()) {
            return;
        }

        for (EntityInfo info : entities) {
            NbtCompound nbt = QuickLitematicaContainerMaterialsAccess.getEntityNbt(info);
            List<ItemStack> stacks = readItemsForStatistics(nbt, registryLookup);

            if (stacks == null || stacks.isEmpty()) {
                continue;
            }

            ContainerDescriptor descriptor = describeEntityContainer(QuickLitematicaContainerMaterialsAccess.readString(nbt, "id"));
            addContainer(accumulator, descriptor, stacks);
        }
    }

    private static void addContainer(GroupAccumulator accumulator, ContainerDescriptor descriptor, List<ItemStack> stacks) {
        List<ItemStack> slotStacks = copySlotStacks(stacks);
        List<ItemCount> contents = countStacks(slotStacks);

        if (contents.isEmpty()) {
            return;
        }

        accumulator.add(descriptor.stack(), descriptor.displayName(), null, contents, slotStacks, 1, false);
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

            accumulator.add(shulkerStack, shulkerName, shulkerSource, shulkerContents, shulkerSlotStacks, item.count(), true);
            addStoredShulkerGroups(accumulator, nestedSourceName, nestedSourceKey, shulkerContents, depth + 1);
        }
    }

    static List<ItemStack> readItems(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        return readItems(nbt, registryLookup, false);
    }

    static List<ItemStack> readItemsForStatistics(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        return readItems(nbt, registryLookup, true);
    }

    // 统计允许保留已读到的槽位；替换、保存和验证必须拒绝不完整库存。
    private static List<ItemStack> readItems(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup, boolean allowPartial) {
        if (nbt == null || !nbt.contains("Items")) {
            return List.of();
        }

        List<ItemStack> stacks = new ArrayList<>();
        if (!(nbt.get("Items") instanceof NbtList items)) {
            QuickLitematicaSchematicData.unreadableInventory(nbt);
            return allowPartial ? stacks : null;
        }
        int size = inferInventorySize(items);

        for (int i = 0; i < size; i++) {
            stacks.add(ItemStack.EMPTY);
        }

        Set<Integer> seenSlots = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            NbtCompound itemNbt = QuickLitematicaContainerMaterialsAccess.readCompound(items, i);
            if (!(itemNbt.get("Slot") instanceof net.minecraft.nbt.NbtByte)) {
                QuickLitematicaSchematicData.unreadableInventory(itemNbt);
                if (allowPartial) { continue; }
                return null;
            }
            ItemStack stack = QuickLitematicaContainerMaterialsAccess.itemStackFromNbt(registryLookup, itemNbt);
            if (stack.isEmpty() && !QuickLitematicaSchematicData.isEmptyItem(itemNbt)) {
                if (allowPartial) { continue; }
                return null;
            }
            int slot = QuickLitematicaContainerMaterialsAccess.readByte(itemNbt, "Slot") & 255;
            if (!seenSlots.add(slot)) {
                QuickLitematicaSchematicData.unreadableInventory(itemNbt);
                if (allowPartial) { continue; }
                return null;
            }

            if (!stack.isEmpty() && slot >= 0 && slot < stacks.size()) {
                stacks.set(slot, stack);
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

    private static List<ItemStack> copySlotStacks(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            copies.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return copies;
    }

    private static List<ItemStack> readStoredShulkerStacks(ItemStack shulkerStack) {
        List<ItemStack> stacks = new ArrayList<>();
        DefaultedList<ItemStack> storedItems = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(shulkerStack);

        for (ItemStack storedStack : storedItems) {
            stacks.add(storedStack.isEmpty() ? ItemStack.EMPTY : storedStack.copy());
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

    private static List<ItemStack> getReplacedSlotStacks(
            List<ItemStack> slotStacks,
            String contextKey,
            String groupKey
    ) {
        List<ItemStack> replaced = new ArrayList<>(slotStacks.size());

        for (int slotIndex = 0; slotIndex < slotStacks.size(); slotIndex++) {
            ItemStack stack = slotStacks.get(slotIndex);
            replaced.add(QuickLitematicaContainerReplacements.applyToStack(
                    stack,
                    QuickLitematicaContainerReplacements.Scope.CONTAINER,
                    contextKey,
                    groupKey,
                    slotIndex
            ));
        }

        return replaced;
    }

    static int inferInventorySize(NbtList items) {
        int size = 0;

        for (int i = 0; i < items.size(); i++) {
            size = Math.max(size, (QuickLitematicaContainerMaterialsAccess.readByte(QuickLitematicaContainerMaterialsAccess.readCompound(items, i), "Slot") & 255) + 1);
        }

        return size;
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
            stack = stackFromBlockEntityId(QuickLitematicaContainerMaterialsAccess.readString(nbt, "id"));
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

    static boolean hasStackChange(ItemStack original, ItemStack result) {
        return original.getCount() != result.getCount() || !ItemStack.areItemsAndComponentsEqual(original, result);
    }

    static int getGroupHeight(QuickLitematicaContainerMaterialsScreens.ContainerRow row, int rowWidth) {
        int contentWidth = Math.max(ITEM_CELL_WIDTH, rowWidth - CONTAINER_COLUMN_WIDTH - COUNT_COLUMN_WIDTH - ACTION_COLUMN_WIDTH - 18);
        int columns = Math.max(1, Math.min(9, (contentWidth - 12) / (2 * ITEM_CELL_WIDTH)));
        int rows = Math.max(1, (row.endSlot() - row.startSlot() + columns - 1) / columns);
        int contentHeight = rows * ITEM_CELL_HEIGHT + 6;
        return Math.max(row.startSlot() == 0 && row.group().sourceLabel() != null ? 40 : 30, contentHeight);
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

    public record ButtonPlacement(int x, int y) {
    }

    private record ContainerDescriptor(ItemStack stack, String displayName, String signatureKey) {
    }

    record ItemCount(ItemStack stack, int count, String signature) {
        private int totalCount(int multiplier) {
            return this.count * multiplier;
        }
    }

    record ContainerGroup(
            ItemStack containerStack,
            String containerName,
            String sourceLabel,
            int containerCount,
            List<ItemStack> slotStacks,
            List<ItemCount> contents,
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
                List<ItemCount> contents,
                List<ItemStack> slotStacks,
                int count,
                boolean nestedShulker
        ) {
            String signature = QuickLitematicaContainerReplacements.containerGroupSignature(stack, slotStacks);
            GroupBuilder builder = this.groups.computeIfAbsent(
                    signature,
                    key -> new GroupBuilder(stack.copy(), displayName, sourceLabel, contents, slotStacks, nestedShulker, signature)
            );
            builder.containerCount += count;
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
        private final List<ItemStack> slotStacks;
        private final boolean nestedShulker;
        private final String signature;
        private int containerCount;

        private GroupBuilder(
                ItemStack stack,
                String displayName,
                String sourceLabel,
                List<ItemCount> contents,
                List<ItemStack> slotStacks,
                boolean nestedShulker,
                String signature
        ) {
            this.stack = stack;
            this.displayName = displayName;
            this.sourceLabel = sourceLabel;
            this.contents = List.copyOf(contents);
            this.slotStacks = copySlotStacks(slotStacks);
            this.nestedShulker = nestedShulker;
            this.signature = signature;
        }

        private ContainerGroup toGroup() {
            ItemStack displayStack = this.stack.copy();
            displayStack.setCount(1);
            return new ContainerGroup(
                    displayStack,
                    this.displayName,
                    this.sourceLabel,
                    this.containerCount,
                    copySlotStacks(this.slotStacks),
                    this.contents,
                    this.nestedShulker,
                    this.signature
            );
        }
    }

    static final class ContainerMaterialsData {
        final LitematicaSchematic schematic;
        final LitematicaSchematic liveSchematic;
        final List<String> regions;
        final SchematicPlacement placement;
        final String replacementContextKey;
        final Set<String> ignoredGroupSignatures = new HashSet<>();
        List<ContainerGroup> groups;

        ContainerMaterialsData(
                LitematicaSchematic schematic,
                List<String> regions,
                SchematicPlacement placement,
                String replacementContextKey
        ) {
            QuickLitematicaContainerReplacements.migrateContainerContext(placement == null ? ""
                    : QuickLitematicaMaterialLists.getReplacementContextKey(placement), replacementContextKey);
            this.liveSchematic = schematic;
            this.schematic = QuickLitematicaContainerMemory.original(schematic);
            this.regions = regions;
            this.placement = placement;
            this.replacementContextKey = QuickLitematicaContainerMemory.effectiveContext(schematic,
                    replacementContextKey == null ? "" : replacementContextKey);
            this.ignoredGroupSignatures.addAll(QuickLitematicaMaterialIgnores.get(this.persistentContextKey() + "#groups"));
            this.refresh();
        }

        String persistentContextKey() {
            return QuickLitematicaMaterialLists.getReplacementContextKey(this.schematic, this.regions) + "#container";
        }

        static ContainerMaterialsData create(LitematicaSchematic schematic, Collection<String> regions, String replacementContextKey) {
            return new ContainerMaterialsData(schematic, List.copyOf(regions), null, replacementContextKey);
        }

        static ContainerMaterialsData create(SchematicPlacement placement, String replacementContextKey) {
            return new ContainerMaterialsData(
                    placement.getSchematic(),
                    List.copyOf(placement.getEnabledRelativeSubRegionPlacements().keySet()),
                    placement,
                    replacementContextKey
            );
        }

        void refresh() {
            QuickLitematicaContainerMemory.update(this.liveSchematic, this.regions, this.replacementContextKey);
            this.groups = createContainerGroups(this.schematic, this.regions);
        }

        List<ContainerGroup> visibleGroups() {
            return this.groups.stream()
                    .filter(group -> !this.ignoredGroupSignatures.contains(group.signature()))
                    .toList();
        }

        QuickLitematicaMaterialReplacementScreen.ReplacementBinding createSummaryReplacementBinding(ItemStack source) {
            ItemStack sourceCopy = source == null ? ItemStack.EMPTY : source.copy();
            sourceCopy.setCount(1);
            return this.createSummaryReplacementBinding(List.of(sourceCopy));
        }

        QuickLitematicaMaterialReplacementScreen.ReplacementBinding createSummaryReplacementBinding(List<ItemStack> sources) {
            ItemStack sourceCopy = sources.getFirst();
            List<SummarySlot> slots = sources.stream().flatMap(stack -> this.matchingSummarySlots(stack).stream()).toList();

            return new QuickLitematicaMaterialReplacementScreen.ReplacementBinding() {
                @Override
                public boolean supportsCount() { return true; }

                @Override
                public boolean preserveCountByDefault() { return true; }

                @Override
                public String scopeDescription() {
                    return StringUtils.translate("quickcraft.litematica.label.batch_material_scope", sources.size());
                }

                @Override
                public String validateTarget(ItemStack ignoredSource, ItemStack target, boolean preserveCount) {
                    boolean editable = false;
                    for (SummarySlot slot : slots) {
                        if (!hasSlotOverride(slot)) {
                            editable = true;
                            int count = preserveCount ? slot.source().getCount() : target.getCount();
                            if (!target.isEmpty() && count > target.getMaxCount()) {
                                return StringUtils.translate("quickcraft.litematica.label.material_replace_count_error", target.getMaxCount());
                            }
                        }
                    }
                    return editable ? "" : StringUtils.translate("quickcraft.litematica.label.material_replace_all_overridden");
                }

                @Override
                public Optional<ItemStack> getCurrentTarget(ItemStack ignoredSource) {
                    return getFirstMatchingSummaryTarget(sourceCopy);
                }

                @Override
                public void apply(ItemStack ignoredSource, ItemStack target) {
                    this.apply(ignoredSource, target, true);
                }

                @Override
                public void apply(ItemStack ignoredSource, ItemStack target, boolean preserveCount) {
                    List<QuickLitematicaContainerReplacements.ContainerReplacement> rules = new ArrayList<>();
                    for (SummarySlot slot : slots) {
                        if (!hasSlotOverride(slot)) {
                            rules.add(new QuickLitematicaContainerReplacements.ContainerReplacement(
                                    slot.source(), target, replacementContextKey, slot.group().signature(), -1, preserveCount));
                        }
                    }
                    QuickLitematicaContainerReplacements.setContainerReplacementRules(rules);
                }

                @Override
                public void clear(ItemStack ignoredSource) {
                    sources.forEach(ContainerMaterialsData.this::clearSummaryReplacement);
                }
            };
        }

        boolean hasSummaryReplacement(ItemStack source) {
            for (SummarySlot slot : this.matchingSummarySlots(source == null ? ItemStack.EMPTY : source)) {
                if (QuickLitematicaContainerReplacements.getReplacementTarget(slot.source(),
                        QuickLitematicaContainerReplacements.Scope.CONTAINER, this.replacementContextKey,
                        slot.group().signature(), -1).isPresent()) { return true; }
            }
            return false;
        }

        void clearAllContainerRulesForContext() {
            QuickLitematicaContainerReplacements.removeReplacementRulesForContext(
                    QuickLitematicaContainerReplacements.Scope.CONTAINER,
                    this.replacementContextKey
            );
        }

        QuickLitematicaMaterialReplacementScreen.ReplacementBinding createSlotReplacementBinding(
                ContainerGroup group,
                int slotIndex
        ) {
            ItemStack sourceStack = slotIndex >= 0 && slotIndex < group.slotStacks().size()
                    ? group.slotStacks().get(slotIndex)
                    : ItemStack.EMPTY;
            ItemStack sourceCopy = sourceStack.isEmpty() ? ItemStack.EMPTY : sourceStack.copy();

            return new QuickLitematicaMaterialReplacementScreen.ReplacementBinding() {
                @Override
                public boolean supportsCount() { return true; }

                @Override
                public boolean canKeepOriginal() { return true; }

                @Override
                public String scopeDescription() {
                    return StringUtils.translate("quickcraft.litematica.label.material_replace_group_scope", group.containerCount());
                }

                @Override
                public void keepOriginal(ItemStack ignoredSource) {
                    Optional<ItemStack> bulk = QuickLitematicaContainerReplacements.getReplacementTarget(sourceCopy,
                            QuickLitematicaContainerReplacements.Scope.CONTAINER, replacementContextKey, group.signature(), -1);
                    if (bulk.isPresent() && hasStackChange(sourceCopy, bulk.get())) {
                        this.apply(ignoredSource, sourceCopy.copy());
                    } else {
                        this.clear(ignoredSource);
                    }
                }

                @Override
                public Optional<ItemStack> getCurrentTarget(ItemStack ignoredSource) {
                    return QuickLitematicaContainerReplacements.getReplacementTarget(
                            sourceCopy,
                            QuickLitematicaContainerReplacements.Scope.CONTAINER,
                            ContainerMaterialsData.this.replacementContextKey,
                            group.signature(),
                            slotIndex
                    );
                }

                @Override
                public void apply(ItemStack ignoredSource, ItemStack target) {
                    if (target == null) {
                        return;
                    }

                    QuickLitematicaContainerReplacements.setContainerReplacementRules(List.of(
                            new QuickLitematicaContainerReplacements.ContainerReplacement(sourceCopy, target,
                                    replacementContextKey, group.signature(), slotIndex, false)));
                }

                @Override
                public void clear(ItemStack ignoredSource) {
                    QuickLitematicaContainerReplacements.removeReplacementRule(
                            sourceCopy,
                            QuickLitematicaContainerReplacements.Scope.CONTAINER,
                            ContainerMaterialsData.this.replacementContextKey,
                            group.signature(),
                            slotIndex
                    );
                }
            };
        }

        QuickLitematicaMaterialReplacementScreen.ReplacementBinding createBatchSlotBinding(List<SummarySlot> slots) {
            return new QuickLitematicaMaterialReplacementScreen.ReplacementBinding() {
                @Override
                public boolean supportsCount() { return true; }

                @Override
                public boolean preserveCountByDefault() { return true; }

                @Override
                public boolean canKeepOriginal() { return true; }

                @Override
                public String scopeDescription() {
                    int containers = slots.stream().map(SummarySlot::group).distinct().mapToInt(ContainerGroup::containerCount).sum();
                    return StringUtils.translate("quickcraft.litematica.label.batch_slot_scope", slots.size(), containers);
                }

                @Override
                public Optional<ItemStack> getCurrentTarget(ItemStack source) { return Optional.empty(); }

                @Override
                public String validateTarget(ItemStack source, ItemStack target, boolean preserveCount) {
                    for (SummarySlot slot : slots) {
                        if (!target.isEmpty() && preserveCount && slot.source().getCount() > target.getMaxCount()) {
                            return StringUtils.translate("quickcraft.litematica.label.material_replace_count_error", target.getMaxCount());
                        }
                    }
                    return "";
                }

                @Override
                public void apply(ItemStack source, ItemStack target) { this.apply(source, target, true); }

                @Override
                public void apply(ItemStack source, ItemStack target, boolean preserveCount) {
                    QuickLitematicaContainerReplacements.setContainerReplacementRules(slots.stream().map(slot ->
                            new QuickLitematicaContainerReplacements.ContainerReplacement(slot.source(), target,
                                    replacementContextKey, slot.group().signature(), slot.slotIndex(), preserveCount && !slot.source().isEmpty())).toList());
                }

                @Override
                public void clear(ItemStack source) {
                    slots.forEach(slot -> createSlotReplacementBinding(slot.group(), slot.slotIndex()).clear(slot.source()));
                }

                @Override
                public void keepOriginal(ItemStack source) {
                    slots.forEach(slot -> createSlotReplacementBinding(slot.group(), slot.slotIndex()).keepOriginal(slot.source()));
                }
            };
        }

        Optional<ItemStack> getFirstMatchingSummaryTarget(ItemStack source) {
            if (source == null || source.isEmpty()) {
                return Optional.empty();
            }

            for (ContainerGroup group : this.visibleGroups()) {
                for (int slotIndex = 0; slotIndex < group.slotStacks().size(); slotIndex++) {
                    ItemStack slotStack = group.slotStacks().get(slotIndex);
                    if (!matchesSummarySource(group, slotIndex, slotStack, source)) {
                        continue;
                    }

                    Optional<ItemStack> target = QuickLitematicaContainerReplacements.getReplacementTarget(
                            slotStack,
                            QuickLitematicaContainerReplacements.Scope.CONTAINER,
                            this.replacementContextKey,
                            group.signature(),
                            slotIndex
                    );
                    if (target.isPresent() && hasStackChange(slotStack, target.get())) {
                        return target;
                    }
                }
            }

            return Optional.empty();
        }

        List<SummarySlot> matchingSummarySlots(ItemStack source) {
            List<SummarySlot> result = new ArrayList<>();
            for (ContainerGroup group : this.visibleGroups()) {
                for (int slotIndex = 0; slotIndex < group.slotStacks().size(); slotIndex++) {
                    ItemStack slotStack = group.slotStacks().get(slotIndex);
                    if (matchesSummarySource(group, slotIndex, slotStack, source)) {
                        result.add(new SummarySlot(group, slotIndex, slotStack.copy()));
                    }
                }
            }
            return result;
        }

        boolean matchesSummarySource(ContainerGroup group, int slotIndex, ItemStack original, ItemStack source) {
            if (ItemStack.areItemsAndComponentsEqual(original, source)) { return true; }
            return original.isEmpty() && ItemStack.areItemsAndComponentsEqual(source,
                    QuickLitematicaContainerReplacements.applyToStack(original,
                            QuickLitematicaContainerReplacements.Scope.CONTAINER, this.replacementContextKey,
                            group.signature(), slotIndex));
        }

        boolean hasSlotOverride(SummarySlot slot) {
            return QuickLitematicaContainerReplacements.hasSlotOverride(slot.source(), this.replacementContextKey,
                    slot.group().signature(), slot.slotIndex());
        }

        record SummarySlot(ContainerGroup group, int slotIndex, ItemStack source) {
        }

        void clearSummaryReplacement(ItemStack source) {
            if (source == null || source.isEmpty()) {
                return;
            }

            for (SummarySlot slot : this.matchingSummarySlots(source)) {
                QuickLitematicaContainerReplacements.removeReplacementRule(
                        slot.source(),
                        QuickLitematicaContainerReplacements.Scope.CONTAINER,
                        this.replacementContextKey,
                        slot.group().signature(),
                        -1
                );
            }
        }

        ReplacementMaterialCounts collectReplacementMaterialCounts(int multiplier) {
            Object2IntOpenHashMap<ItemKey> counts = new Object2IntOpenHashMap<>();
            Map<ItemKey, ItemStack> displayStacks = new HashMap<>();
            int effectiveMultiplier = Math.max(1, multiplier);

            for (ContainerGroup group : this.visibleGroups()) {
                List<ItemStack> replacedSlotStacks = getReplacedSlotStacks(
                        group.slotStacks(),
                        this.replacementContextKey,
                        group.signature()
                );

                for (ItemStack stack : replacedSlotStacks) {
                    if (!stack.isEmpty()) {
                        addStackCount(
                                counts,
                                displayStacks,
                                stack,
                                stack.getCount() * group.containerCount() * effectiveMultiplier
                        );
                    }
                }
            }

            return new ReplacementMaterialCounts(counts, displayStacks);
        }

        void ignoreGroup(ContainerGroup group) {
            this.ignoredGroupSignatures.add(group.signature());
            QuickLitematicaMaterialIgnores.add(this.persistentContextKey() + "#groups", group.signature());
        }

        void clearIgnoredGroups() {
            this.ignoredGroupSignatures.clear();
            QuickLitematicaMaterialIgnores.clear(this.persistentContextKey() + "#groups");
        }

        String materialTitle() {
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

        Object2IntOpenHashMap<ItemKey> createWorldMissingCounts(
                Object2IntOpenHashMap<ItemKey> totalCounts
        ) {
            if (this.placement == null
                    || !this.placement.hasVerifier()
                    || !(this.placement.getSchematicVerifier() instanceof QuickLitematicaContainerVerifier.VerifierExtension verifier)
                    || verifier.quickcraft$getExpectedContainerCount() <= 0
                    || verifier.quickcraft$getCheckedContainerCount() < verifier.quickcraft$getExpectedContainerCount()) {
                return null;
            }

            Object2IntOpenHashMap<ItemKey> missing = new Object2IntOpenHashMap<>();

            if (QuickLitematicaGlobalReplacements.hasRuntimeRules()) {
                for (ItemStack stack : verifier.quickcraft$getMaterialMissingStacks()) {
                    addMissingStackCounts(missing, stack, stack.getCount());
                }
                for (ItemKey type : missing.keySet()) { missing.put(type, Math.min(missing.getInt(type), totalCounts.getInt(type))); }
                return missing;
            }

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

        static void addMissingStackCounts(
                Object2IntOpenHashMap<ItemKey> missing,
                ItemStack stack,
                int count
        ) {
            addMissingStackCounts(missing, stack, count, 0);
        }

        static void addMissingStackCounts(
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

        static int getMissingCount(QuickLitematicaContainerVerifier.SlotMismatch mismatch) {
            ItemStack expected = mismatch.expectedStack();
            ItemStack found = mismatch.foundStack();

            return switch (mismatch.status()) {
                case MISSING, WRONG -> expected.getCount();
                case COUNT -> Math.max(0, expected.getCount() - found.getCount());
                case EXTRA, LOCK_STATE -> 0;
            };
        }

        record ReplacementMaterialCounts(
                Object2IntOpenHashMap<ItemKey> counts,
                Map<ItemKey, ItemStack> displayStacks
        ) {
        }
    }

    private static final class ContainerSummaryEntry extends MaterialListEntry {
        private final List<ItemStack> targets;

        private ContainerSummaryEntry(ItemStack original, int total, int missing, List<ItemStack> targets) {
            super(original, total, missing, 0, 0);
            this.targets = targets;
        }

        @Override
        public int getCountAvailable() {
            var player = MinecraftClient.getInstance().player;
            if (player == null) { return 0; }
            int count = 0;
            // 左侧显示原材料；可用数量必须比较实际目标，不能让原版按左侧图标统计。
            for (int slot = 0; slot < player.getInventory().size(); slot++) {
                ItemStack stack = player.getInventory().getStack(slot);
                if (this.targets.stream().anyMatch(target -> ItemStack.areItemsAndComponentsEqual(target, stack))) {
                    count += stack.getCount();
                }
            }
            return count;
        }
    }

    static final class ContainerMaterialList extends MaterialListBase
            implements ContainerMaterialRequestSource, ContainerReplacementBindingSource, ICompletionListener {
        final ContainerMaterialsData data;
        final Screen parent;
        QuickLitematicaMaterialNavigation navigation;
        final Map<ItemKey, ItemStack> selectedMaterials = new LinkedHashMap<>();
        List<MaterialListEntry> hudEntries;
        List<MaterialListEntry> hudMissingEntries;
        int hudMultiplier;
        long hudRevision = -1;
        int hudIgnoredHash;

        ContainerMaterialList(ContainerMaterialsData data, Screen parent) {
            this.data = data;
            this.parent = parent;
            this.reCreateMaterialList();
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
        public void reCreateMaterialList() {
            this.hudEntries = null;
            this.data.refresh();

            this.setMaterialListEntries(this.createMaterialEntries());
            if (this.data.placement == null) { return; }

            MinecraftClient client = MinecraftClient.getInstance();
            WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

            if (client.world == null || schematicWorld == null) {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.error.generic.schematic_world_not_loaded");
                return;
            }

            SchematicVerifier verifier = this.data.placement.getSchematicVerifier();
            verifier.setCompletionListener(this);

            if (verifier.isPaused()) {
                verifier.resume();
            } else if (!verifier.isActive()) {
                verifier.startVerification(client.world, schematicWorld, this.data.placement, this);
            }

            InfoUtils.showGuiOrInGameMessage(MessageType.INFO, "litematica.message.scheduled_task_added");
        }

        @Override
        public void onTaskCompleted() {
            this.hudEntries = null;
            this.data.refresh();
            this.setMaterialListEntries(this.createMaterialEntries());
        }

        @Override
        public void clearIgnored() {
            this.data.clearIgnoredGroups();
            super.clearIgnored();
            this.reCreateMaterialList();
        }

        List<MaterialListEntry> createMaterialEntries() {
            var finalCounts = this.data.collectReplacementMaterialCounts(1).counts();
            var worldMissing = this.data.createWorldMissingCounts(finalCounts);
            var remainingMissing = worldMissing == null ? new Object2IntOpenHashMap<>(finalCounts)
                    : new Object2IntOpenHashMap<>(worldMissing);
            Map<ItemKey, ItemStack> sources = new LinkedHashMap<>();
            Map<ItemKey, Map<ItemKey, Integer>> contributions = new LinkedHashMap<>();
            Map<ItemKey, ItemStack> targets = new HashMap<>();
            for (ContainerGroup group : this.data.visibleGroups()) {
                for (int slot = 0; slot < group.slotStacks().size(); slot++) {
                    ItemStack original = group.slotStacks().get(slot);
                    ItemStack target = QuickLitematicaContainerReplacements.applyToStack(original,
                            QuickLitematicaContainerReplacements.Scope.CONTAINER, this.data.replacementContextKey,
                            group.signature(), slot);
                    ItemStack display = original.isEmpty() ? target : original;
                    if (display.isEmpty()) { continue; }
                    ItemStack sourceDisplay = display.copyWithCount(1);
                    ItemKey sourceType = QuickLitematicaContainerMaterialsAccess.createItemKey(sourceDisplay);
                    sources.putIfAbsent(sourceType, sourceDisplay);
                    var row = contributions.computeIfAbsent(sourceType, ignored -> new LinkedHashMap<>());
                    if (!target.isEmpty()) {
                        ItemStack targetDisplay = target.copyWithCount(1);
                        ItemKey targetType = QuickLitematicaContainerMaterialsAccess.createItemKey(targetDisplay);
                        targets.putIfAbsent(targetType, targetDisplay);
                        row.merge(targetType, target.getCount() * group.containerCount(), Integer::sum);
                    }
                }
            }
            List<MaterialListEntry> entries = new ArrayList<>();
            for (var source : sources.entrySet()) {
                int total = 0;
                int missing = 0;
                for (var contribution : contributions.get(source.getKey()).entrySet()) {
                    total += contribution.getValue();
                    int allocated = Math.min(contribution.getValue(), remainingMissing.getInt(contribution.getKey()));
                    missing += allocated;
                    remainingMissing.addTo(contribution.getKey(), -allocated);
                }
                List<ItemStack> rowTargets = contributions.get(source.getKey()).keySet().stream().map(targets::get).toList();
                entries.add(new ContainerSummaryEntry(source.getValue(), total, missing, rowTargets));
            }
            return entries;
        }

        @Override
        public List<QuickMaterialCollector.MaterialRequest> quickcraft$getReplacementMaterialRequests() {
            List<QuickMaterialCollector.MaterialRequest> requests = new ArrayList<>();
            for (MaterialListEntry entry : this.quickcraft$getHudMaterialsAll(false)) {
                requests.add(new QuickMaterialCollector.MaterialRequest(entry.getStack().copy(), entry.getCountTotal() * this.getMultiplier()));
            }
            return requests;
        }

        @Override
        public List<MaterialListEntry> quickcraft$getHudMaterialsAll(boolean refresh) {
            long revision = QuickLitematicaContainerMemory.revision();
            int ignoredHash = this.ignored.hashCode() + this.data.ignoredGroupSignatures.hashCode();
            if (refresh || this.hudEntries == null || this.hudRevision != revision || this.hudIgnoredHash != ignoredHash) {
                Object2IntOpenHashMap<ItemKey> counts = new Object2IntOpenHashMap<>();
                Map<ItemKey, ItemStack> stacks = new HashMap<>();
                Set<ItemKey> ignoredSources = new HashSet<>();
                for (var entry : this.ignored) { ignoredSources.add(QuickLitematicaContainerMaterialsAccess.createItemKey(entry.getStack())); }
                String context = QuickLitematicaContainerMemory.appliedContext(this.data.liveSchematic, this.data.replacementContextKey);
                for (ContainerGroup group : this.data.visibleGroups()) {
                    var targets = exportSlotStacks(group.containerStack(), group.slotStacks(), context, getRegistryLookup(), 0, false);
                    for (int slot = 0; slot < targets.size(); slot++) {
                        ItemStack original = group.slotStacks().get(slot);
                        ItemStack target = targets.get(slot);
                        ItemStack display = original.isEmpty() ? target : original;
                        if (!display.isEmpty() && ignoredSources.contains(QuickLitematicaContainerMaterialsAccess.createItemKey(display.copyWithCount(1)))) { continue; }
                        addStackCount(counts, stacks, target, target.getCount() * group.containerCount());
                    }
                }
                var missing = this.data.createWorldMissingCounts(counts);
                List<MaterialListEntry> entries = new ArrayList<>();
                for (ItemKey type : counts.keySet()) {
                    ItemStack stack = stacks.get(type);
                    int total = counts.getInt(type);
                    entries.add(new ContainerSummaryEntry(stack, total, missing == null ? total : missing.getInt(type), List.of(stack)));
                }
                this.hudEntries = entries;
                this.hudMissingEntries = null;
                this.hudRevision = revision;
                this.hudIgnoredHash = ignoredHash;
            }
            return this.hudEntries;
        }

        @Override
        public List<MaterialListEntry> quickcraft$getHudMaterialsMissing() {
            List<MaterialListEntry> entries = this.quickcraft$getHudMaterialsAll(false);
            int multiplier = this.getMultiplier();
            if (this.hudMissingEntries == null || this.hudMultiplier != multiplier) {
                List<MaterialListEntry> missing = new ArrayList<>();
                for (MaterialListEntry entry : entries) {
                    int needed = multiplier == 1 ? entry.getCountMissing() : entry.getCountTotal() * multiplier;
                    int available = entry.getCountAvailable();
                    if (needed > available) {
                        // HUD 按原版刷新周期保存数量，避免逐帧筛选、排序和显示时反复扫描背包。
                        missing.add(new MaterialListEntry(entry.getStack(), entry.getCountTotal(),
                                entry.getCountMissing(), entry.getCountMismatched(), available));
                    }
                }
                missing.sort(new fi.dy.masa.litematica.materials.MaterialListSorter(this));
                this.hudMissingEntries = missing;
                this.hudMultiplier = multiplier;
            }
            return this.hudMissingEntries;
        }

        @Override
        public QuickLitematicaMaterialReplacementScreen.ReplacementBinding quickcraft$createSummaryReplacementBinding(ItemStack source) {
            return !this.quickcraft$isMaterialSelected(source) ? this.data.createSummaryReplacementBinding(source)
                    : this.data.createSummaryReplacementBinding(List.copyOf(this.selectedMaterials.values()));
        }

        @Override
        public String quickcraft$getPersistentContextKey() { return this.data.persistentContextKey(); }

        @Override
        public void quickcraft$toggleSelectedMaterial(ItemStack source) {
            ItemKey key = QuickLitematicaContainerMaterialsAccess.createItemKey(source);
            if (this.selectedMaterials.remove(key) == null) { this.selectedMaterials.put(key, source.copy()); }
        }

        @Override
        public boolean quickcraft$isMaterialSelected(ItemStack source) {
            return this.selectedMaterials.containsKey(QuickLitematicaContainerMaterialsAccess.createItemKey(source));
        }

        @Override
        public Optional<ItemStack> quickcraft$getSummaryReplacementTarget(ItemStack source) {
            return this.data.getFirstMatchingSummaryTarget(source);
        }

        @Override
        public String quickcraft$getSummaryReplacementLabel(ItemStack source) {
            Map<ItemKey, ItemStack> targets = new LinkedHashMap<>();
            for (var slot : this.data.matchingSummarySlots(source)) {
                ItemStack target = QuickLitematicaContainerReplacements.applyToStack(slot.source(),
                        QuickLitematicaContainerReplacements.Scope.CONTAINER, this.data.replacementContextKey,
                        slot.group().signature(), slot.slotIndex());
                targets.putIfAbsent(QuickLitematicaContainerMaterialsAccess.createItemKey(target), target);
            }
            if (targets.size() > 1) {
                return StringUtils.translate("quickcraft.litematica.label.material_replace_multiple_targets", targets.size());
            }
            ItemStack target = targets.values().stream().findFirst().orElse(ItemStack.EMPTY);
            return target.isEmpty() ? StringUtils.translate("quickcraft.litematica.label.material_replace_empty_slot")
                    : target.getName().getString();
        }

        @Override
        public boolean quickcraft$hasSummaryReplacement(ItemStack source) {
            return this.data.hasSummaryReplacement(source);
        }

        @Override
        public void quickcraft$clearSummaryReplacement(ItemStack source) {
            this.data.clearSummaryReplacement(source);
        }

        @Override
        public void quickcraft$clearAllContainerRulesForContext() {
            this.data.clearAllContainerRulesForContext();
        }
    }

}
