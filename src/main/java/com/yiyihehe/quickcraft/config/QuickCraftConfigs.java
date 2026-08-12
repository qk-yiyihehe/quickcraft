package com.yiyihehe.quickcraft.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yiyihehe.quickcraft.QuickCraft;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;
import fi.dy.masa.malilib.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * QuickCraft 的 malilib 配置定义与持久化。
 * 这里仅承载配置数据，不放业务逻辑。
 */
// malilib/Guava 的泛型在 JDT 空安全检查下会产生大量误报，这里统一压制这类警告。
public final class QuickCraftConfigs implements IConfigHandler {
    private static final String CONFIG_FILE_NAME = QuickCraft.MOD_ID + ".json";
    private static final String HOTKEY_CATEGORY_KEY = "screen.quickcraft.tab.hotkeys";
    private static final String CRAFTING_TRANSLATION_PREFIX = QuickCraft.MOD_ID + ".config.crafting";
    private static final String CONTAINER_TRANSLATION_PREFIX = QuickCraft.MOD_ID + ".config.container_tools";
    private static final String PROJECTION_TRANSLATION_PREFIX = QuickCraft.MOD_ID + ".config.projection_tools";
    private static final String MOD_SUPPORT_TRANSLATION_PREFIX = QuickCraft.MOD_ID + ".config.mod_support";
    private static final String HOTKEY_TRANSLATION_PREFIX = QuickCraft.MOD_ID + ".config.hotkeys";
    private static final String BUTTON_POSITIONS_KEY = "ButtonPositions";
    private static final Map<String, ButtonOffset> BUTTON_OFFSETS = new HashMap<>();

    public static final int DEFAULT_CRAFT_LOOPS_PER_TICK = 20;
    public static final int MIN_CRAFT_LOOPS_PER_TICK = 1;
    public static final int MAX_CRAFT_LOOPS_PER_TICK = 60;
    public static final int DEFAULT_CONTAINER_FILL_FREE_SLOTS_LIMIT = 5;
    public static final int MIN_CONTAINER_FILL_FREE_SLOTS_LIMIT = 0;
    public static final int MAX_CONTAINER_FILL_FREE_SLOTS_LIMIT = 36;
    public static final int DEFAULT_MATERIAL_COLLECT_EXTRA_0_TO_10 = 0;
    public static final int DEFAULT_MATERIAL_COLLECT_EXTRA_10_TO_20 = 1;
    public static final int DEFAULT_MATERIAL_COLLECT_EXTRA_20_TO_50 = 3;
    public static final int DEFAULT_MATERIAL_COLLECT_EXTRA_50_TO_100 = 5;
    public static final int DEFAULT_MATERIAL_COLLECT_EXTRA_100_TO_500 = 10;
    public static final int DEFAULT_MATERIAL_COLLECT_EXTRA_OVER_500 = 32;
    public static final int MIN_MATERIAL_COLLECT_EXTRA = 0;
    public static final int MAX_MATERIAL_COLLECT_EXTRA_0_TO_10 = 10;
    public static final int MAX_MATERIAL_COLLECT_EXTRA_10_TO_20 = 20;
    public static final int MAX_MATERIAL_COLLECT_EXTRA_20_TO_50 = 50;
    public static final int MAX_MATERIAL_COLLECT_EXTRA_50_TO_100 = 100;
    public static final int MAX_MATERIAL_COLLECT_EXTRA_100_TO_500 = 500;
    public static final int MAX_MATERIAL_COLLECT_EXTRA_OVER_500 = 512;
    public static final int DEFAULT_HOLD_EASY_PLACE_CACHE_TIME_MS = 2000;
    public static final int MIN_HOLD_EASY_PLACE_CACHE_TIME_MS = 10;
    public static final int MAX_HOLD_EASY_PLACE_CACHE_TIME_MS = 10000;
    public static final int DEFAULT_QUICK_SHULKER_ACTION_INTERVAL_TICKS = 5;
    public static final int MIN_QUICK_SHULKER_ACTION_INTERVAL_TICKS = 0;
    public static final int MAX_QUICK_SHULKER_ACTION_INTERVAL_TICKS = 20;

    private static final KeybindSettings GUI_PRESS = KeybindSettings.create(
            KeybindSettings.Context.GUI,
            KeyAction.PRESS,
            true,
            false,
            false,
            true
    );
    private static final KeybindSettings GUI_BOTH = KeybindSettings.create(
            KeybindSettings.Context.GUI,
            KeyAction.BOTH,
            true,
            false,
            false,
            true
    );
    private static final KeybindSettings ANY_PRESS = KeybindSettings.create(
            KeybindSettings.Context.ANY,
            KeyAction.PRESS,
            true,
            false,
            false,
            true
    );
    private static final KeybindSettings INGAME_PRESS = KeybindSettings.create(
            KeybindSettings.Context.INGAME,
            KeyAction.PRESS,
            true,
            false,
            false,
            true
    );

    public QuickCraftConfigs() {
    }

    public enum ContainerToolMode implements IConfigOptionListEntry {
        QUICK_STASH("quick_stash", "quickcraft.label.container_tool_mode.quick_stash"),
        QUICK_COPY("quick_copy", "quickcraft.label.container_tool_mode.quick_copy"),
        QUICK_CLEAR("quick_clear", "quickcraft.label.container_tool_mode.quick_clear");

        private final String configValue;
        private final String translationKey;

        ContainerToolMode(String configValue, String translationKey) {
            this.configValue = configValue;
            this.translationKey = translationKey;
        }

        @Override
        public String getStringValue() {
            return this.configValue;
        }

        @Override
        public String getDisplayName() {
            return StringUtils.translate(this.translationKey);
        }

        @Override
        public IConfigOptionListEntry cycle(boolean forward) {
            int next = this.ordinal() + (forward ? 1 : -1);
            if (next < 0) {
                next = values().length - 1;
            } else if (next >= values().length) {
                next = 0;
            }
            return values()[next];
        }

        @Override
        public IConfigOptionListEntry fromString(String value) {
            return fromStringStatic(value);
        }

