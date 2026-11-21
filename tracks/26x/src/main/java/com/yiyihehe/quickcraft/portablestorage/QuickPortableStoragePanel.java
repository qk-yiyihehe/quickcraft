package com.yiyihehe.quickcraft.portablestorage;

import com.ibm.icu.text.Transliterator;
import com.mojang.blaze3d.platform.InputConstants;
import com.yiyihehe.quickcraft.QuickCraftKeyBindings;
import com.yiyihehe.quickcraft.QuickSlotTransfer;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.RecipeBookToggleButtonAccessor;
import com.yiyihehe.quickcraft.mixin.RecipeBookScreenAccessor;
import com.yiyihehe.quickcraft.render.QuickCompactIconButton;
import com.yiyihehe.quickcraft.render.QuickDraggableButton;
import com.yiyihehe.quickcraft.sort.QuickCrossBoxSortStackKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.yiyihehe.quickcraft.portablestorage.QuickPortableStorageTransfer.*;

public final class QuickPortableStoragePanel {
    public interface Access {
        QuickPortableStoragePanel quickcraft$portableStoragePanel();
    }

    private static final int CELL = 18;
    private static final int GUTTER = 22;
    private static final int HEADER = 21;
    private static final int FOOTER = 23;
    private static final int VIEWPORT_MARGIN = 4;
    private static final int CATEGORY_RAIL_HEIGHT = Category.values().length * CELL + 6;
    private static final int GLASS = 0x51131D26;
    private static final int TILE = 0x8527313C;
    private static final int HOVERED = 0xB13C5662;
    private static final int SELECTED = 0xC7355059;
    private static final int DIVIDER = 0x577D929F;
    private static final int ACCENT = 0xECABE3DD;
    private static final Identifier BUTTON_ICON =
            Identifier.fromNamespaceAndPath("quickcraft", "textures/gui/workbench_shulker_mode.png");
    private static final ItemStack[] SOURCE_ICONS = {
            new ItemStack(Items.SHULKER_BOX), new ItemStack(Items.ENDER_CHEST), new ItemStack(Items.BUNDLE)
    };

    private final InventoryScreen screen;
    private boolean shown = QuickCraftConfigs.ContainerTools.PORTABLE_STORAGE_OPEN_BY_DEFAULT.getBooleanValue();
    private final QuickCompactIconButton button;
    private final EditBox search;
    private final List<QuickCrossBoxSortStackKey> order = new ArrayList<>();
    private final Map<QuickCrossBoxSortStackKey, Integer> amounts = new LinkedHashMap<>();
    private final Map<String, String> pinyin = new HashMap<>();
    private final Set<Integer> visitedSlots = new HashSet<>();
    private final Set<QuickCrossBoxSortStackKey> visitedItems = new HashSet<>();
    private final List<ItemStack> inventorySnapshot = new ArrayList<>();
    private long lastStorageRevision = -1;
    private Source source = Source.SHULKER;
    private Category category = Category.ALL;
    private int left, top, width, height, inventoryLeft, inventoryTop, columns, rows, scroll;
    private int mouseX, mouseY;
    private int gesture = -1;
    private boolean taking;
    private boolean storingFromSlots;
    private double lastStoreX, lastStoreY;
    private boolean dirty = true;
    private boolean enderRequested;

    public QuickPortableStoragePanel(InventoryScreen screen, Minecraft client) {
        this.screen = screen;
        this.search = QuickPortableStorageUiAccess.searchBox(client.font, label("search"));
        search.setHint(label("search").copy().withStyle(style -> style.withColor(0xB7C6CF)));
        search.setBordered(false);
        search.setTextColor(0xFFF3F5F8);
        search.setMaxLength(64);
        search.setResponder(query -> resetFilter());
        this.button = new QuickCompactIconButton(0, 0, label("toggle"), widget -> {
            shown = !shown;
            if (shown) {
                enderRequested = false;
            } else {
                releaseControls();
            }
        }, QuickDraggableButton.PositionKey.PORTABLE_STORAGE) {
            @Override
            protected boolean isToggleSelected() {
                return visible();
            }

            @Override
            protected void extractIcon(GuiGraphicsExtractor context, int x, int y, boolean hovered) {
                context.blit(RenderPipelines.GUI_TEXTURED, BUTTON_ICON, x, y, 0, 0, 10, 10, 10, 10);
            }
        };
    }

    public QuickCompactIconButton button() {
        return button;
    }

    public EditBox search() {
        return search;
    }

