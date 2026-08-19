package com.yiyihehe.quickcraft.gui;

import com.yiyihehe.quickcraft.QuickCraft;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetHoverInfo;
import fi.dy.masa.malilib.gui.widgets.WidgetLabel;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/**
 * 基于 malilib 的配置页。
 * 顶部分成辅助合成工具、辅助容器工具、快捷键绑定三个分页。
 */
public class QuickCraftConfigScreen extends GuiConfigsBase {
    private static final int EXPAND_BUTTON_WIDTH = 18;
    private static final int CHILD_INDENT = 28;
    private static final int NAME_COLUMN_PADDING = 16;
    private static final EnumSet<ConfigGroup> EXPANDED_GROUPS = EnumSet.noneOf(ConfigGroup.class);
    private static Tab currentTab = Tab.CRAFTING;

    public QuickCraftConfigScreen() {
        this(null);
    }

    public QuickCraftConfigScreen(Screen parent) {
        super(10, 50, QuickCraft.MOD_ID, parent, tr("screen.quickcraft.title", "QuickCraft Config"));
        this.setTitle(tr("screen.quickcraft.title", "QuickCraft Config"));
    }

    public static boolean isOpen(MinecraftClient client) {
        return client != null && client.currentScreen instanceof QuickCraftConfigScreen;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();

        int x = 10;
        int y = 26;

        for (Tab tab : Tab.values()) {
            x += this.createTabButton(x, y, tab) + 2;
        }
    }

    @Override
    protected int getConfigWidth() {
        return currentTab == Tab.HOTKEYS ? 320 : 240;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<ConfigOptionWrapper> wrappers = new ArrayList<>();

        for (IConfigBase config : currentTab.getOptions()) {
            ConfigGroup parentGroup = ConfigGroup.findByParent(config);

            if (parentGroup != null) {
                wrappers.add(new GroupedConfigOptionWrapper(config, parentGroup, false));
                parentGroup.children.forEach(child ->
                        wrappers.add(new GroupedConfigOptionWrapper(child, parentGroup, true))
                );
            } else if (ConfigGroup.findByChild(config) == null) {
                wrappers.add(new ConfigOptionWrapper(config));
            }
        }

        return wrappers;
    }

    @Override
    protected WidgetListConfigOptions createListWidget(int listX, int listY) {
        return new ExpandableConfigList(
                listX,
                listY,
                this.getBrowserWidth(),
                this.getBrowserHeight(),
                this.getConfigWidth(),
                this.useKeybindSearch(),
                this
        );
    }