        public static ContainerToolMode fromStringStatic(String value) {
            for (ContainerToolMode mode : values()) {
                if (mode.configValue.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return QUICK_STASH;
        }
    }

    public static final class Crafting {
        public static final ConfigBooleanHotkeyed ENABLE_WORKBENCH = new ConfigBooleanHotkeyed(
                "enableWorkbenchQuickCraft",
                true,
                ""
        ).apply(CRAFTING_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_BACKPACK = new ConfigBooleanHotkeyed(
                "enableBackpackQuickCraft",
                true,
                ""
        ).apply(CRAFTING_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_STONECUTTER = new ConfigBooleanHotkeyed(
                "enableStonecutterQuickCraft",
                true,
                ""
        ).apply(CRAFTING_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_ANVIL_RENAME = new ConfigBooleanHotkeyed(
                "enableAnvilRenameQuickCraft",
                true,
                ""
        ).apply(CRAFTING_TRANSLATION_PREFIX);
        public static final ConfigInteger CRAFT_LOOPS_PER_TICK = new ConfigInteger(
                "craftLoopsPerTick",
                DEFAULT_CRAFT_LOOPS_PER_TICK,
                MIN_CRAFT_LOOPS_PER_TICK,
                MAX_CRAFT_LOOPS_PER_TICK,
                true
        ).apply(CRAFTING_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed SHOW_CRAFT_ACTION_BUTTON = new ConfigBooleanHotkeyed(
                "showCraftActionButton",
                true,
                ""
        ).apply(CRAFTING_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed DROP_RESULTS_ON_STOP = new ConfigBooleanHotkeyed(
                "dropCraftResultsOnStop",
                false,
                ""
        ).apply(CRAFTING_TRANSLATION_PREFIX);

        public static final List<IConfigBase> OPTIONS = List.of(
                ENABLE_WORKBENCH,
                ENABLE_BACKPACK,
                ENABLE_STONECUTTER,
                ENABLE_ANVIL_RENAME,
                SHOW_CRAFT_ACTION_BUTTON,
                DROP_RESULTS_ON_STOP,
                CRAFT_LOOPS_PER_TICK
        );

        private Crafting() {
        }
    }

    public static final class ContainerTools {
        public static final ConfigBooleanHotkeyed ENABLE_QUICK_TRANSFER = new ConfigBooleanHotkeyed(
                "enableQuickTransfer",
                true,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_SCROLL_TRANSFER = new ConfigBooleanHotkeyed(
                "enableScrollTransfer",
                true,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed QUICK_TRANSFER_RETAIN_ONE = new ConfigBooleanHotkeyed(
                "quickTransferRetainOne",
                true,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed SHOW_QUICK_STASH_BUTTON = new ConfigBooleanHotkeyed(
                "showQuickStashButton",
                true,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_QUICK_THROW = new ConfigBooleanHotkeyed(
                "enableQuickThrow",
                true,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_QUICK_TRADE = new ConfigBooleanHotkeyed(
                "enableQuickTrade",
                false,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_FAVORITE_TRADE = new ConfigBooleanHotkeyed(
                "enableFavoriteTrade",
                true,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_QUICK_SORT = new ConfigBooleanHotkeyed(
                "enableQuickSort",
                true,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed SHOW_CONTAINER_LOCK_BUTTON = new ConfigBooleanHotkeyed(
                "showContainerLockButton",
                true,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed SHOW_SLOT_LOCK_OVERLAY = new ConfigBooleanHotkeyed(
                "showSlotLockOverlay",
                false,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ALLOW_MANUAL_LOCKED_SLOT_INTERACTION = new ConfigBooleanHotkeyed(
                "allowManualLockedSlotInteraction",
                true,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_CONTAINER_TOOL_MODE = new ConfigBooleanHotkeyed(
                "enableContainerToolMode",
                false,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigOptionList CONTAINER_TOOL_MODE = new ConfigOptionList(
                "containerToolMode",
                ContainerToolMode.QUICK_STASH,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_CREATIVE_PACKING = new ConfigBooleanHotkeyed(
                "enableCreativePacking",
                false,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigInteger CREATIVE_PACKING_BUNDLE_STACKS = new ConfigInteger(
                "creativePackingBundleStacks",
                1,
                1,
                64,
                true
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_CREATIVE_PACKING_NESTED_CONTAINERS = new ConfigBoolean(
                "allowCreativePackingNestedContainers",
                false
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_QUICK_BEACON = new ConfigBooleanHotkeyed(
                "enableQuickBeacon",
                false,
                ""
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final ConfigStringList BEACON_EFFECT_ORDER = new ConfigStringList(
                "beaconEffectOrder",
                ImmutableList.of(
                        "急迫2",
                        "力量2",
                        "生命恢复1",
                        "跳跃提升2",
                        "迅捷2",
                        "抗性提升2"
                )
        ).apply(CONTAINER_TRANSLATION_PREFIX);
        public static final List<IConfigBase> OPTIONS = List.of(
                ENABLE_QUICK_TRANSFER,
                ENABLE_SCROLL_TRANSFER,
                QUICK_TRANSFER_RETAIN_ONE,
                SHOW_QUICK_STASH_BUTTON,
                ENABLE_QUICK_THROW,
                ENABLE_QUICK_TRADE,
                ENABLE_FAVORITE_TRADE,
                ENABLE_QUICK_SORT,
                SHOW_CONTAINER_LOCK_BUTTON,
                SHOW_SLOT_LOCK_OVERLAY,
                ALLOW_MANUAL_LOCKED_SLOT_INTERACTION,
                ENABLE_CONTAINER_TOOL_MODE,
                CONTAINER_TOOL_MODE,
                ENABLE_CREATIVE_PACKING,
                CREATIVE_PACKING_BUNDLE_STACKS,
                ALLOW_CREATIVE_PACKING_NESTED_CONTAINERS,
                ENABLE_QUICK_BEACON,
                BEACON_EFFECT_ORDER
        );

        private ContainerTools() {
        }
    }

    public static final class ProjectionTools {
        public static final ConfigBooleanHotkeyed SHOW_LITEMATICA_3D_PREVIEW = new ConfigBooleanHotkeyed(
                "showLitematica3DPreview",
                true,
                ""
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_ADDING_LITEMATICA_PREVIEW_IMAGES = new ConfigBoolean(
                "allowAddingLitematicaPreviewImages",
                true
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean REPLACE_LITEMATICA_PREVIEW_WITH_3D = new ConfigBoolean(
                "replaceLitematicaPreviewWith3D",
                true
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ALLOW_EASY_PLACE_VANILLA_INTERACTIONS = new ConfigBooleanHotkeyed(
                "allowEasyPlaceOpenContainers",
                false,
                ""
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_EASY_PLACE_INTERACTION_SCREENS = new ConfigBoolean(
                "allowEasyPlaceInteractionScreens",
                true
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_EASY_PLACE_REDSTONE_INTERACTIONS = new ConfigBoolean(
                "allowEasyPlaceRedstoneInteractions",
                true
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_EASY_PLACE_FUNCTIONAL_BLOCK_INTERACTIONS = new ConfigBoolean(
                "allowEasyPlaceFunctionalBlockInteractions",
                true
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_EASY_PLACE_FLUID_INTERACTIONS = new ConfigBoolean(
                "allowEasyPlaceFluidInteractions",
                true
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_EASY_PLACE_TOOL_INTERACTIONS = new ConfigBoolean(
                "allowEasyPlaceToolInteractions",
                true
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_EASY_PLACE_DECORATION_INTERACTIONS = new ConfigBoolean(
                "allowEasyPlaceDecorationInteractions",
                true
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_EASY_PLACE_SURVIVAL_INTERACTIONS = new ConfigBoolean(
                "allowEasyPlaceSurvivalInteractions",
                true
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_EASY_PLACE_SPECIAL_INTERACTIONS = new ConfigBoolean(
                "allowEasyPlaceSpecialInteractions",
                true
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_EASY_PLACE_DANGEROUS_INTERACTIONS = new ConfigBoolean(
                "allowEasyPlaceDangerousInteractions",
                false
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBoolean ALLOW_EASY_PLACE_ADMIN_INTERACTIONS = new ConfigBoolean(
                "allowEasyPlaceAdminInteractions",
                false
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed HOLD_EASY_PLACE = new ConfigBooleanHotkeyed(
                "holdEasyPlace",
                false,
                ""
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigInteger HOLD_EASY_PLACE_CACHE_TIME_MS = new ConfigInteger(
                "holdEasyPlaceCacheTimeMs",
                DEFAULT_HOLD_EASY_PLACE_CACHE_TIME_MS,
                MIN_HOLD_EASY_PLACE_CACHE_TIME_MS,
                MAX_HOLD_EASY_PLACE_CACHE_TIME_MS,
                false
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed SHOW_LITEMATICA_SCHEMATIC_FOLDER_BUTTON = new ConfigBooleanHotkeyed(
                "showLitematicaSchematicFolderButton",
                true,
                ""
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed SHOW_LITEMATICA_CONTAINER_MATERIAL_BUTTON = new ConfigBooleanHotkeyed(
                "showLitematicaContainerMaterialButton",
                true,
                ""
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed SHOW_LITEMATICA_CONTAINER_SLOT_HINTS = new ConfigBooleanHotkeyed(
                "showLitematicaContainerSlotHints",
                true,
                ""
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed SHOW_LITEMATICA_CONTAINER_VERIFIER = new ConfigBooleanHotkeyed(
                "showLitematicaContainerVerifier",
                true,
                ""
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_AUTO_COLLECT_MATERIALS = new ConfigBooleanHotkeyed(
                "enableAutoCollectMaterials",
                false,
                ""
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigInteger MATERIAL_COLLECT_EXTRA_0_TO_10 = new ConfigInteger(
                "materialCollectExtra0To10",
                DEFAULT_MATERIAL_COLLECT_EXTRA_0_TO_10,
                MIN_MATERIAL_COLLECT_EXTRA,
                MAX_MATERIAL_COLLECT_EXTRA_0_TO_10,
                false
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigInteger MATERIAL_COLLECT_EXTRA_10_TO_20 = new ConfigInteger(
                "materialCollectExtra10To20",
                DEFAULT_MATERIAL_COLLECT_EXTRA_10_TO_20,
                MIN_MATERIAL_COLLECT_EXTRA,
                MAX_MATERIAL_COLLECT_EXTRA_10_TO_20,
                false
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigInteger MATERIAL_COLLECT_EXTRA_20_TO_50 = new ConfigInteger(
                "materialCollectExtra20To50",
                DEFAULT_MATERIAL_COLLECT_EXTRA_20_TO_50,
                MIN_MATERIAL_COLLECT_EXTRA,
                MAX_MATERIAL_COLLECT_EXTRA_20_TO_50,
                false
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigInteger MATERIAL_COLLECT_EXTRA_50_TO_100 = new ConfigInteger(
                "materialCollectExtra50To100",
                DEFAULT_MATERIAL_COLLECT_EXTRA_50_TO_100,
                MIN_MATERIAL_COLLECT_EXTRA,
                MAX_MATERIAL_COLLECT_EXTRA_50_TO_100,
                false
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigInteger MATERIAL_COLLECT_EXTRA_100_TO_500 = new ConfigInteger(
                "materialCollectExtra100To500",
                DEFAULT_MATERIAL_COLLECT_EXTRA_100_TO_500,
                MIN_MATERIAL_COLLECT_EXTRA,
                MAX_MATERIAL_COLLECT_EXTRA_100_TO_500,
                false
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigInteger MATERIAL_COLLECT_EXTRA_OVER_500 = new ConfigInteger(
                "materialCollectExtraOver500",
                DEFAULT_MATERIAL_COLLECT_EXTRA_OVER_500,
                MIN_MATERIAL_COLLECT_EXTRA,
                MAX_MATERIAL_COLLECT_EXTRA_OVER_500,
                false
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_LITEMATICA_CONTAINER_AUTOFILL = new ConfigBooleanHotkeyed(
                "enableLitematicaContainerAutofill",
                false,
                ""
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_CREATIVE_CONTAINER_FILL = new ConfigBooleanHotkeyed(
                "enableCreativeContainerFill",
                true,
                ""
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigBooleanHotkeyed ENABLE_CONTAINER_FILL_OVERFLOW_DROP = new ConfigBooleanHotkeyed(
                "enableContainerFillOverflowDrop",
                true,
                ""
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigInteger CONTAINER_FILL_FREE_SLOTS_LIMIT = new ConfigInteger(
                "containerFillFreeSlotsLimit",
                DEFAULT_CONTAINER_FILL_FREE_SLOTS_LIMIT,
                MIN_CONTAINER_FILL_FREE_SLOTS_LIMIT,
                MAX_CONTAINER_FILL_FREE_SLOTS_LIMIT,
                true
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigStringList CONTAINER_FILL_PROTECTED_ITEMS = new ConfigStringList(
                "containerFillProtectedItems",
                ImmutableList.of(
                        "金胡萝卜",
                        "烟花火箭"
                )
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final ConfigStringList CONTAINER_FILL_REPLACEMENTS = new ConfigStringList(
                "containerFillReplacements",
                ImmutableList.of()
        ).apply(PROJECTION_TRANSLATION_PREFIX);
        public static final List<IConfigBase> OPTIONS = List.of(
                SHOW_LITEMATICA_3D_PREVIEW,
                ALLOW_ADDING_LITEMATICA_PREVIEW_IMAGES,
                REPLACE_LITEMATICA_PREVIEW_WITH_3D,
                ALLOW_EASY_PLACE_VANILLA_INTERACTIONS,
                ALLOW_EASY_PLACE_INTERACTION_SCREENS,
                ALLOW_EASY_PLACE_REDSTONE_INTERACTIONS,
                ALLOW_EASY_PLACE_FUNCTIONAL_BLOCK_INTERACTIONS,
                ALLOW_EASY_PLACE_FLUID_INTERACTIONS,
                ALLOW_EASY_PLACE_TOOL_INTERACTIONS,
                ALLOW_EASY_PLACE_DECORATION_INTERACTIONS,
                ALLOW_EASY_PLACE_SURVIVAL_INTERACTIONS,
                ALLOW_EASY_PLACE_SPECIAL_INTERACTIONS,
                ALLOW_EASY_PLACE_DANGEROUS_INTERACTIONS,
                ALLOW_EASY_PLACE_ADMIN_INTERACTIONS,
                HOLD_EASY_PLACE,
                HOLD_EASY_PLACE_CACHE_TIME_MS,
                SHOW_LITEMATICA_SCHEMATIC_FOLDER_BUTTON,
                SHOW_LITEMATICA_CONTAINER_MATERIAL_BUTTON,
                SHOW_LITEMATICA_CONTAINER_SLOT_HINTS,
                SHOW_LITEMATICA_CONTAINER_VERIFIER,
                ENABLE_AUTO_COLLECT_MATERIALS,
                MATERIAL_COLLECT_EXTRA_0_TO_10,
                MATERIAL_COLLECT_EXTRA_10_TO_20,
                MATERIAL_COLLECT_EXTRA_20_TO_50,
                MATERIAL_COLLECT_EXTRA_50_TO_100,
                MATERIAL_COLLECT_EXTRA_100_TO_500,
                MATERIAL_COLLECT_EXTRA_OVER_500,
                ENABLE_LITEMATICA_CONTAINER_AUTOFILL,
                ENABLE_CREATIVE_CONTAINER_FILL,
                ENABLE_CONTAINER_FILL_OVERFLOW_DROP,
                CONTAINER_FILL_FREE_SLOTS_LIMIT,
                CONTAINER_FILL_PROTECTED_ITEMS,
                CONTAINER_FILL_REPLACEMENTS
        );

        private ProjectionTools() {
        }
    }

    public static final class ModSupport {
        public static final ConfigBooleanHotkeyed ENABLE_QUICK_SHULKER = new ConfigBooleanHotkeyed(
                "enableQuickShulker",
                true,
                ""
        ).apply(MOD_SUPPORT_TRANSLATION_PREFIX);
        public static final ConfigInteger QUICK_SHULKER_ACTION_INTERVAL_TICKS = new ConfigInteger(
                "quickShulkerActionIntervalTicks",
                DEFAULT_QUICK_SHULKER_ACTION_INTERVAL_TICKS,
                MIN_QUICK_SHULKER_ACTION_INTERVAL_TICKS,
                MAX_QUICK_SHULKER_ACTION_INTERVAL_TICKS,
                true
        ).apply(MOD_SUPPORT_TRANSLATION_PREFIX);
        public static final List<IConfigBase> OPTIONS = List.of(
                ENABLE_QUICK_SHULKER,
                QUICK_SHULKER_ACTION_INTERVAL_TICKS
        );

        private ModSupport() {
        }
    }

    public static final class Hotkeys {
        public static final ConfigBoolean ENABLE_OPEN_CONFIG_HOTKEY = new ConfigBoolean(
                "enableOpenConfigHotkey",
                true
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigBoolean ENABLE_ACTION_BUTTON_DRAGGING = new ConfigBoolean(
                "enableActionButtonDragging",
                false
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey OPEN_CONFIG = new ConfigHotkey(
                "openConfigHotkey",
                "O",
                INGAME_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey OPEN_LITEMATICA_AREA_3D_PREVIEW = new ConfigHotkey(
                "openLitematicaArea3DPreviewHotkey",
                "LEFT_CONTROL,P",
                INGAME_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey SINGLE_CRAFT = new ConfigHotkey(
                "singleCraftHotkey",
                "V",
                GUI_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey RAPID_CRAFT = new ConfigHotkey(
                "rapidCraftHotkey",
                "LEFT_ALT,C",
                GUI_BOTH
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey QUICK_SORT = new ConfigHotkey(
                "quickSortHotkey",
                "R",
                ANY_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey DROP_MATCHING = new ConfigHotkey(
                "dropMatchingHotkey",
                "LEFT_CONTROL,Q",
                GUI_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey DROP_WHOLE_STACK = new ConfigHotkey(
                "dropWholeStackHotkey",
                "LEFT_SHIFT,Q",
                GUI_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey QUICK_TRANSFER = new ConfigHotkey(
                "quickTransferHotkey",
                "LEFT_ALT,BUTTON_1",
                GUI_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey QUICK_TRANSFER_RETAIN_ONE = new ConfigHotkey(
                "quickTransferRetainOneHotkey",
                "LEFT_SHIFT,BUTTON_2",
                GUI_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey SLOT_QUICK_TRANSFER = new ConfigHotkey(
                "slotQuickTransferHotkey",
                "LEFT_SHIFT,BUTTON_1",
                GUI_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey SLOT_LOCK = new ConfigHotkey(
                "slotLockHotkey",
                "LEFT_ALT,BUTTON_2",
                GUI_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey COPY_CONTAINER_TEMPLATE = new ConfigHotkey(
                "copyContainerTemplateHotkey",
                "BUTTON_3",
                INGAME_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey CONTINUOUS_CONTAINER_FILL = new ConfigHotkey(
                "continuousContainerFillHotkey",
                "BUTTON_2",
                ANY_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey TOGGLE_CONTAINER_TOOL_MODE = new ConfigHotkey(
                "toggleContainerToolModeHotkey",
                "LEFT_CONTROL,F",
                ANY_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final ConfigHotkey CREATIVE_PACKING = new ConfigHotkey(
                "creativePackingHotkey",
                "LEFT_CONTROL,G",
                INGAME_PRESS
        ).apply(HOTKEY_TRANSLATION_PREFIX);
        public static final List<IConfigBase> OPTIONS = List.of(
                ENABLE_OPEN_CONFIG_HOTKEY,
                ENABLE_ACTION_BUTTON_DRAGGING,
                OPEN_CONFIG,
                OPEN_LITEMATICA_AREA_3D_PREVIEW,
                SINGLE_CRAFT,
                RAPID_CRAFT,
                QUICK_SORT,
                DROP_MATCHING,
                DROP_WHOLE_STACK,
                QUICK_TRANSFER,
                QUICK_TRANSFER_RETAIN_ONE,
                SLOT_QUICK_TRANSFER,
                SLOT_LOCK,
                COPY_CONTAINER_TEMPLATE,
                CONTINUOUS_CONTAINER_FILL,
                TOGGLE_CONTAINER_TOOL_MODE,
                CREATIVE_PACKING
        );

        public static final List<IHotkey> HOTKEYS = List.of(
                OPEN_CONFIG,
                OPEN_LITEMATICA_AREA_3D_PREVIEW,
                SINGLE_CRAFT,
                RAPID_CRAFT,
                QUICK_SORT,
                DROP_MATCHING,
                DROP_WHOLE_STACK,
                QUICK_TRANSFER,
                QUICK_TRANSFER_RETAIN_ONE,
                SLOT_QUICK_TRANSFER,
                SLOT_LOCK,
                COPY_CONTAINER_TEMPLATE,
                CONTINUOUS_CONTAINER_FILL,
                TOGGLE_CONTAINER_TOOL_MODE,
                CREATIVE_PACKING
        );

        private Hotkeys() {
        }
    }

    public static List<IHotkey> getAllHotkeys() {
        return List.of(
                Crafting.ENABLE_WORKBENCH,
                Crafting.ENABLE_BACKPACK,
                Crafting.ENABLE_STONECUTTER,
                Crafting.ENABLE_ANVIL_RENAME,
                Crafting.SHOW_CRAFT_ACTION_BUTTON,
                Crafting.DROP_RESULTS_ON_STOP,
                ContainerTools.ENABLE_QUICK_TRANSFER,
                ContainerTools.ENABLE_SCROLL_TRANSFER,
                ContainerTools.QUICK_TRANSFER_RETAIN_ONE,
                ContainerTools.ENABLE_QUICK_THROW,
                ContainerTools.ENABLE_QUICK_TRADE,
                ContainerTools.ENABLE_FAVORITE_TRADE,
                ContainerTools.ENABLE_QUICK_SORT,
                ContainerTools.SHOW_CONTAINER_LOCK_BUTTON,
                ContainerTools.SHOW_SLOT_LOCK_OVERLAY,
                ContainerTools.ALLOW_MANUAL_LOCKED_SLOT_INTERACTION,
                ContainerTools.ENABLE_CONTAINER_TOOL_MODE,
                ContainerTools.ENABLE_CREATIVE_PACKING,
                ContainerTools.ENABLE_QUICK_BEACON,
                ProjectionTools.SHOW_LITEMATICA_3D_PREVIEW,
                ProjectionTools.ALLOW_EASY_PLACE_VANILLA_INTERACTIONS,
                ProjectionTools.HOLD_EASY_PLACE,
                ProjectionTools.SHOW_LITEMATICA_SCHEMATIC_FOLDER_BUTTON,
                ProjectionTools.SHOW_LITEMATICA_CONTAINER_MATERIAL_BUTTON,
                ProjectionTools.SHOW_LITEMATICA_CONTAINER_SLOT_HINTS,
                ProjectionTools.SHOW_LITEMATICA_CONTAINER_VERIFIER,
                ProjectionTools.ENABLE_AUTO_COLLECT_MATERIALS,
                ProjectionTools.ENABLE_LITEMATICA_CONTAINER_AUTOFILL,
                ProjectionTools.ENABLE_CREATIVE_CONTAINER_FILL,
                ProjectionTools.ENABLE_CONTAINER_FILL_OVERFLOW_DROP,
                ModSupport.ENABLE_QUICK_SHULKER,
                Hotkeys.OPEN_CONFIG,
                Hotkeys.OPEN_LITEMATICA_AREA_3D_PREVIEW,
                Hotkeys.SINGLE_CRAFT,
                Hotkeys.RAPID_CRAFT,
                Hotkeys.QUICK_SORT,
                Hotkeys.DROP_MATCHING,
                Hotkeys.DROP_WHOLE_STACK,
                Hotkeys.QUICK_TRANSFER,
                Hotkeys.QUICK_TRANSFER_RETAIN_ONE,
                Hotkeys.SLOT_QUICK_TRANSFER,
                Hotkeys.SLOT_LOCK,
                Hotkeys.COPY_CONTAINER_TEMPLATE,
                Hotkeys.CONTINUOUS_CONTAINER_FILL,
                Hotkeys.TOGGLE_CONTAINER_TOOL_MODE,
                Hotkeys.CREATIVE_PACKING
        );
    }

    public static List<ConfigBooleanHotkeyed> getBooleanHotkeyConfigs() {
        return List.of(
                Crafting.ENABLE_WORKBENCH,
                Crafting.ENABLE_BACKPACK,
                Crafting.ENABLE_STONECUTTER,
                Crafting.ENABLE_ANVIL_RENAME,
                Crafting.SHOW_CRAFT_ACTION_BUTTON,
                Crafting.DROP_RESULTS_ON_STOP,
                ContainerTools.ENABLE_QUICK_TRANSFER,
                ContainerTools.ENABLE_SCROLL_TRANSFER,
                ContainerTools.QUICK_TRANSFER_RETAIN_ONE,
                ContainerTools.ENABLE_QUICK_THROW,
                ContainerTools.ENABLE_QUICK_TRADE,
                ContainerTools.ENABLE_FAVORITE_TRADE,
                ContainerTools.ENABLE_QUICK_SORT,
                ContainerTools.SHOW_CONTAINER_LOCK_BUTTON,
                ContainerTools.SHOW_SLOT_LOCK_OVERLAY,
                ContainerTools.ALLOW_MANUAL_LOCKED_SLOT_INTERACTION,
                ContainerTools.ENABLE_CONTAINER_TOOL_MODE,
                ContainerTools.ENABLE_CREATIVE_PACKING,
                ContainerTools.ENABLE_QUICK_BEACON,
                ProjectionTools.SHOW_LITEMATICA_3D_PREVIEW,
                ProjectionTools.ALLOW_EASY_PLACE_VANILLA_INTERACTIONS,
                ProjectionTools.HOLD_EASY_PLACE,
                ProjectionTools.SHOW_LITEMATICA_SCHEMATIC_FOLDER_BUTTON,
                ProjectionTools.SHOW_LITEMATICA_CONTAINER_MATERIAL_BUTTON,
                ProjectionTools.SHOW_LITEMATICA_CONTAINER_SLOT_HINTS,
                ProjectionTools.SHOW_LITEMATICA_CONTAINER_VERIFIER,
                ProjectionTools.ENABLE_AUTO_COLLECT_MATERIALS,
                ProjectionTools.ENABLE_LITEMATICA_CONTAINER_AUTOFILL,
                ProjectionTools.ENABLE_CREATIVE_CONTAINER_FILL,
                ProjectionTools.ENABLE_CONTAINER_FILL_OVERFLOW_DROP,
                ModSupport.ENABLE_QUICK_SHULKER
        );
    }

    public static String getHotkeyCategory() {
        return StringUtils.getTranslatedOrFallback(HOTKEY_CATEGORY_KEY, "Hotkeys");
    }

    public static int getCraftLoopsPerTick() {
        int loops = Crafting.CRAFT_LOOPS_PER_TICK.getIntegerValue();
        int clamped = Math.max(MIN_CRAFT_LOOPS_PER_TICK, Math.min(MAX_CRAFT_LOOPS_PER_TICK, loops));
        if (clamped != loops) {
            Crafting.CRAFT_LOOPS_PER_TICK.setIntegerValue(clamped);
        }
        return clamped;
    }

    public static boolean isWorkbenchQuickCraftEnabled() {
        return Crafting.ENABLE_WORKBENCH.getBooleanValue();
    }

    public static boolean isBackpackQuickCraftEnabled() {
        return Crafting.ENABLE_BACKPACK.getBooleanValue();
    }

    public static boolean isStonecutterQuickCraftEnabled() {
        return Crafting.ENABLE_STONECUTTER.getBooleanValue();
    }

    public static boolean isAnvilRenameQuickCraftEnabled() {
        return Crafting.ENABLE_ANVIL_RENAME.getBooleanValue();
    }

    public static boolean isCraftActionButtonVisible() {
        return Crafting.SHOW_CRAFT_ACTION_BUTTON.getBooleanValue();
    }

    public static boolean isDropCraftResultsOnStopEnabled() {
        return Crafting.DROP_RESULTS_ON_STOP.getBooleanValue();
    }

    public static boolean isQuickTransferEnabled() {
        return ContainerTools.ENABLE_QUICK_TRANSFER.getBooleanValue();
    }

    public static boolean isScrollTransferEnabled() {
        return ContainerTools.ENABLE_SCROLL_TRANSFER.getBooleanValue();
    }

    public static boolean isQuickThrowEnabled() {
        return ContainerTools.ENABLE_QUICK_THROW.getBooleanValue();
    }

    public static boolean isQuickTradeEnabled() {
        return ContainerTools.ENABLE_QUICK_TRADE.getBooleanValue();
    }

    public static boolean isFavoriteTradeEnabled() {
        return ContainerTools.ENABLE_FAVORITE_TRADE.getBooleanValue();
    }

    public static boolean isQuickSortEnabled() {
        return ContainerTools.ENABLE_QUICK_SORT.getBooleanValue();
    }

    public static boolean isContainerToolModeEnabled() {
        return ContainerTools.ENABLE_CONTAINER_TOOL_MODE.getBooleanValue();
    }

    public static boolean isQuickStashEnabled() {
        return isContainerToolModeEnabled()
                && ContainerTools.CONTAINER_TOOL_MODE.getOptionListValue() == ContainerToolMode.QUICK_STASH;
    }

    public static boolean isQuickStashButtonVisible() {
        return ContainerTools.SHOW_QUICK_STASH_BUTTON.getBooleanValue();
    }

    public static boolean isAutoCollectMaterialsEnabled() {
        return ProjectionTools.ENABLE_AUTO_COLLECT_MATERIALS.getBooleanValue();
    }

    public static boolean isAutoCollectMaterialsWithQuickShulkerEnabled() {
        return ModSupport.ENABLE_QUICK_SHULKER.getBooleanValue();
    }

    public static boolean isLitematicaContainerAutofillEnabled() {
        return ProjectionTools.ENABLE_LITEMATICA_CONTAINER_AUTOFILL.getBooleanValue();
    }

    public static boolean isLitematicaContainerAutofillWithQuickShulkerEnabled() {
        return ModSupport.ENABLE_QUICK_SHULKER.getBooleanValue();
    }

    public static boolean isCreativeContainerFillEnabled() {
        return ProjectionTools.ENABLE_CREATIVE_CONTAINER_FILL.getBooleanValue();
    }

    public static boolean isContainerFillOverflowDropEnabled() {
        return ProjectionTools.ENABLE_CONTAINER_FILL_OVERFLOW_DROP.getBooleanValue();
    }

    public static int getContainerFillFreeSlotsLimit() {
        int value = ProjectionTools.CONTAINER_FILL_FREE_SLOTS_LIMIT.getIntegerValue();
        int clamped = Math.max(MIN_CONTAINER_FILL_FREE_SLOTS_LIMIT, Math.min(MAX_CONTAINER_FILL_FREE_SLOTS_LIMIT, value));
        if (clamped != value) {
            ProjectionTools.CONTAINER_FILL_FREE_SLOTS_LIMIT.setIntegerValue(clamped);
        }
        return clamped;
    }

    public static List<String> getContainerFillProtectedItems() {
        return ProjectionTools.CONTAINER_FILL_PROTECTED_ITEMS.getStrings();
    }

    public static List<String> getContainerFillReplacements() {
        return ProjectionTools.CONTAINER_FILL_REPLACEMENTS.getStrings();
    }

    public static int getMaterialCollectExtra0To10() {
        return getClampedMaterialCollectExtra(ProjectionTools.MATERIAL_COLLECT_EXTRA_0_TO_10, MAX_MATERIAL_COLLECT_EXTRA_0_TO_10);
    }

    public static int getMaterialCollectExtra10To20() {
        return getClampedMaterialCollectExtra(ProjectionTools.MATERIAL_COLLECT_EXTRA_10_TO_20, MAX_MATERIAL_COLLECT_EXTRA_10_TO_20);
    }

    public static int getMaterialCollectExtra20To50() {
        return getClampedMaterialCollectExtra(ProjectionTools.MATERIAL_COLLECT_EXTRA_20_TO_50, MAX_MATERIAL_COLLECT_EXTRA_20_TO_50);
    }

    public static int getMaterialCollectExtra50To100() {
        return getClampedMaterialCollectExtra(ProjectionTools.MATERIAL_COLLECT_EXTRA_50_TO_100, MAX_MATERIAL_COLLECT_EXTRA_50_TO_100);
    }

    public static int getMaterialCollectExtra100To500() {
        return getClampedMaterialCollectExtra(ProjectionTools.MATERIAL_COLLECT_EXTRA_100_TO_500, MAX_MATERIAL_COLLECT_EXTRA_100_TO_500);
    }

    public static int getMaterialCollectExtraOver500() {
        return getClampedMaterialCollectExtra(ProjectionTools.MATERIAL_COLLECT_EXTRA_OVER_500, MAX_MATERIAL_COLLECT_EXTRA_OVER_500);
    }

    private static int getClampedMaterialCollectExtra(ConfigInteger config, int maxValue) {
        int value = config.getIntegerValue();
        int clamped = Math.max(MIN_MATERIAL_COLLECT_EXTRA, Math.min(maxValue, value));
        if (clamped != value) {
            config.setIntegerValue(clamped);
        }
        return clamped;
    }

    public static int getQuickShulkerActionIntervalTicks() {
        int value = ModSupport.QUICK_SHULKER_ACTION_INTERVAL_TICKS.getIntegerValue();
        int clamped = Math.max(MIN_QUICK_SHULKER_ACTION_INTERVAL_TICKS, Math.min(MAX_QUICK_SHULKER_ACTION_INTERVAL_TICKS, value));
        if (clamped != value) {
            ModSupport.QUICK_SHULKER_ACTION_INTERVAL_TICKS.setIntegerValue(clamped);
        }
        return clamped;
    }

    public static boolean isQuickClearContainerEnabled() {
        return isContainerToolModeEnabled()
                && ContainerTools.CONTAINER_TOOL_MODE.getOptionListValue() == ContainerToolMode.QUICK_CLEAR;
    }

    public static boolean isQuickContainerCopyEnabled() {
        return isContainerToolModeEnabled()
                && ContainerTools.CONTAINER_TOOL_MODE.getOptionListValue() == ContainerToolMode.QUICK_COPY;
    }

    public static boolean isCreativePackingEnabled() {
        return ContainerTools.ENABLE_CREATIVE_PACKING.getBooleanValue();
    }

    public static int getCreativePackingBundleStacks() {
        return ContainerTools.CREATIVE_PACKING_BUNDLE_STACKS.getIntegerValue();
    }

    public static boolean areCreativePackingNestedContainersAllowed() {
        return ContainerTools.ALLOW_CREATIVE_PACKING_NESTED_CONTAINERS.getBooleanValue();
    }

    public static boolean isQuickBeaconEnabled() {
        return ContainerTools.ENABLE_QUICK_BEACON.getBooleanValue();
    }

    public static List<String> getBeaconEffectOrderStrings() {
        return ContainerTools.BEACON_EFFECT_ORDER.getStrings();
    }

    public static boolean isContainerLockButtonVisible() {
        return ContainerTools.SHOW_CONTAINER_LOCK_BUTTON.getBooleanValue();
    }

    public static boolean isSlotLockOverlayVisible() {
        return ContainerTools.SHOW_SLOT_LOCK_OVERLAY.getBooleanValue();
    }

    public static boolean areManualLockedSlotInteractionsAllowed() {
        return ContainerTools.ALLOW_MANUAL_LOCKED_SLOT_INTERACTION.getBooleanValue();
    }
    public static boolean canAddLitematicaPreviewImages() {
        return ProjectionTools.ALLOW_ADDING_LITEMATICA_PREVIEW_IMAGES.getBooleanValue();
    }

    public static boolean shouldReplaceLitematicaPreviewWith3D() {
        return ProjectionTools.REPLACE_LITEMATICA_PREVIEW_WITH_3D.getBooleanValue();
    }
    public static boolean areEasyPlaceVanillaInteractionsAllowed() {
        return ProjectionTools.ALLOW_EASY_PLACE_VANILLA_INTERACTIONS.getBooleanValue();
    }

    public static boolean areEasyPlaceInteractionScreensAllowed() {
        return ProjectionTools.ALLOW_EASY_PLACE_INTERACTION_SCREENS.getBooleanValue();
    }

    public static boolean areEasyPlaceRedstoneInteractionsAllowed() {
        return ProjectionTools.ALLOW_EASY_PLACE_REDSTONE_INTERACTIONS.getBooleanValue();
    }

    public static boolean areEasyPlaceFunctionalBlockInteractionsAllowed() {
        return ProjectionTools.ALLOW_EASY_PLACE_FUNCTIONAL_BLOCK_INTERACTIONS.getBooleanValue();
    }

    public static boolean areEasyPlaceFluidInteractionsAllowed() {
        return ProjectionTools.ALLOW_EASY_PLACE_FLUID_INTERACTIONS.getBooleanValue();
    }

    public static boolean areEasyPlaceToolInteractionsAllowed() {
        return ProjectionTools.ALLOW_EASY_PLACE_TOOL_INTERACTIONS.getBooleanValue();
    }

    public static boolean areEasyPlaceDecorationInteractionsAllowed() {
        return ProjectionTools.ALLOW_EASY_PLACE_DECORATION_INTERACTIONS.getBooleanValue();
    }

    public static boolean areEasyPlaceSurvivalInteractionsAllowed() {
        return ProjectionTools.ALLOW_EASY_PLACE_SURVIVAL_INTERACTIONS.getBooleanValue();
    }

    public static boolean areEasyPlaceSpecialInteractionsAllowed() {
        return ProjectionTools.ALLOW_EASY_PLACE_SPECIAL_INTERACTIONS.getBooleanValue();
    }

    public static boolean areEasyPlaceDangerousInteractionsAllowed() {
        return ProjectionTools.ALLOW_EASY_PLACE_DANGEROUS_INTERACTIONS.getBooleanValue();
    }

    public static boolean areEasyPlaceAdminInteractionsAllowed() {
        return ProjectionTools.ALLOW_EASY_PLACE_ADMIN_INTERACTIONS.getBooleanValue();
    }

    public static boolean isHoldEasyPlaceEnabled() {
        return ProjectionTools.HOLD_EASY_PLACE.getBooleanValue();
    }

    public static int getHoldEasyPlaceCacheTimeMs() {
        int value = ProjectionTools.HOLD_EASY_PLACE_CACHE_TIME_MS.getIntegerValue();
        int clamped = Math.max(MIN_HOLD_EASY_PLACE_CACHE_TIME_MS, Math.min(MAX_HOLD_EASY_PLACE_CACHE_TIME_MS, value));
        if (clamped != value) {
            ProjectionTools.HOLD_EASY_PLACE_CACHE_TIME_MS.setIntegerValue(clamped);
        }
        return clamped;
    }

    public static boolean isLitematicaSchematicFolderButtonVisible() {
        return ProjectionTools.SHOW_LITEMATICA_SCHEMATIC_FOLDER_BUTTON.getBooleanValue();
    }

    public static boolean isLitematicaContainerMaterialListButtonVisible() {
        return ProjectionTools.SHOW_LITEMATICA_CONTAINER_MATERIAL_BUTTON.getBooleanValue();
    }

    public static boolean isLitematica3DPreviewEnabled() {
        return ProjectionTools.SHOW_LITEMATICA_3D_PREVIEW.getBooleanValue();
    }

    public static boolean isLitematicaContainerSlotHintsVisible() {
        return ProjectionTools.SHOW_LITEMATICA_CONTAINER_SLOT_HINTS.getBooleanValue();
    }

    public static boolean isLitematicaContainerVerifierEnabled() {
        return ProjectionTools.SHOW_LITEMATICA_CONTAINER_VERIFIER.getBooleanValue();
    }

    public static ContainerToolMode getContainerToolMode() {
        return (ContainerToolMode) ContainerTools.CONTAINER_TOOL_MODE.getOptionListValue();
    }

    public static void cycleContainerToolMode(boolean forward) {
        ContainerTools.CONTAINER_TOOL_MODE.setOptionListValue(
                ContainerTools.CONTAINER_TOOL_MODE.getOptionListValue().cycle(forward)
        );
    }

    public static boolean isOpenConfigHotkeyEnabled() {
        return Hotkeys.ENABLE_OPEN_CONFIG_HOTKEY.getBooleanValue();
    }

    public static boolean isActionButtonDraggingEnabled() {
        return Hotkeys.ENABLE_ACTION_BUTTON_DRAGGING.getBooleanValue();
    }

    public static ButtonOffset getActionButtonOffset(String key) {
        return BUTTON_OFFSETS.getOrDefault(key, ButtonOffset.ZERO);
    }

    public static void setActionButtonOffset(String key, int x, int y) {
        if (x == 0 && y == 0) {
            BUTTON_OFFSETS.remove(key);
        } else {
            BUTTON_OFFSETS.put(key, new ButtonOffset(x, y));
        }
    }

    public static void resetActionButtonOffset(String key) {
        BUTTON_OFFSETS.remove(key);
    }

    public static IKeybind getSingleCraftHotkey() {
        return Hotkeys.SINGLE_CRAFT.getKeybind();
    }

    public static IKeybind getRapidCraftHotkey() {
        return Hotkeys.RAPID_CRAFT.getKeybind();
    }

    public static IKeybind getQuickSortHotkey() {
        return Hotkeys.QUICK_SORT.getKeybind();
    }

    public static void loadFromFile() {
        BUTTON_OFFSETS.clear();
        Path configFile = FileUtils.getConfigDirectory().resolve(CONFIG_FILE_NAME);

        if (!Files.exists(configFile) || !Files.isReadable(configFile)) {
            return;
        }

        JsonElement element = JsonUtils.parseJsonFile(configFile);
        if (element == null || !element.isJsonObject()) {
            return;
        }

        JsonObject root = element.getAsJsonObject();
        ConfigUtils.readConfigBase(root, "Crafting", Crafting.OPTIONS);
        ConfigUtils.readConfigBase(root, "ContainerTools", ContainerTools.OPTIONS);
        ConfigUtils.readConfigBase(root, "ProjectionTools", ProjectionTools.OPTIONS);
        ConfigUtils.readConfigBase(root, "ModSupport", ModSupport.OPTIONS);
        ConfigUtils.readConfigBase(root, "Hotkeys", Hotkeys.OPTIONS);
        migrateBeaconRegenerationLevelName();
        readButtonPositions(root);

        JsonObject containerTools = root.getAsJsonObject("ContainerTools");
        if (containerTools != null) {
            if (getLegacyEnabledValue(containerTools.get("quickTradeStandby"))) {
                ContainerTools.ENABLE_QUICK_TRADE.setBooleanValue(true);
            }
            if (getLegacyEnabledValue(containerTools.get("quickStashStandby"))) {
                ContainerTools.CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.QUICK_STASH);
            }
            if (getLegacyEnabledValue(containerTools.get("quickClearContainerStandby"))) {
                ContainerTools.CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.QUICK_CLEAR);
            }

            applyLegacyContainerToolMappings(containerTools);
        }
    }

    private static boolean getLegacyEnabledValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsBoolean();
        }
        if (element.isJsonObject()) {
            JsonElement enabled = element.getAsJsonObject().get("enabled");
            return enabled != null && enabled.isJsonPrimitive() && enabled.getAsBoolean();
        }
        return false;
    }

    private static void migrateBeaconRegenerationLevelName() {
        List<String> current = ContainerTools.BEACON_EFFECT_ORDER.getStrings();
        List<String> migrated = current.stream()
                .map(QuickCraftConfigs::migrateBeaconRegenerationLevelName)
                .toList();
        if (!current.equals(migrated)) {
            ContainerTools.BEACON_EFFECT_ORDER.setStrings(migrated);
        }
    }

    private static String migrateBeaconRegenerationLevelName(String raw) {
        String normalized = raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "");
        return switch (normalized) {
            case "regeneration2", "regenerationii", "regen2", "regenii" -> "regeneration1";
            case "生命恢复2", "生命恢复ii", "恢复2", "恢复ii" -> "生命恢复1";
            default -> raw;
        };
    }

    public static void saveToFile() {
        Path dir = FileUtils.getConfigDirectory();
        if (!Files.exists(dir)) {
            FileUtils.createDirectoriesIfMissing(dir);
        }
        if (!Files.isDirectory(dir)) {
            return;
        }

        JsonObject root = new JsonObject();
        ConfigUtils.writeConfigBase(root, "Crafting", Crafting.OPTIONS);
        ConfigUtils.writeConfigBase(root, "ContainerTools", ContainerTools.OPTIONS);
        ConfigUtils.writeConfigBase(root, "ProjectionTools", ProjectionTools.OPTIONS);
        ConfigUtils.writeConfigBase(root, "ModSupport", ModSupport.OPTIONS);
        ConfigUtils.writeConfigBase(root, "Hotkeys", Hotkeys.OPTIONS);
        writeButtonPositions(root);
        JsonUtils.writeJsonToFile(root, dir.resolve(CONFIG_FILE_NAME));
    }

    private static void readButtonPositions(JsonObject root) {
        BUTTON_OFFSETS.clear();
        JsonObject positions = root.getAsJsonObject(BUTTON_POSITIONS_KEY);
        if (positions == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : positions.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject offset = entry.getValue().getAsJsonObject();
            JsonElement x = offset.get("x");
            JsonElement y = offset.get("y");
            if (x != null && y != null
                    && x.isJsonPrimitive() && x.getAsJsonPrimitive().isNumber()
                    && y.isJsonPrimitive() && y.getAsJsonPrimitive().isNumber()) {
                BUTTON_OFFSETS.put(entry.getKey(), new ButtonOffset(x.getAsInt(), y.getAsInt()));
            }
        }
    }

    private static void writeButtonPositions(JsonObject root) {
        JsonObject positions = new JsonObject();
        BUTTON_OFFSETS.forEach((key, offset) -> {
            JsonObject value = new JsonObject();
            value.addProperty("x", offset.x());
            value.addProperty("y", offset.y());
            positions.add(key, value);
        });
        root.add(BUTTON_POSITIONS_KEY, positions);
    }

    public record ButtonOffset(int x, int y) {
        private static final ButtonOffset ZERO = new ButtonOffset(0, 0);
    }

    private static void applyLegacyContainerToolMappings(JsonObject containerTools) {
        if (getLegacyEnabledValue(containerTools.get("enableQuickContainerCopy"))) {
            ContainerTools.ENABLE_CONTAINER_TOOL_MODE.setBooleanValue(true);
            ContainerTools.CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.QUICK_COPY);
        } else if (getLegacyEnabledValue(containerTools.get("enableQuickClearContainer"))) {
            ContainerTools.ENABLE_CONTAINER_TOOL_MODE.setBooleanValue(true);
            ContainerTools.CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.QUICK_CLEAR);
        } else if (getLegacyEnabledValue(containerTools.get("enableQuickStash"))) {
            ContainerTools.ENABLE_CONTAINER_TOOL_MODE.setBooleanValue(true);
            ContainerTools.CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.QUICK_STASH);
        }

        if (getLegacyEnabledValue(containerTools.get("enableQuickBeacon"))) {
            ContainerTools.ENABLE_QUICK_BEACON.setBooleanValue(true);
        }

        if (getLegacyEnabledValue(containerTools.get("showLitematicaContainerMaterialButton"))) {
            ProjectionTools.SHOW_LITEMATICA_CONTAINER_MATERIAL_BUTTON.setBooleanValue(true);
        }
        if (getLegacyEnabledValue(containerTools.get("showLitematicaContainerVerifier"))) {
            ProjectionTools.SHOW_LITEMATICA_CONTAINER_VERIFIER.setBooleanValue(true);
        }
        if (getLegacyEnabledValue(containerTools.get("enableAutoCollectMaterials"))) {
            ProjectionTools.ENABLE_AUTO_COLLECT_MATERIALS.setBooleanValue(true);
        }
        if (getLegacyEnabledValue(containerTools.get("enableLitematicaContainerAutofill"))) {
            ProjectionTools.ENABLE_LITEMATICA_CONTAINER_AUTOFILL.setBooleanValue(true);
        }
        if (getLegacyEnabledValue(containerTools.get("enableCreativeContainerFill"))) {
            ProjectionTools.ENABLE_CREATIVE_CONTAINER_FILL.setBooleanValue(true);
        }
        if (getLegacyEnabledValue(containerTools.get("enableContainerFillOverflowDrop"))) {
            ProjectionTools.ENABLE_CONTAINER_FILL_OVERFLOW_DROP.setBooleanValue(true);
        }
        if (getLegacyEnabledValue(containerTools.get("enableAutoCollectMaterialsWithQuickShulker"))
                || getLegacyEnabledValue(containerTools.get("enableLitematicaContainerAutofillWithQuickShulker"))) {
            ModSupport.ENABLE_QUICK_SHULKER.setBooleanValue(true);
        }
    }

    @Override
    public void load() {
        loadFromFile();
    }

    @Override
    public void save() {
        saveToFile();
    }
}