    public void layout(int x, int y, int inventoryWidth, int inventoryHeight) {
        inventoryLeft = x;
        inventoryTop = y;
        Slot firstBackpackSlot = inventorySlot(screen.getMenu(), 9);
        // Item icons are drawn one pixel inside their cells; anchor those pixels to the main inventory rows.
        int firstRow = y + (firstBackpackSlot != null ? firstBackpackSlot.y : 0);
        int firstTop = VIEWPORT_MARGIN + Math.floorMod(firstRow - HEADER - 1 - VIEWPORT_MARGIN, CELL);
        int availableHeight = screen.height - VIEWPORT_MARGIN - firstTop;
        int requestedColumns = QuickCraftConfigs.ContainerTools.PORTABLE_STORAGE_COLUMNS.getIntegerValue();
        int requestedRows = QuickCraftConfigs.ContainerTools.PORTABLE_STORAGE_ROWS.getIntegerValue();
        columns = Math.clamp((x - GUTTER - 8) / CELL, 0, requestedColumns);
        rows = Math.clamp((availableHeight - HEADER - FOOTER) / CELL, 0, requestedRows);
        width = GUTTER + columns * CELL + 4;
        height = Math.max(HEADER + rows * CELL, CATEGORY_RAIL_HEIGHT) + FOOTER;
        left = x - width - 4;
        int centeredTop = y + (inventoryHeight - height) / 2;
        int centeredStep = Math.round((centeredTop - firstTop) / (float) CELL);
        top = firstTop + CELL * Math.clamp(centeredStep, 0, Math.max(0, (availableHeight - height) / CELL));
        button.setDefaultPosition(x + inventoryWidth - 18, y + 5);
        button.visible = panelEnabled() && fitsViewport() && !recipeBookOpen();
        button.active = !waitingForOpen(screen);
        search.visible = visible();
        if (!search.visible) {
            releaseControls();
        }
        search.setX(left + GUTTER + 4);
        search.setY(top + 6);
        search.setWidth(Math.max(0, columns * CELL - 8));
        if (fitsViewport()) {
            clampScroll();
        }
    }

    private boolean recipeBookOpen() {
        return ((RecipeBookScreenAccessor) (Object) screen).quickcraft$getRecipeBook().isVisible();
    }

    private boolean visible() {
        return panelEnabled() && shown && fitsViewport() && !recipeBookOpen();
    }

    private boolean fitsViewport() {
        return columns >= 2 && rows > 0 && top + height <= screen.height - VIEWPORT_MARGIN;
    }

    private static QuickPortableStoragePanel panel(AbstractContainerScreen<?> screen) {
        return screen instanceof Access access ? access.quickcraft$portableStoragePanel() : null;
    }

