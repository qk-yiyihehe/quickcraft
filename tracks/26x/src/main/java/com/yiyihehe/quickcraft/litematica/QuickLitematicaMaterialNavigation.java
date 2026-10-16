package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListPlacementAccessor;
import com.yiyihehe.quickcraft.mixin.LitematicaMaterialListSchematicAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.materials.MaterialListSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 一次浏览持有自己的页面与来源；切换不重建材料表，也不累积 parent 返回链。 */
public final class QuickLitematicaMaterialNavigation {
    enum View { BLOCKS, CONTAINERS, DETAILS, ENTITIES }

    public interface NavigationScreen {
        void quickcraft$setBottomMargin(int margin);
        QuickLitematicaMaterialNavigation quickcraft$getNavigation();
        void quickcraft$setNavigation(QuickLitematicaMaterialNavigation navigation);
    }

    final LitematicaSchematic schematic;
    final List<String> regions;
    final SchematicPlacement placement;
    final Screen parent;
    final boolean world;
    private MaterialListBase blocks;
    private QuickLitematicaContainerMaterials.ContainerMaterialList containers;
    private QuickLitematicaEntityMaterials.EntityMaterialList entities;
    private final Map<View, Screen> screens = new EnumMap<>(View.class);

    private QuickLitematicaMaterialNavigation(LitematicaSchematic schematic, Collection<String> regions,
                                               SchematicPlacement placement, Screen parent, boolean world) {
        this.schematic = schematic;
        this.regions = List.copyOf(regions);
        this.placement = placement;
        this.parent = parent;
        this.world = world;
    }

    static QuickLitematicaMaterialNavigation forContainers(QuickLitematicaContainerMaterials.ContainerMaterialList list) {
        if (list.navigation == null) {
            var data = list.data;
            var navigation = new QuickLitematicaMaterialNavigation(data.liveSchematic, data.regions, data.placement,
                    list.parent, data.placement != null);
            navigation.containers = list;
            list.navigation = navigation;
        }
        return list.navigation;
    }

    static QuickLitematicaMaterialNavigation bind(GuiMaterialList gui) {
        var screen = (NavigationScreen) gui;
        if (screen.quickcraft$getNavigation() != null) { return screen.quickcraft$getNavigation(); }
        var list = gui.getMaterialList();
        QuickLitematicaMaterialNavigation navigation;
        View view;
        if (list instanceof QuickLitematicaContainerMaterials.ContainerMaterialList container) {
            navigation = forContainers(container);
            view = View.CONTAINERS;
        } else if (list instanceof QuickLitematicaEntityMaterials.EntityMaterialList entity) {
            navigation = entity.navigation;
            view = View.ENTITIES;
        } else if (list instanceof LitematicaMaterialListPlacementAccessor source) {
            var placement = source.quickcraft$getPlacement();
            navigation = new QuickLitematicaMaterialNavigation(placement.getSchematic(),
                    placement.getEnabledRelativeSubRegionPlacements().keySet(), placement, gui.getParent(), true);
            navigation.blocks = list;
            view = View.BLOCKS;
        } else if (list instanceof LitematicaMaterialListSchematicAccessor source) {
            navigation = new QuickLitematicaMaterialNavigation(source.quickcraft$getSchematic(), source.quickcraft$getRegions(),
                    null, gui.getParent(), false);
            navigation.blocks = list;
            view = View.BLOCKS;
        } else { return null; }
        screen.quickcraft$setNavigation(navigation);
        navigation.screens.put(view, gui);
        return navigation;
    }

    String label(View view) {
        String key = view == View.BLOCKS ? "file_blocks" : view.name().toLowerCase(java.util.Locale.ROOT);
        return StringUtils.translate("quickcraft.litematica.nav." + key);
    }

    List<View> views(View current) {
        var views = new ArrayList<View>();
        views.add(View.BLOCKS);
        if (QuickLitematicaContainerMaterials.shouldShowButton() || current == View.CONTAINERS || current == View.DETAILS) {
            views.add(View.CONTAINERS);
            views.add(View.DETAILS);
        }
        if (!this.world && (QuickCraftConfigs.ProjectionTools.SHOW_LITEMATICA_ENTITY_MATERIAL_BUTTON.getBooleanValue()
                || current == View.ENTITIES)) { views.add(View.ENTITIES); }
        return views;
    }

    void show(View view) {
        if (view == View.ENTITIES && this.world) { return; }
        MaterialListBase list;
        if (view == View.BLOCKS) {
            if (this.blocks == null) {
                this.blocks = this.world ? new MaterialListPlacement(this.placement, true)
                        : new MaterialListSchematic(this.schematic, this.regions, true);
            }
            list = this.blocks;
        } else if (view == View.ENTITIES) {
            if (this.entities == null) {
                this.entities = new QuickLitematicaEntityMaterials.EntityMaterialList(this.schematic, this.regions);
                this.entities.navigation = this;
            }
            list = this.entities;
        } else {
            if (this.containers == null) {
                var context = QuickLitematicaMaterialLists.getContainerReplacementContextKey(this.schematic);
                var data = this.world ? QuickLitematicaContainerMaterials.ContainerMaterialsData.create(this.placement, context)
                        : QuickLitematicaContainerMaterials.ContainerMaterialsData.create(this.schematic, this.regions, context);
                this.containers = new QuickLitematicaContainerMaterials.ContainerMaterialList(data, this.parent);
                this.containers.navigation = this;
            }
            list = this.containers;
        }
        Screen screen = this.screens.get(view);
        if (screen != null && (view == View.DETAILS || view == View.ENTITIES)) {
            // 详情与实体页不再提供手动刷新，重进缓存页面时重读计数。
            list.reCreateMaterialList();
        }
        if (screen == null) {
            if (view == View.DETAILS) { screen = QuickLitematicaContainerMaterialsScreens.createDetailScreen(this.containers); }
            else {
                var gui = new GuiMaterialList(list);
                ((NavigationScreen) gui).quickcraft$setNavigation(this);
                gui.setParent(this.parent);
                screen = gui;
            }
            this.screens.put(view, screen);
        }
        DataManager.setMaterialList(list);
        GuiBase.openGui(screen);
    }
}
