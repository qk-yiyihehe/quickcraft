package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickMaterialCollector;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.GuiBaseAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.GuiMainMenu.ButtonListenerChangeMenu;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.ItemType;
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
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
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

/**
 * 给 Litematica 的加载原理图页面补一个“容器材料列表”。
 * 这里按容器内容分组，而不是把所有容器物品直接拍平成一张总材料表。
 */
public final class QuickLitematicaContainerMaterials {
    public static final String BUTTON_KEY = "quickcraft.litematica.button.container_material_list";
    public static final String BUTTON_HOVER_KEY = "quickcraft.litematica.button.hover.container_material_list";

    private static final int BUTTON_GAP = 4;
    private static final int CONTAINER_COLUMN_WIDTH = 188;
    private static final int COUNT_COLUMN_WIDTH = 58;
    private static final int ACTION_COLUMN_WIDTH = 66;
    private static final int ITEM_CELL_WIDTH = 34;
    private static final int ITEM_CELL_HEIGHT = 20;
    private static final int HEADER_HEIGHT = 22;
    private static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[] {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private QuickLitematicaContainerMaterials() {
    }

    public interface ContainerMaterialRequestSource {
        List<QuickMaterialCollector.MaterialRequest> quickcraft$getReplacementMaterialRequests();
    }

    public static boolean shouldShowButton() {
        return QuickCraftConfigs.isLitematicaContainerMaterialListButtonVisible();
    }

    public static ButtonPlacement getButtonPlacement(GuiSchematicLoad gui, int buttonWidth) {
        int y = gui.getScreenHeight() - 26;
        List<ButtonBase> buttons = ((GuiBaseAccessor) (Object) gui).quickcraft$getButtons();
        ButtonBase mainMenuButton = buttons.stream()
                .filter(button -> button.getY() == y)
                // Litematica 0.26.12 把主菜单按钮固定在距右边缘 10 px 的位置。
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

        Path file = entry.getFullPath();
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
        ContainerMaterialList materialList = new ContainerMaterialList(data, gui);
        DataManager.setMaterialList(materialList);
        openMaterialListScreen(materialList);
    }

    private static void openMaterialListScreen(ContainerMaterialList materialList) {
        ContainerMaterialListScreen screen = new ContainerMaterialListScreen(materialList);
        screen.setParent(materialList.parent);
        GuiBase.openGui(screen);
    }

    private static void openDetailScreen(ContainerMaterialList materialList) {
        ContainerMaterialsScreen screen = new ContainerMaterialsScreen(materialList);
        screen.setParent(materialList.parent);
        GuiBase.openGui(screen);
    }

    private static LitematicaSchematic readSchematic(GuiSchematicLoad gui, DirectoryEntry entry) {
        FileType fileType = FileType.fromFile(entry.getFullPath());

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
                gui.addMessage(MessageType.ERROR, "litematica.error.schematic_load.unsupported_type", entry.getFullPath().getFileName());
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
        Map<BlockPos, NbtCompound> blockEntities = schematic.getBlockEntityMapForRegion(regionName);

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
            List<ItemStack> stacks = readItems(nbt, registryLookup);

            if (stacks.isEmpty()) {
                continue;
            }

            BlockState state = getState(stateContainer, pos);
            BlockPos pairedChestPos = findPairedChest(pos, state, stateContainer, blockEntities, consumed);
            ContainerDescriptor descriptor = describeBlockContainer(state, nbt, pairedChestPos != null);

            consumed.add(pos);

            if (pairedChestPos != null) {
                NbtCompound pairedNbt = blockEntities.get(pairedChestPos);
                stacks.addAll(readItems(pairedNbt, registryLookup));
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
            NbtCompound nbt = info.nbt;
            List<ItemStack> stacks = readItems(nbt, registryLookup);

            if (stacks.isEmpty()) {
                continue;
            }

            ContainerDescriptor descriptor = describeEntityContainer(nbt.getString("id", ""));
            addContainer(accumulator, descriptor, stacks);
        }
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
        if (nbt == null || !nbt.contains("Items")) {
            return List.of();
        }

        List<ItemStack> stacks = new ArrayList<>();
        NbtList items = nbt.getListOrEmpty("Items");

        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = itemStackFromNbt(registryLookup, items.getCompoundOrEmpty(i));

            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }

        return stacks;
    }