    private int createTabButton(int x, int y, Tab tab) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, tab.getDisplayName());
        button.setEnabled(currentTab != tab);
        this.addButton(button, new TabButtonListener(tab, this));
        return button.getWidth();
    }

    private enum Tab {
        CRAFTING("screen.quickcraft.tab.crafting", "Crafting Tools", QuickCraftConfigs.Crafting.OPTIONS),
        CONTAINERS("screen.quickcraft.tab.containers", "Container Tools", QuickCraftConfigs.ContainerTools.OPTIONS),
        PROJECTION("screen.quickcraft.tab.projection", "Projection Tools", QuickCraftConfigs.ProjectionTools.OPTIONS),
        MOD_SUPPORT("screen.quickcraft.tab.mod_support", "Mod Support", QuickCraftConfigs.ModSupport.OPTIONS),
        HOTKEYS("screen.quickcraft.tab.hotkeys", "Hotkeys", QuickCraftConfigs.Hotkeys.OPTIONS);

        private final String translationKey;
        private final String fallback;
        private final List<IConfigBase> options;

        Tab(String translationKey, String fallback, List<IConfigBase> options) {
            this.translationKey = translationKey;
            this.fallback = fallback;
            this.options = options;
        }

        private List<IConfigBase> getOptions() {
            return options;
        }

        private String getDisplayName() {
            return tr(this.translationKey, this.fallback);
        }
    }

    private enum ConfigGroup {
        QUICK_TRANSFER(
                QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRANSFER,
                QuickCraftConfigs.ContainerTools.SHOW_MATCHING_TRANSFER_HIGHLIGHT,
                QuickCraftConfigs.ContainerTools.ENABLE_SCROLL_TRANSFER,
                QuickCraftConfigs.ContainerTools.QUICK_TRANSFER_RETAIN_ONE,
                QuickCraftConfigs.ContainerTools.SHOW_QUICK_STASH_BUTTON
        ),
        QUICK_TRADE(
                QuickCraftConfigs.ContainerTools.ENABLE_QUICK_TRADE,
                QuickCraftConfigs.ContainerTools.ENABLE_CONTINUOUS_TRADE,
                QuickCraftConfigs.ContainerTools.ENABLE_FAVORITE_TRADE
        ),
        QUICK_SORT(
                QuickCraftConfigs.ContainerTools.ENABLE_QUICK_SORT,
                QuickCraftConfigs.ContainerTools.QUICK_SORT_TOP_PRIORITY_ITEMS,
                QuickCraftConfigs.ContainerTools.QUICK_SORT_BOTTOM_PRIORITY_ITEMS,
                QuickCraftConfigs.ContainerTools.QUICK_SORT_SHULKER_BOXES_AT_END
        ),
        QUICK_BEACON(
                QuickCraftConfigs.ContainerTools.ENABLE_QUICK_BEACON,
                QuickCraftConfigs.ContainerTools.BEACON_EFFECT_ORDER
        ),
        CREATIVE_PACKING(
                QuickCraftConfigs.ContainerTools.ENABLE_CREATIVE_PACKING,
                QuickCraftConfigs.ContainerTools.CREATIVE_PACKING_BUNDLE_STACKS,
                QuickCraftConfigs.ContainerTools.ALLOW_CREATIVE_PACKING_NESTED_CONTAINERS
        ),
        FREE_CAMERA_ENHANCEMENT(
                QuickCraftConfigs.ContainerTools.ENABLE_FREE_CAMERA_ENHANCEMENT,
                QuickCraftConfigs.ContainerTools.FREE_CAMERA_BLOCK_INTERACTIONS,
                QuickCraftConfigs.ContainerTools.FREE_CAMERA_ENTITY_INTERACTIONS,
                QuickCraftConfigs.ContainerTools.FREE_CAMERA_EASY_PLACE
        ),
        SLOT_LOCK(
                QuickCraftConfigs.ContainerTools.SHOW_SLOT_LOCK_OVERLAY,
                QuickCraftConfigs.ContainerTools.ALLOW_MANUAL_LOCKED_SLOT_INTERACTION
        ),
        LITEMATICA_3D_PREVIEW(
                QuickCraftConfigs.ProjectionTools.SHOW_LITEMATICA_3D_PREVIEW,
                QuickCraftConfigs.ProjectionTools.AUTO_DISABLE_SHADERS_FOR_3D_PREVIEW,
                QuickCraftConfigs.ProjectionTools.ALLOW_ADDING_LITEMATICA_PREVIEW_IMAGES,
                QuickCraftConfigs.ProjectionTools.REPLACE_LITEMATICA_PREVIEW_WITH_3D
        ),
        HOLD_EASY_PLACE(
                QuickCraftConfigs.ProjectionTools.HOLD_EASY_PLACE,
                QuickCraftConfigs.ProjectionTools.HOLD_EASY_PLACE_CACHE_TIME_MS
        ),
        EASY_PLACE_ENTITIES(
                QuickCraftConfigs.ProjectionTools.ENABLE_EASY_PLACE_ENTITIES,
                QuickCraftConfigs.ProjectionTools.ALLOW_CREATIVE_ENTITY_PLACEMENT
        ),
        EASY_PLACE_VANILLA_INTERACTIONS(
                QuickCraftConfigs.ProjectionTools.ALLOW_EASY_PLACE_VANILLA_INTERACTIONS,
                QuickCraftConfigs.ProjectionTools.ALLOW_EASY_PLACE_INTERACTION_SCREENS,
                QuickCraftConfigs.ProjectionTools.ALLOW_EASY_PLACE_REDSTONE_INTERACTIONS,
                QuickCraftConfigs.ProjectionTools.ALLOW_EASY_PLACE_FUNCTIONAL_BLOCK_INTERACTIONS,
                QuickCraftConfigs.ProjectionTools.ALLOW_EASY_PLACE_FLUID_INTERACTIONS,
                QuickCraftConfigs.ProjectionTools.ALLOW_EASY_PLACE_TOOL_INTERACTIONS,
                QuickCraftConfigs.ProjectionTools.ALLOW_EASY_PLACE_DECORATION_INTERACTIONS,
                QuickCraftConfigs.ProjectionTools.ALLOW_EASY_PLACE_SURVIVAL_INTERACTIONS,
                QuickCraftConfigs.ProjectionTools.ALLOW_EASY_PLACE_SPECIAL_INTERACTIONS,
                QuickCraftConfigs.ProjectionTools.ALLOW_EASY_PLACE_DANGEROUS_INTERACTIONS,
                QuickCraftConfigs.ProjectionTools.ALLOW_EASY_PLACE_ADMIN_INTERACTIONS
        ),
        AUTO_COLLECT_MATERIALS(
                QuickCraftConfigs.ProjectionTools.ENABLE_AUTO_COLLECT_MATERIALS,
                QuickCraftConfigs.ProjectionTools.MATERIAL_COLLECT_EXTRA_0_TO_10,
                QuickCraftConfigs.ProjectionTools.MATERIAL_COLLECT_EXTRA_10_TO_20,
                QuickCraftConfigs.ProjectionTools.MATERIAL_COLLECT_EXTRA_20_TO_50,
                QuickCraftConfigs.ProjectionTools.MATERIAL_COLLECT_EXTRA_50_TO_100,
                QuickCraftConfigs.ProjectionTools.MATERIAL_COLLECT_EXTRA_100_TO_500,
                QuickCraftConfigs.ProjectionTools.MATERIAL_COLLECT_EXTRA_OVER_500
        ),
        CONTAINER_AUTOFILL(
                QuickCraftConfigs.ProjectionTools.ENABLE_LITEMATICA_CONTAINER_AUTOFILL,
                QuickCraftConfigs.ProjectionTools.ENABLE_CREATIVE_CONTAINER_FILL,
                QuickCraftConfigs.ProjectionTools.ENABLE_CONTAINER_FILL_OVERFLOW_DROP,
                QuickCraftConfigs.ProjectionTools.CONTAINER_FILL_FREE_SLOTS_LIMIT,
                QuickCraftConfigs.ProjectionTools.CONTAINER_FILL_PROTECTED_ITEMS,
                QuickCraftConfigs.ProjectionTools.CONTAINER_FILL_REPLACEMENTS
        ),
        LITEMATICA_SHULKER_MATERIAL_RESTOCK(
                QuickCraftConfigs.ProjectionTools.ENABLE_LITEMATICA_SHULKER_MATERIAL_RESTOCK,
                QuickCraftConfigs.ProjectionTools.LITEMATICA_SHULKER_MATERIAL_ORDERLY_STORAGE
        );

        private final IConfigBase parent;
        private final List<IConfigBase> children;

        ConfigGroup(IConfigBase parent, IConfigBase... children) {
            this.parent = parent;
            this.children = List.of(children);
        }

        private static ConfigGroup findByParent(IConfigBase config) {
            for (ConfigGroup group : values()) {
                if (group.parent == config) {
                    return group;
                }
            }

            return null;
        }

        private static ConfigGroup findByChild(IConfigBase config) {
            for (ConfigGroup group : values()) {
                if (group.children.contains(config)) {
                    return group;
                }
            }

            return null;
        }
    }

    private static final class GroupedConfigOptionWrapper extends ConfigOptionWrapper {
        private final ConfigGroup group;
        private final boolean child;

        private GroupedConfigOptionWrapper(IConfigBase config, ConfigGroup group, boolean child) {
            super(config);
            this.group = group;
            this.child = child;
        }
    }

    private static final class ExpandableConfigList extends WidgetListConfigOptions {
        private ExpandableConfigList(
                int x,
                int y,
                int width,
                int height,
                int configWidth,
                boolean useKeybindSearch,
                QuickCraftConfigScreen parent
        ) {
            super(x, y, width, height, configWidth, 0.0F, useKeybindSearch, parent);
        }

        @Override
        protected void addNonFilteredContents(Collection<ConfigOptionWrapper> entries) {
            for (ConfigOptionWrapper entry : entries) {
                if (entry instanceof GroupedConfigOptionWrapper grouped
                        && grouped.child
                        && !EXPANDED_GROUPS.contains(grouped.group)) {
                    continue;
                }

                this.listContents.add(entry);
            }
        }

        @Override
        public int getMaxNameLengthWrapped(List<ConfigOptionWrapper> wrappers) {
            int width = 0;

            for (ConfigOptionWrapper wrapper : wrappers) {
                IConfigBase config = wrapper.getConfig();

                if (config != null) {
                    int indent = 0;
                    if (wrapper instanceof GroupedConfigOptionWrapper grouped) {
                        indent = grouped.child ? CHILD_INDENT : EXPAND_BUTTON_WIDTH;
                    }
                    width = Math.max(
                            width,
                            this.getStringWidth(config.getConfigGuiDisplayName()) + indent + NAME_COLUMN_PADDING
                    );
                }
            }

            return width;
        }

        @Override
        protected WidgetConfigOption createListEntryWidget(
                int x,
                int y,
                int listIndex,
                boolean isOdd,
                ConfigOptionWrapper wrapper
        ) {
            return new ExpandableConfigOption(
                    x,
                    y,
                    this.browserEntryWidth,
                    this.browserEntryHeight,
                    this.maxLabelWidth,
                    this.configWidth,
                    wrapper,
                    listIndex,
                    this.parent,
                    this
            );
        }
    }

    private static final class ExpandableConfigOption extends WidgetConfigOption {
        private ExpandableConfigOption(
                int x,
                int y,
                int width,
                int height,
                int labelWidth,
                int configWidth,
                ConfigOptionWrapper wrapper,
                int listIndex,
                GuiConfigsBase host,
                ExpandableConfigList parent
        ) {
            super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);

            if (wrapper instanceof GroupedConfigOptionWrapper grouped) {
                this.indentLabelArea(grouped.child ? CHILD_INDENT : EXPAND_BUTTON_WIDTH);

                if (!grouped.child) {
                    ButtonGeneric expandButton = new ButtonGeneric(
                            x,
                            y,
                            EXPAND_BUTTON_WIDTH,
                            20,
                            EXPANDED_GROUPS.contains(grouped.group) ? "[-]" : "[+]"
                    ).setRenderDefaultBackground(false);
                    this.addButton(expandButton, (button, mouseButton) -> {
                        if (!EXPANDED_GROUPS.remove(grouped.group)) {
                            EXPANDED_GROUPS.add(grouped.group);
                        }
                        parent.refreshEntries();
                    });
                }
            }
        }

        private void indentLabelArea(int indent) {
            for (WidgetBase widget : this.subWidgets) {
                if (widget instanceof WidgetLabel || widget instanceof WidgetHoverInfo) {
                    widget.setPosition(widget.getX() + indent, widget.getY());
                    widget.setWidth(Math.max(0, widget.getWidth() - indent));
                }
            }
        }
    }

    private static class TabButtonListener implements IButtonActionListener {
        private final Tab tab;
        private final QuickCraftConfigScreen screen;

        private TabButtonListener(Tab tab, QuickCraftConfigScreen screen) {
            this.tab = tab;
            this.screen = screen;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            currentTab = tab;
            screen.reCreateListWidget();
            var listWidget = screen.getListWidget();
            if (listWidget != null) {
                listWidget.resetScrollbarPosition();
            }
            screen.initGui();
        }
    }

    private static String tr(String key, String fallback) {
        return StringUtils.getTranslatedOrFallback(key, fallback);
    }
}