    static boolean isVisible(InventoryScreen screen) {
        var panel = panel(screen);
        return panel != null && panel.visible();
    }

    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int x, int y, float delta) {
        var panel = panel(screen);
        if (panel != null) {
            panel.render(graphics, x, y, delta);
        }
    }

    private void releaseControls() {
        if (screen.getFocused() == search) {
            screen.setFocused(null);
        }
        search.setFocused(false);
        resetGesture();
    }

    private void resetGesture() {
        gesture = -1;
        storingFromSlots = false;
        visitedItems.clear();
        visitedSlots.clear();
    }

    private void resetFilter() {
        order.clear();
        dirty = true;
        scroll = 0;
    }

    public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        if (!visible()) {
            return;
        }
        var client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (source == Source.ENDER_CHEST && !enderRequested && !hasEnderSnapshot() && hasEnderChest(client.player)) {
            enderRequested = true;
            request(screen, source, Mode.PREVIEW, ItemStack.EMPTY, -1);
        }
        updateItems(client);
        int gridLeft = left + GUTTER;
        int gridRight = left + width - 4;
        graphics.fill(left + 2, top + 3, gridLeft - 2, top + CATEGORY_RAIL_HEIGHT - 1, GLASS);
        graphics.fill(gridLeft - 3, top + 4, gridLeft - 2, top + CATEGORY_RAIL_HEIGHT - 2, DIVIDER);
        graphics.fill(gridLeft + 1, top + 3, gridRight - 1, top + HEADER - 3, search.isFocused() ? HOVERED : TILE);
        graphics.fill(gridLeft + 2, top + HEADER - 4, gridRight - 2, top + HEADER - 3,
                search.isFocused() ? ACCENT : DIVIDER);
        graphics.fill(gridLeft, top + HEADER, gridRight, top + HEADER + rows * CELL, GLASS);
        graphics.fill(gridLeft, top + HEADER, gridRight, top + HEADER + 1, DIVIDER);
        for (Category tab : Category.values()) {
            int x = left + 3;
            int y = top + 4 + tab.ordinal() * CELL;
            icon(graphics, tab.icon, x, y, category == tab);
            if (hit(mouseX, mouseY, x, y, CELL, CELL)) {
                tooltip(graphics, label("category." + tab.name().toLowerCase(Locale.ROOT)));
            }
        }
        int sourceY = top + height - 20;
        int sourceX = sourceX();
        graphics.fill(sourceX - 1, sourceY - 1, sourceX + Source.values().length * CELL, sourceY + CELL, GLASS);
        for (Source tab : Source.values()) {
            int x = sourceX + tab.ordinal() * CELL;
            icon(graphics, SOURCE_ICONS[tab.ordinal()], x, sourceY, source == tab);
            if (hit(mouseX, mouseY, x, sourceY, CELL, CELL)) {
                tooltip(graphics, sourceLabel(tab));
            }
        }
        search.extractRenderState(graphics, mouseX, mouseY, delta);
        renderGrid(graphics, client);
        renderScrollBar(graphics);
    }

    private void updateItems(Minecraft client) {
        var inventory = client.player.getInventory();
        long currentStorageRevision = storageRevision();
        boolean changed = dirty || lastStorageRevision != currentStorageRevision || inventorySnapshot.size() != 36;
        for (int i = 0; !changed && i < 36; i++) {
            changed = !same(inventorySnapshot.get(i), inventory.getItem(i));
        }
        // Keep positions fixed during a take drag so another item cannot slide under the pointer.
        boolean takingGesture = taking && gesture == InputConstants.MOUSE_BUTTON_LEFT;
        if (!changed) {
            if (!takingGesture && order.size() != amounts.size()) {
                order.removeIf(key -> !amounts.containsKey(key));
                clampScroll();
            }
            return;
        }
        dirty = false;
        inventorySnapshot.clear();
        for (int i = 0; i < 36; i++) {
            inventorySnapshot.add(inventory.getItem(i).copy());
        }
        lastStorageRevision = currentStorageRevision;
        amounts.clear();
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        for (Entry entry : entries(client.player, source)) {
            var key = new QuickCrossBoxSortStackKey(entry.stack());
            amounts.merge(key, entry.stack().getCount(), Integer::sum);
        }
        amounts.keySet().removeIf(key -> {
            ItemStack stack = key.template();
            return !category.accepts(stack) || !matches(stack, query);
        });
        if (!takingGesture) {
            order.removeIf(key -> !amounts.containsKey(key));
        }
        Set<QuickCrossBoxSortStackKey> placed = new HashSet<>(order);
        for (var key : amounts.keySet()) {
            if (placed.add(key)) {
                order.add(key);
            }
        }
        clampScroll();
    }

    private boolean matches(ItemStack stack, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        if (name.contains(query) || BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(query)) {
            return true;
        }
        return name.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                && pinyin.computeIfAbsent(name, key -> Pinyin.TRANSLITERATOR.transliterate(key)
                .toLowerCase(Locale.ROOT).replace(" ", "")).contains(query.replace(" ", ""));
    }

    private void renderGrid(GuiGraphicsExtractor graphics, Minecraft client) {
        for (int i = 0; i < columns * rows; i++) {
            int x = left + GUTTER + i % columns * CELL;
            int y = top + HEADER + i / columns * CELL;
            boolean hovered = hit(mouseX, mouseY, x, y, CELL, CELL);
            int at = scroll * columns + i;
            var key = at < order.size() ? order.get(at) : null;
            Integer count = key == null ? null : amounts.get(key);
            if (hovered) {
                drawCell(graphics, x, y, count == null ? GLASS : HOVERED);
                drawHoverBorder(graphics, x, y);
            } else if (count != null) {
                drawCell(graphics, x, y, TILE);
            }
            if (count == null) {
                continue;
            }
            ItemStack stack = key.template();
            graphics.item(stack, x + 1, y + 1);
            if (count > 1) {
                String text = count >= 1000 ? (count / 1000) + "k" : count.toString();
                graphics.text(client.font, text, x + 17 - client.font.width(text), y + 9, 0xFFFFFFFF, true);
            }
            if (hovered && screen.getMenu().getCarried().isEmpty()) {
                List<Component> tooltip = new ArrayList<>(Screen.getTooltipFromItem(client, stack));
                tooltip.add(label("count", count));
                graphics.setTooltipForNextFrame(client.font, tooltip, java.util.Optional.empty(), mouseX, mouseY);
            }
        }
        if (amounts.isEmpty()) {
            Component empty = label(source == Source.ENDER_CHEST && !hasEnderSnapshot() ? "ender_refresh" : "empty");
            if (client.font.width(empty) > columns * CELL - 4) {
                empty = Component.literal(client.font.plainSubstrByWidth(empty.getString(), columns * CELL - 8) + "…");
            }
            graphics.text(client.font, empty, left + GUTTER + 3, top + HEADER + 6, 0xFFF1F1F1, true);
        }
    }

    private void icon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, boolean selected) {
        boolean hovered = hit(mouseX, mouseY, x, y, CELL, CELL);
        if (selected || hovered) {
            drawCell(graphics, x, y, selected ? SELECTED : HOVERED);
        }
        if (selected) {
            graphics.fill(x, y + 3, x + 1, y + CELL - 3, ACCENT);
        }
        graphics.item(stack, x + 1, y + 1);
    }

    private static void drawCell(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x, y + 1, x + CELL - 1, y + CELL - 2, color);
        graphics.fill(x + 1, y, x + CELL - 2, y + 1, color);
        graphics.fill(x + 1, y + CELL - 2, x + CELL - 2, y + CELL - 1, color);
    }

    private static void drawHoverBorder(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + CELL, y + 1, ACCENT);
        graphics.fill(x, y + CELL - 1, x + CELL, y + CELL, ACCENT);
        graphics.fill(x, y + 1, x + 1, y + CELL - 1, ACCENT);
        graphics.fill(x + CELL - 1, y + 1, x + CELL, y + CELL - 1, ACCENT);
    }

    private void renderScrollBar(GuiGraphicsExtractor graphics) {
        int maxScroll = maxScroll();
        if (maxScroll == 0) {
            return;
        }
        int x = left + width - 3;
        int y = top + HEADER;
        int trackHeight = rows * CELL;
        int thumbHeight = Math.max(8, trackHeight * rows / (rows + maxScroll));
        int thumbY = y + (trackHeight - thumbHeight) * scroll / maxScroll;
        graphics.fill(x, y, x + 1, y + trackHeight, DIVIDER);
        graphics.fill(x, thumbY, x + 1, thumbY + thumbHeight, ACCENT);
    }

    private void tooltip(GuiGraphicsExtractor graphics, Component text) {
        if (screen.getMenu().getCarried().isEmpty()) {
            graphics.setTooltipForNextFrame(Minecraft.getInstance().font, text, mouseX, mouseY);
        }
    }

    public static boolean click(AbstractContainerScreen<?> screen, double x, double y, int button) {
        var panel = panel(screen);
        if (panel == null || !panel.visible()) {
            return inUse(screen);
        }
        return panel.click(x, y, button);
    }

    private boolean click(double x, double y, int button) {
        if (screen.children().stream().anyMatch(child ->
                child instanceof QuickDraggableButton widget && widget.isMouseOver(x, y))) {
            releaseControls();
            return blocksVanillaInput(screen);
        }
        boolean leftClick = button == InputConstants.MOUSE_BUTTON_LEFT;
        boolean rightClick = button == InputConstants.MOUSE_BUTTON_RIGHT;
        if (hit(x, y, search.getX(), search.getY(), search.getWidth(), search.getHeight())) {
            return !screen.getMenu().getCarried().isEmpty() || blocksVanillaInput(screen);
        }
        releaseControls();
        if (insidePanel(x, y)) {
            gesture = button;
            taking = screen.getMenu().getCarried().isEmpty();
            if (!screen.getMenu().getCarried().isEmpty() || !leftClick && !rightClick) {
                return true;
            }
            int sourceX = sourceX();
            if (leftClick && hit(x, y, sourceX, top + height - 20, Source.values().length * CELL, CELL)) {
                source = Source.values()[(int) (x - sourceX) / CELL];
                resetFilter();
                if (source == Source.ENDER_CHEST) {
                    enderRequested = true;
                    request(screen, source, Mode.PREVIEW, ItemStack.EMPTY, -1);
                }
            } else if (leftClick && hit(x, y, left + 3, top + 4, CELL, Category.values().length * CELL)) {
                category = Category.values()[(int) (y - top - 4) / CELL];
                resetFilter();
            } else if (leftClick && QuickCraftKeyBindings.isAltDown()) {
                takeAt(x, y, Mode.TAKE_ALL);
            } else {
                takeAt(x, y, rightClick || QuickCraftKeyBindings.isShiftDown() ? Mode.STACK : Mode.ONE);
            }
            return true;
        }
        if ((leftClick || rightClick) && QuickPortableStorageUiAccess.spaceDown()
                && screen.getMenu().getCarried().isEmpty() && storageSlotAt(x, y) != null) {
            gesture = button;
            taking = false;
            storingFromSlots = true;
            lastStoreX = x;
            lastStoreY = y;
            storeAt(x, y, rightClick ? Mode.STORE_ONE : Mode.STORE_STACK);
            return true;
        }
        return blocksVanillaInput(screen);
    }

    public static boolean drag(AbstractContainerScreen<?> screen, double x, double y, int button) {
        var panel = panel(screen);
        if (panel == null || panel.gesture != button || !panel.visible()) {
            return inUse(screen);
        }
        if (panel.taking && button == InputConstants.MOUSE_BUTTON_LEFT && !QuickCraftKeyBindings.isAltDown()
                && screen.getMenu().getCarried().isEmpty()) {
            panel.takeAt(x, y, QuickCraftKeyBindings.isShiftDown() ? Mode.STACK : Mode.ONE);
        } else if (panel.storingFromSlots) {
            if (QuickPortableStorageUiAccess.spaceDown()) {
                panel.storeAlong(x, y, button == InputConstants.MOUSE_BUTTON_RIGHT ? Mode.STORE_ONE : Mode.STORE_STACK);
            } else {
                panel.lastStoreX = x;
                panel.lastStoreY = y;
            }
        }
        return true;
    }

    public static boolean release(AbstractContainerScreen<?> screen, double x, double y, int button) {
        var panel = panel(screen);
        if (panel == null || !panel.visible()) {
            return inUse(screen);
        }
        boolean owned = panel.gesture == button;
        boolean releasedAfterTaking = owned && panel.taking;
        panel.resetGesture();
        if (!releasedAfterTaking && panel.insidePanel(x, y)
                && (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)
                && !screen.getMenu().getCarried().isEmpty()) {
            QuickPortableStorageUiAccess.cancelQuickCraft(screen);
            request(panel.screen, panel.source, Mode.STORE_CURSOR, screen.getMenu().getCarried(), -1);
            return true;
        }
        return owned || inUse(screen);
    }

    public static boolean scroll(AbstractContainerScreen<?> screen, double x, double y, double delta) {
        var panel = panel(screen);
        if (panel != null && panel.visible() && panel.insidePanel(x, y)) {
            panel.scroll -= (int) Math.signum(delta);
            panel.clampScroll();
            return true;
        }
        return blocksVanillaInput(screen);
    }

    public static boolean key(AbstractContainerScreen<?> screen, KeyEvent event) {
        var panel = panel(screen);
        // The recipe book hides the panel while open, so reserve Space before testing visibility.
        if (event.key() == InputConstants.KEY_SPACE && panel != null && panelEnabled() && panel.shown && panel.fitsViewport()
                && !Minecraft.getInstance().getLastInputType().isKeyboard()
                && screen.getFocused() instanceof RecipeBookToggleButtonAccessor button
                && button.quickcraft$getRecipeBookToggleSprites() == RecipeBookComponent.RECIPE_BUTTON_SPRITES) {
            return true;
        }
        if (panel == null || !panel.visible()) {
            return blocksVanillaInput(screen) && event.key() != InputConstants.KEY_ESCAPE;
        }
        if (panel.search.isFocused()) {
            if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_TAB) {
                panel.releaseControls();
            } else {
                panel.search.keyPressed(event);
            }
            return true;
        }
        if (Minecraft.getInstance().options.keySwapOffhand.matches(event) && panel.itemAt(panel.mouseX, panel.mouseY) != null) {
            panel.takeAt(panel.mouseX, panel.mouseY, Mode.OFFHAND);
            panel.visitedItems.clear();
            return true;
        }
        return blocksVanillaInput(screen) && event.key() != InputConstants.KEY_ESCAPE;
    }

    private void clampScroll() {
        scroll = Math.clamp(scroll, 0, maxScroll());
    }

    private int maxScroll() {
        int last = order.size();
        while (last > 0 && !amounts.containsKey(order.get(last - 1))) {
            last--;
        }
        return Math.max(0, (last + columns - 1) / columns - rows);
    }

    private void takeAt(double x, double y, Mode mode) {
        var key = itemAt(x, y);
        if (key != null && visitedItems.add(key)) {
            request(screen, source, mode, key.template(), -1);
        }
    }

    private QuickCrossBoxSortStackKey itemAt(double x, double y) {
        if (!hit(x, y, left + GUTTER, top + HEADER, columns * CELL, rows * CELL)) {
            return null;
        }
        int at = (scroll + (int) (y - top - HEADER) / CELL) * columns + (int) (x - left - GUTTER) / CELL;
        return at < order.size() && amounts.containsKey(order.get(at)) ? order.get(at) : null;
    }

    private void storeAlong(double x, double y, Mode mode) {
        double dx = x - lastStoreX;
        double dy = y - lastStoreY;
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)) / (CELL / 2.0)));
        for (int step = 1; step <= steps; step++) {
            double fraction = (double) step / steps;
            storeAt(lastStoreX + fraction * dx, lastStoreY + fraction * dy, mode);
        }
        lastStoreX = x;
        lastStoreY = y;
    }

    private void storeAt(double x, double y, Mode mode) {
        Slot slot = storageSlotAt(x, y);
        if (slot != null && slot.hasItem() && !visitedSlots.contains(slot.index)
                && request(screen, source, mode, slot.getItem(), slot.getContainerSlot())) {
            visitedSlots.add(slot.index);
        }
    }

    private Slot storageSlotAt(double x, double y) {
        for (Slot slot : screen.getMenu().slots) {
            if (QuickSlotTransfer.isPlayerStorageSlot(slot)
                    && hit(x, y, inventoryLeft + slot.x - 1, inventoryTop + slot.y - 1, CELL, CELL)) {
                return slot;
            }
        }
        return null;
    }

    private int sourceX() {
        int tabsWidth = Source.values().length * CELL;
        return Math.min(left + GUTTER + (columns * CELL - tabsWidth) / 2, left + width - 3 - tabsWidth);
    }

    private boolean insidePanel(double x, double y) {
        return hit(x, y, left, top, width, height);
    }

    private static boolean hit(double x, double y, int left, int top, int width, int height) {
        return x >= left && y >= top && x < left + width && y < top + height;
    }

    private static Component label(String key, Object... args) {
        return Component.translatable("quickcraft.portable_storage." + key, args);
    }

    private static Component sourceLabel(Source source) {
        return SOURCE_ICONS[source.ordinal()].getHoverName();
    }

    private static final class Pinyin {
        static final Transliterator TRANSLITERATOR = Transliterator.getInstance("Han-Latin; Latin-ASCII");
    }

    private enum Category {
        ALL(Items.COMPASS), BLOCKS(Items.BRICKS), REDSTONE(Items.REDSTONE),
        TOOLS(Items.IRON_PICKAXE), FOOD(Items.APPLE), OTHER(Items.STICK);

        final ItemStack icon;

        Category(net.minecraft.world.item.Item icon) {
            this.icon = new ItemStack(icon);
        }

        boolean accepts(ItemStack stack) {
            if (this == ALL) {
                return true;
            }
            boolean redstone = stack.is(Items.REDSTONE) || stack.is(Items.REPEATER) || stack.is(Items.COMPARATOR)
                    || stack.is(Items.REDSTONE_TORCH) || stack.is(Items.REDSTONE_BLOCK)
                    || stack.is(Items.PISTON) || stack.is(Items.STICKY_PISTON) || stack.is(Items.OBSERVER)
                    || stack.is(Items.HOPPER) || stack.is(Items.DISPENSER) || stack.is(Items.DROPPER)
                    || stack.is(Items.LEVER) || stack.is(Items.TARGET)
                    || Block.byItem(stack.getItem()).defaultBlockState().isSignalSource();
            boolean food = stack.has(DataComponents.FOOD);
            boolean tool = stack.has(DataComponents.TOOL) || stack.isDamageableItem();
            boolean block = stack.getItem() instanceof BlockItem;
            return switch (this) {
                case ALL -> true;
                case BLOCKS -> block && !redstone && !food && !tool;
                case REDSTONE -> redstone;
                case TOOLS -> tool && !redstone && !food;
                case FOOD -> food && !redstone;
                case OTHER -> !block && !redstone && !food && !tool;
            };
        }
    }
}