    private static ItemStack itemStackFromNbt(RegistryWrapper.WrapperLookup registryLookup, NbtCompound nbt) {
        return ItemStack.OPTIONAL_CODEC
                .parse(registryLookup.getOps(NbtOps.INSTANCE), nbt)
                .result()
                .orElse(ItemStack.EMPTY);
    }

    private static List<ItemCount> countStacks(List<ItemStack> stacks) {
        Object2IntOpenHashMap<ItemType> counts = new Object2IntOpenHashMap<>();
        Map<ItemType, ItemStack> displayStacks = new HashMap<>();

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
            Object2IntOpenHashMap<ItemType> counts,
            Map<ItemType, ItemStack> displayStacks,
            ItemStack stack,
            int count
    ) {
        ItemStack displayStack = stack.copy();
        displayStack.setCount(1);
        ItemType key = new ItemType(displayStack, true, true);
        counts.addTo(key, count);
        displayStacks.putIfAbsent(key, displayStack);
    }

    private static List<ItemCount> toItemCounts(Object2IntOpenHashMap<ItemType> counts, Map<ItemType, ItemStack> displayStacks) {
        List<ItemCount> result = new ArrayList<>();

        for (ItemType type : counts.keySet()) {
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
            stack = stackFromBlockEntityId(nbt.getString("id", ""));
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
        return new ItemType(stack, true, true).toString();
    }

    private static String itemId(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private static String contentSignature(List<ItemCount> contents) {
        StringBuilder builder = new StringBuilder();

        for (ItemCount item : contents) {
            builder.append(item.signature()).append('#').append(item.count()).append(';');
        }

        return builder.toString();
    }

    private static int getGroupHeight(ContainerGroup group, int rowWidth) {
        int contentWidth = Math.max(ITEM_CELL_WIDTH, rowWidth - CONTAINER_COLUMN_WIDTH - COUNT_COLUMN_WIDTH - ACTION_COLUMN_WIDTH - 18);
        int columns = Math.max(1, contentWidth / ITEM_CELL_WIDTH);
        int rows = Math.max(1, (group.contents().size() + columns - 1) / columns);
        int contentHeight = rows * ITEM_CELL_HEIGHT + 8;
        return Math.max(group.sourceLabel() == null ? 30 : 40, contentHeight);
    }

    private static String fitText(String text, int maxWidth) {
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

    private static String formatCount(int count) {
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

    private record ItemCount(ItemStack stack, int count, String signature) {
        private int totalCount(int multiplier) {
            return this.count * multiplier;
        }
    }

    private record ContainerGroup(
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

    private static final class ContainerMaterialsData {
        private final LitematicaSchematic schematic;
        private final List<String> regions;
        private final Set<String> ignoredGroupSignatures = new HashSet<>();
        private List<ContainerGroup> groups;

        private ContainerMaterialsData(LitematicaSchematic schematic, List<String> regions) {
            this.schematic = schematic;
            this.regions = regions;
            this.refresh();
        }

        private static ContainerMaterialsData create(LitematicaSchematic schematic) {
            return new ContainerMaterialsData(schematic, List.copyOf(schematic.getAreas().keySet()));
        }

        private void refresh() {
            this.groups = createContainerGroups(this.schematic, this.regions);
        }

        private List<ContainerGroup> visibleGroups() {
            return this.groups.stream()
                    .filter(group -> !this.ignoredGroupSignatures.contains(group.signature()))
                    .toList();
        }

        private List<QuickMaterialCollector.MaterialRequest> createReplacementMaterialRequests(int multiplier) {
            Object2IntOpenHashMap<ItemType> counts = new Object2IntOpenHashMap<>();
            Map<ItemType, ItemStack> displayStacks = new HashMap<>();
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

        private void ignoreGroup(ContainerGroup group) {
            this.ignoredGroupSignatures.add(group.signature());
        }

        private void clearIgnoredGroups() {
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

        private String detailTitle() {
            return StringUtils.translate(
                    "quickcraft.litematica.title.container_material_details",
                    this.schematic.getMetadata().getName(),
                    this.regions.size(),
                    this.schematic.getAreas().size()
            );
        }

        private int totalVisibleContainerCount() {
            int total = 0;

            for (ContainerGroup group : this.visibleGroups()) {
                total += group.containerCount();
            }

            return total;
        }
    }

    private static final class ContainerMaterialList extends MaterialListBase implements ContainerMaterialRequestSource {
        private final ContainerMaterialsData data;
        private final Screen parent;

        private ContainerMaterialList(ContainerMaterialsData data, Screen parent) {
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
            this.data.refresh();
            this.setMaterialListEntries(this.createMaterialEntries());
        }

        @Override
        public void clearIgnored() {
            this.data.clearIgnoredGroups();
            super.clearIgnored();
            this.reCreateMaterialList();
        }

        private List<MaterialListEntry> createMaterialEntries() {
            Object2IntOpenHashMap<ItemType> counts = new Object2IntOpenHashMap<>();
            Map<ItemType, ItemStack> displayStacks = new HashMap<>();

            for (ContainerGroup group : this.data.visibleGroups()) {
                for (ItemCount item : group.contents()) {
                    ItemStack displayStack = item.stack().copy();
                    displayStack.setCount(1);
                    ItemType key = new ItemType(displayStack, true, true);
                    counts.addTo(key, item.totalCount(group.containerCount()));
                    displayStacks.putIfAbsent(key, displayStack);
                }
            }

            List<MaterialListEntry> entries = new ArrayList<>();

            for (ItemType type : counts.keySet()) {
                ItemStack stack = displayStacks.get(type);

                if (stack != null && !stack.isEmpty()) {
                    int count = counts.getInt(type);
                    entries.add(new MaterialListEntry(stack, count, count, 0, 0));
                }
            }

            entries.sort(Comparator.comparing(entry -> itemSignature(entry.getStack())));
            return entries;
        }

        @Override
        public List<QuickMaterialCollector.MaterialRequest> quickcraft$getReplacementMaterialRequests() {
            return this.data.createReplacementMaterialRequests(this.getMultiplier());
        }
    }

    private static class ContainerMaterialListScreen extends GuiMaterialList {
        private final ContainerMaterialList materialList;

        private ContainerMaterialListScreen(ContainerMaterialList materialList) {
            super(materialList);
            this.materialList = materialList;
        }

        @Override
        public void initGui() {
            super.initGui();

            int gap = 1;
            String detailsLabel = StringUtils.translate("quickcraft.litematica.button.container_material_details");
            String materialLabel = StringUtils.translate(BUTTON_KEY);
            ButtonPlacement placement = this.getContainerNavButtonPlacement(gap, detailsLabel, materialLabel);
            int x = placement.x();
            int y = placement.y();

            ButtonGeneric detailsButton = this.createNavButton(x, y, detailsLabel);
            this.addButton(detailsButton, (button, mouseButton) -> openDetailScreen(this.materialList));
            x += detailsButton.getWidth() + gap;

            ButtonGeneric materialButton = this.createNavButton(x, y, materialLabel);
            materialButton.setEnabled(false);
            this.addButton(materialButton, (button, mouseButton) -> {
            });
        }

        private ButtonPlacement getContainerNavButtonPlacement(int gap, String detailsLabel, String materialLabel) {
            List<ButtonBase> buttons = ((GuiBaseAccessor) (Object) this).quickcraft$getButtons();
            int bottomButtonRow = this.height - 22;
            int y = buttons.stream().anyMatch(button -> button.getY() == bottomButtonRow) ? bottomButtonRow : 24;
            int x = buttons.stream()
                    .filter(button -> button.getY() == y)
                    .mapToInt(button -> button.getX() + button.getWidth() + gap)
                    .max()
                    .orElse(12);

            int detailsWidth = this.getStringWidth(detailsLabel) + 10;
            int materialWidth = this.getStringWidth(materialLabel) + 10;

            if (x + detailsWidth + gap + materialWidth > this.width - 12) {
                return new ButtonPlacement(12, Math.max(24, this.height - 58));
            }

            return new ButtonPlacement(x, y);
        }

        private ButtonGeneric createNavButton(int x, int y, String label) {
            return new ButtonGeneric(x, y, this.getStringWidth(label) + 10, 20, label);
        }
    }

    private static final class ContainerMaterialsScreen
            extends GuiListBase<ContainerGroup, ContainerGroupEntryWidget, ContainerGroupListWidget> {
        private final ContainerMaterialList materialList;
        private final ContainerMaterialsData data;

        private ContainerMaterialsScreen(ContainerMaterialList materialList) {
            super(10, 44);
            this.materialList = materialList;
            this.data = materialList.data;
            this.title = this.data.detailTitle();
            this.useTitleHierarchy = false;
        }

        @Override
        protected int getBrowserWidth() {
            return this.width - 20;
        }

        @Override
        protected int getBrowserHeight() {
            return this.height - 82;
        }

        @Override
        public void initGui() {
            super.initGui();

            int x = 12;
            int y = 24;
            x += this.createButton(x, y, ButtonType.REFRESH) + 2;
            x += this.createButton(x, y, ButtonType.MATERIAL_LIST) + 2;
            ButtonGeneric detailsButton = new ButtonGeneric(
                    x,
                    y,
                    -1,
                    20,
                    StringUtils.translate("quickcraft.litematica.button.container_material_details")
            );
            detailsButton.setEnabled(false);
            this.addButton(detailsButton, (button, mouseButton) -> {
            });

            String summary = StringUtils.translate(
                    "quickcraft.litematica.label.summary",
                    this.data.visibleGroups().size(),
                    this.data.totalVisibleContainerCount()
            );
            this.addLabel(12, this.height - 36, -1, 12, 0xFFFFFFFF, summary);

            ButtonListenerChangeMenu.ButtonType type = ButtonListenerChangeMenu.ButtonType.MAIN_MENU;
            String label = StringUtils.translate(type.getLabelKey());
            int buttonWidth = this.getStringWidth(label) + 20;
            ButtonGeneric button = new ButtonGeneric(this.width - buttonWidth - 10, this.height - 36, buttonWidth, 20, label);
            this.addButton(button, new ButtonListenerChangeMenu(type, this.getParent()));
        }

        @Override
        public void drawContents(GuiContext drawContext, int mouseX, int mouseY, float partialTicks) {
            super.drawContents(drawContext, mouseX, mouseY, partialTicks);

            if (this.data.visibleGroups().isEmpty()) {
                String text = StringUtils.translate("quickcraft.litematica.label.no_container_contents");
                drawContext.drawText(this.textRenderer, text, this.width / 2 - this.getStringWidth(text) / 2, this.height / 2, 0xFFFFFFFF, false);
            }
        }

        @Override
        protected ContainerGroupListWidget createListWidget(int listX, int listY) {
            return new ContainerGroupListWidget(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
        }

        private int createButton(int x, int y, ButtonType type) {
            String label = StringUtils.translate(type.translationKey);
            ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, label);
            this.addButton(button, new ContainerMaterialsButtonListener(this, type));
            return button.getWidth();
        }

        private List<ContainerGroup> groups() {
            return this.data.visibleGroups();
        }

        private void refreshData() {
            this.materialList.reCreateMaterialList();
            this.reCreateListWidget();
            this.initGui();
        }

        private void ignoreGroup(ContainerGroup group) {
            this.data.ignoreGroup(group);
            this.materialList.reCreateMaterialList();
            this.reCreateListWidget();
            this.initGui();
        }

        private void openMaterialList() {
            openMaterialListScreen(this.materialList);
        }
    }

    private static final class ContainerGroupListWidget extends WidgetListBase<ContainerGroup, ContainerGroupEntryWidget> {
        private final ContainerMaterialsScreen gui;

        private ContainerGroupListWidget(int x, int y, int width, int height, ContainerMaterialsScreen gui) {
            super(x, y, width, height, null);
            this.gui = gui;
            this.browserEntryHeight = HEADER_HEIGHT;
        }

        @Override
        protected Collection<ContainerGroup> getAllEntries() {
            return this.gui.groups();
        }

        @Override
        protected int getBrowserEntryHeightFor(ContainerGroup group) {
            return group == null ? HEADER_HEIGHT : getGroupHeight(group, this.browserEntryWidth);
        }

        @Override
        protected ContainerGroupEntryWidget createHeaderWidget(int x, int y, int listIndexStart, int usableHeight, int usedHeight) {
            if (usedHeight + HEADER_HEIGHT > usableHeight) {
                return null;
            }

            return new ContainerGroupEntryWidget(x, y, this.browserEntryWidth, HEADER_HEIGHT, true, null, this.gui);
        }

        @Override
        protected ContainerGroupEntryWidget createListEntryWidget(int x, int y, int listIndex, boolean isOdd, ContainerGroup entry) {
            return new ContainerGroupEntryWidget(
                    x,
                    y,
                    this.browserEntryWidth,
                    getGroupHeight(entry, this.browserEntryWidth),
                    isOdd,
                    entry,
                    this.gui
            );
        }
    }

    private static final class ContainerGroupEntryWidget extends WidgetListEntryBase<ContainerGroup> {
        private final ContainerMaterialsScreen gui;
        private final boolean isOdd;

        private ContainerGroupEntryWidget(
                int x,
                int y,
                int width,
                int height,
                boolean isOdd,
                ContainerGroup entry,
                ContainerMaterialsScreen gui
        ) {
            super(x, y, width, height, entry, entry == null ? -1 : 0);
            this.gui = gui;
            this.isOdd = isOdd;

            if (entry != null) {
                ButtonGeneric button = new ButtonGeneric(
                        x + width - 4,
                        y + 1,
                        -1,
                        true,
                        "quickcraft.litematica.button.ignore_container"
                );
                this.addButton(button, (clickedButton, mouseButton) -> this.gui.ignoreGroup(entry));
            }
        }

        public boolean canSelectAt(int mouseX, int mouseY, int mouseButton) {
            return false;
        }

        @Override
        public void render(GuiContext drawContext, int mouseX, int mouseY, boolean selected) {
            if (this.entry == null) {
                this.renderHeader(drawContext);
                return;
            }

            if (this.isMouseOver(mouseX, mouseY)) {
                RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0707070);
            } else if (this.isOdd) {
                RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0101010);
            } else {
                RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0303030);
            }

            int containerX = this.x + 6;
            int countX = this.x + CONTAINER_COLUMN_WIDTH + 4;
            int contentsX = this.x + CONTAINER_COLUMN_WIDTH + COUNT_COLUMN_WIDTH + 8;
            int yText = this.y + 6;

            RenderUtils.drawRect(drawContext, containerX, this.y + 6, 16, 16, 0x20FFFFFF);
            drawContext.drawItem(this.entry.containerStack(), containerX, this.y + 6);
            this.drawString(drawContext, containerX + 20, yText, 0xFFFFFFFF, fitText(this.entry.containerName(), CONTAINER_COLUMN_WIDTH - 28));

            if (this.entry.sourceLabel() != null) {
                this.drawString(drawContext, containerX + 20, yText + 11, 0xFFAAAAAA, fitText(this.entry.sourceLabel(), CONTAINER_COLUMN_WIDTH - 28));
            }

            this.drawString(drawContext, countX, this.y + 10, 0xFFFFFFFF, "x" + this.entry.containerCount());
            this.renderContents(drawContext, contentsX, mouseX, mouseY);
            super.render(drawContext, mouseX, mouseY, selected);
        }

        @Override
        public void postRenderHovered(GuiContext drawContext, int mouseX, int mouseY, boolean selected) {
            if (this.entry == null) {
                return;
            }

            int containerX = this.x + 6;
            int containerY = this.y + 6;

            if (mouseX >= containerX && mouseX < containerX + 16 && mouseY >= containerY && mouseY < containerY + 16) {
                InventoryOverlay.renderStackToolTipStyled(drawContext, mouseX, mouseY, this.entry.containerStack());
                return;
            }

            ItemCount hovered = this.getHoveredItem(mouseX, mouseY);

            if (hovered != null) {
                InventoryOverlay.renderStackToolTipStyled(drawContext, mouseX, mouseY, hovered.stack());
                return;
            }

            super.postRenderHovered(drawContext, mouseX, mouseY, selected);
        }

        private void renderHeader(GuiContext drawContext) {
            RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0101010);

            int containerX = this.x + 6;
            int countX = this.x + CONTAINER_COLUMN_WIDTH + 4;
            int contentsX = this.x + CONTAINER_COLUMN_WIDTH + COUNT_COLUMN_WIDTH + 8;
            int actionX = this.x + this.width - ACTION_COLUMN_WIDTH + 4;
            int endX = this.x + this.width - 2;
            int y = this.y + 7;

            this.drawHeaderCell(drawContext, containerX, countX);
            this.drawHeaderCell(drawContext, countX, contentsX);
            this.drawHeaderCell(drawContext, contentsX, actionX);
            this.drawHeaderCell(drawContext, actionX, endX);
            this.drawString(drawContext, containerX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.container") + GuiBase.TXT_RST);
            this.drawString(drawContext, countX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.count") + GuiBase.TXT_RST);
            this.drawString(drawContext, contentsX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.contents") + GuiBase.TXT_RST);
            this.drawString(drawContext, actionX, y, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("quickcraft.litematica.header.action") + GuiBase.TXT_RST);
        }

        private void drawHeaderCell(GuiContext drawContext, int xStart, int xEnd) {
            RenderUtils.drawOutline(drawContext, xStart - 3, this.y + 1, xEnd - xStart - 2, this.height - 2, 0xC0707070);
        }

        private void renderContents(GuiContext drawContext, int contentsX, int mouseX, int mouseY) {
            if (this.entry.contents().isEmpty()) {
                this.drawString(drawContext, contentsX, this.y + 10, 0xFFAAAAAA, StringUtils.translate("quickcraft.litematica.label.empty_contents"));
                return;
            }

            int columns = getContentColumns(contentsX);

            for (int i = 0; i < this.entry.contents().size(); i++) {
                ItemCount item = this.entry.contents().get(i);
                int itemX = contentsX + (i % columns) * ITEM_CELL_WIDTH;
                int itemY = this.y + 6 + (i / columns) * ITEM_CELL_HEIGHT;
                ItemStack displayStack = item.stack();

                RenderUtils.drawRect(drawContext, itemX, itemY, 16, 16, 0x20FFFFFF);
                drawContext.drawItem(displayStack, itemX, itemY);
                drawContext.drawStackOverlay(
                        this.textRenderer,
                        displayStack,
                        itemX,
                        itemY,
                        formatCount(item.totalCount(this.entry.containerCount()))
                );
            }
        }

        private ItemCount getHoveredItem(int mouseX, int mouseY) {
            int contentsX = this.x + CONTAINER_COLUMN_WIDTH + COUNT_COLUMN_WIDTH + 8;
            int columns = getContentColumns(contentsX);

            for (int i = 0; i < this.entry.contents().size(); i++) {
                int itemX = contentsX + (i % columns) * ITEM_CELL_WIDTH;
                int itemY = this.y + 6 + (i / columns) * ITEM_CELL_HEIGHT;

                if (mouseX >= itemX && mouseX < itemX + 16 && mouseY >= itemY && mouseY < itemY + 16) {
                    return this.entry.contents().get(i);
                }
            }

            return null;
        }

        private int getContentColumns(int contentsX) {
            int contentWidth = Math.max(ITEM_CELL_WIDTH, this.x + this.width - contentsX - ACTION_COLUMN_WIDTH - 10);
            return Math.max(1, contentWidth / ITEM_CELL_WIDTH);
        }
    }

    private enum ButtonType {
        REFRESH("quickcraft.litematica.button.refresh_container_material_list"),
        MATERIAL_LIST(BUTTON_KEY);

        private final String translationKey;

        ButtonType(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    private static final class ContainerMaterialsButtonListener implements IButtonActionListener {
        private final ContainerMaterialsScreen screen;
        private final ButtonType type;

        private ContainerMaterialsButtonListener(ContainerMaterialsScreen screen, ButtonType type) {
            this.screen = screen;
            this.type = type;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            switch (this.type) {
                case REFRESH -> this.screen.refreshData();
                case MATERIAL_LIST -> this.screen.openMaterialList();
            }
        }
    }
}
