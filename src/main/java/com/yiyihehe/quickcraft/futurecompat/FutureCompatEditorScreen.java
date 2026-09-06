package com.yiyihehe.quickcraft.futurecompat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 未来版本映射编辑器（方案 v2.1 §7，malilib/Litematica 风格布局）：
 * 标题左上、按钮右上、列表占满主区域；问题条目源自动带入，目标做当前注册表校验（非法红字）；
 * 状态行提供"恢复默认"；同名方块/物品保存时自动互补；条目超限折叠提示。
 * 降级说明（v2.1 §10.3）：状态行暂不支持逐属性填值，统一落地为目标方块默认状态。
 */
public final class FutureCompatEditorScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(FutureCompatEditorScreen.class);

    sealed interface Row permits BlockRow, ItemRow, StateRow, InvalidRow, AutoMappedRow, MergedRow {
    }

    record BlockRow(String sourceId) implements Row {
    }

    record ItemRow(String sourceId) implements Row {
    }

    record StateRow(String sourceId, Map<String, String> sourceProperties) implements Row {
    }

    /** 失效映射：kind = block/item；目标默认带入原目标，可改可清（清空仅对用户条目生效=删除）。 */
    record InvalidRow(String kind, String sourceId, String targetId) implements Row {
    }

    /** 内置映射自动处理的条目：预填内置目标，可改（生成用户覆盖）；清空不影响内置表。 */
    record AutoMappedRow(String kind, String sourceId, String targetId) implements Row {
    }

    /** 同一 id 同时缺方块与物品映射的合并行：保存时同时写入两种映射。 */
    record MergedRow(String sourceId) implements Row {
    }

    private sealed interface Line permits HeaderLine, EntryLine {
    }

    private record HeaderLine(String title, @Nullable String hint) implements Line {
    }

    private record EntryLine(Row row) implements Line {
    }

    private static final int MARGIN = 24;
    private static final int ROW_HEIGHT = 26;
    private static final int LIST_TOP = 60;

    private final Screen parent;
    @Nullable
    private final Path schematicFile;
    private final ScanResult result;
    private final List<Line> lines = new ArrayList<>();
    private final Map<Row, EditBox> fields = new HashMap<>();
    private final Map<Row, Button> resetButtons = new HashMap<>();
    private final Map<Row, String> validity = new HashMap<>(); // empty | valid | invalid
    private final FutureCompatUserMappings userMappings;
    private final FutureCompatUserMappings userViewForHints;
    @Nullable
    private final RegistrySnapshot snapshot;
    private int fieldX;
    private int fieldWidth;
    private int listBottom;
    private int visibleLines;
    private int totalLines;
    @Nullable
    private String saveError;

    public FutureCompatEditorScreen(Screen parent, @Nullable Path schematicFile, ScanResult result) {
        super(Component.translatable("quickcraft.future_compat.editor.title"));
        this.parent = parent;
        this.schematicFile = schematicFile;
        this.result = result;
        this.userMappings = FutureSchematicCompatibility.loadUserMappings();
        this.userViewForHints = this.userMappings;
        Minecraft client = Minecraft.getInstance();
        this.snapshot = client.level != null
                ? new VanillaRegistrySnapshot(client.level.registryAccess())
                : null;
    }

    /** 管理模式（配置界面入口）：无文件上下文，仅查看/修改/删除已有用户映射。 */
    public FutureCompatEditorScreen(Screen parent) {
        this(parent, null, ScanResult.EMPTY);
    }

    @Override
    protected void init() {
        buildLines();
        this.fieldWidth = Math.min(220, Math.max(140, this.width - 2 * MARGIN - 320));
        this.fieldX = this.width - MARGIN - this.fieldWidth;
        this.listBottom = this.height - 34;
        this.visibleLines = Math.max(1, (this.listBottom - LIST_TOP) / ROW_HEIGHT);
        this.totalLines = this.lines.size();

        int y = LIST_TOP;
        int index = 0;
        for (Line line : this.lines) {
            if (index >= this.visibleLines) {
                break;
            }
            if (line instanceof EntryLine(Row row)) {
                // 内置映射条目只展示不可修改（目标已在左侧源文本中显示）；用户条目才生成输入框
                boolean readOnly = row instanceof AutoMappedRow autoRow && !this.isUserOwned(autoRow);
                if (!readOnly) {
                    EditBox field = new EditBox(
                            this.font,
                            this.fieldX,
                            y + 3,
                            this.fieldWidth,
                            18,
                            Component.translatable("quickcraft.future_compat.editor.target"));
                    field.setMaxLength(256);
                    String draft = initialDraft(row);
                    field.setValue(draft);
                    field.setResponder(text -> this.validate(row, text));
                    this.validate(row, draft);
                    addRenderableWidget(field);
                    this.fields.put(row, field);

                    // 每个状态行一个"恢复默认"按钮（vanilla 样式，与保存/取消一致）：
                    // 仅当输入偏离默认值（源 id）时可见可点，还原后自动隐藏——预填即默认，未修改时按钮无意义
                    if (row instanceof StateRow stateRow) {
                        this.updateResetButton(stateRow, field, y);
                    }
                }
            }
            y += ROW_HEIGHT;
            index++;
        }

        addRenderableWidget(Button.builder(Component.translatable("quickcraft.future_compat.editor.save"), button -> this.save())
                .bounds(this.width - MARGIN - 156, 6, 74, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("quickcraft.future_compat.editor.cancel"), button -> this.onClose())
                .bounds(this.width - MARGIN - 76, 6, 74, 20).build());
    }

    private void buildLines() {
        this.lines.clear();
        if (this.schematicFile == null) {
            buildManagementLines();
            return;
        }
        if (!this.result.unknownBlocks().isEmpty() || !this.result.unknownItems().isEmpty()) {
            // 同一 id 同时缺方块与物品时合并为一行（保存自动写入两种映射）
            java.util.Set<String> mergedIds = new java.util.HashSet<>();
            for (ScanResult.UnknownBlock block : this.result.unknownBlocks()) {
                for (ScanResult.UnknownItem item : this.result.unknownItems()) {
                    if (item.id().equals(block.id())) {
                        mergedIds.add(block.id());
                        break;
                    }
                }
            }
            if (!this.result.unknownBlocks().isEmpty()) {
                this.lines.add(new HeaderLine(
                        Component.translatable("quickcraft.future_compat.editor.group.block", this.result.unknownBlocks().size()).getString(),
                        Component.translatable("quickcraft.future_compat.editor.hint.autopair").getString()));
            }
            for (ScanResult.UnknownBlock block : this.result.unknownBlocks()) {
                if (mergedIds.contains(block.id())) {
                    this.lines.add(new EntryLine(new MergedRow(block.id())));
                } else {
                    this.lines.add(new EntryLine(new BlockRow(block.id())));
                }
            }

            int itemOnlyCount = this.result.unknownItems().size() - mergedIds.size();
            if (itemOnlyCount > 0) {
                this.lines.add(new HeaderLine(
                        Component.translatable("quickcraft.future_compat.editor.group.item", itemOnlyCount).getString(),
                        null));
            }
            for (ScanResult.UnknownItem item : this.result.unknownItems()) {
                if (!mergedIds.contains(item.id())) {
                    this.lines.add(new EntryLine(new ItemRow(item.id())));
                }
            }
        }

        if (!this.result.stateMismatches().isEmpty()) {
            this.lines.add(new HeaderLine(
                    Component.translatable("quickcraft.future_compat.editor.group.state", this.result.stateMismatches().size()).getString(),
                    Component.translatable("quickcraft.future_compat.editor.hint.state").getString()));
        }
        for (ScanResult.StateMismatch mismatch : this.result.stateMismatches()) {
            this.lines.add(new EntryLine(new StateRow(mismatch.id(), mismatch.properties())));
        }

        if (!this.result.invalidMappings().isEmpty()) {
            this.lines.add(new HeaderLine(
                    Component.translatable("quickcraft.future_compat.editor.group.invalid", this.result.invalidMappings().size()).getString(),
                    Component.translatable("quickcraft.future_compat.editor.hint.invalid").getString()));
        }
        for (ScanResult.InvalidMapping invalid : this.result.invalidMappings()) {
            this.lines.add(new EntryLine(new InvalidRow(invalid.kind(), invalid.sourceId(), invalid.targetId())));
        }

        for (ScanResult.AutoMapped auto : this.result.autoMapped()) {
            this.lines.add(new EntryLine(new AutoMappedRow(auto.kind(), auto.sourceId(), auto.targetId())));
        }

        // 用户映射管理组：列出全部用户条目（可改目标/清空删除），改错映射的回入口
        java.util.Set<String> invalidSources = new java.util.HashSet<>();
        for (ScanResult.InvalidMapping invalid : this.result.invalidMappings()) {
            invalidSources.add(invalid.sourceId());
        }
        Map<String, String> userBlocks = this.userMappings.blockEntries();
        Map<String, String> userItems = this.userMappings.itemEntries();
        List<StateMapping> userStates = this.userMappings.stateEntries();
        // 用户组同样按 id 合并方块+物品条目，与问题组展示一致
        java.util.Set<String> userMergedIds = new java.util.HashSet<>();
        for (String id : userBlocks.keySet()) {
            if (userItems.containsKey(id)) {
                userMergedIds.add(id);
            }
        }
        int userCount = 0;
        for (String id : userBlocks.keySet()) {
            if (userMergedIds.contains(id) || !invalidSources.contains(id)) {
                userCount++;
            }
        }
        for (String id : userItems.keySet()) {
            if (!userMergedIds.contains(id) && !invalidSources.contains(id)) {
                userCount++;
            }
        }
        userCount += userStates.size();
        if (userCount > 0) {
            this.lines.add(new HeaderLine(
                    Component.translatable("quickcraft.future_compat.editor.group.user", userCount).getString(),
                    Component.translatable("quickcraft.future_compat.editor.hint.user").getString()));
            for (Map.Entry<String, String> entry : userBlocks.entrySet()) {
                if (invalidSources.contains(entry.getKey())) {
                    continue;
                }
                if (userMergedIds.contains(entry.getKey())) {
                    this.lines.add(new EntryLine(new MergedRow(entry.getKey())));
                } else {
                    this.lines.add(new EntryLine(new BlockRow(entry.getKey())));
                }
            }
            for (Map.Entry<String, String> entry : userItems.entrySet()) {
                if (!userMergedIds.contains(entry.getKey()) && !invalidSources.contains(entry.getKey())) {
                    this.lines.add(new EntryLine(new ItemRow(entry.getKey())));
                }
            }
            for (StateMapping mapping : userStates) {
                this.lines.add(new EntryLine(new StateRow(mapping.sourceName(), mapping.sourceProperties())));
            }
        }
    }

    /** 管理模式（配置页入口，无文件上下文）：列出整张生效映射表（内置+用户合并），逐条可覆盖/删除。 */
    private void buildManagementLines() {
        FutureCompatMappings effective = FutureSchematicCompatibility.currentMappings();
        Map<String, String> blocks = effective.blockEntries();
        Map<String, String> items = effective.itemEntries();
        List<StateMapping> states = effective.stateEntries();
        if (blocks.isEmpty() && items.isEmpty() && states.isEmpty()) {
            return;
        }
        this.lines.add(new HeaderLine(
                Component.translatable("quickcraft.future_compat.editor.group.table",
                        blocks.size() + items.size() + states.size()).getString(),
                Component.translatable("quickcraft.future_compat.editor.hint.table").getString()));
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            this.lines.add(new EntryLine(new AutoMappedRow("block", entry.getKey(), entry.getValue())));
        }
        for (Map.Entry<String, String> entry : items.entrySet()) {
            this.lines.add(new EntryLine(new AutoMappedRow("item", entry.getKey(), entry.getValue())));
        }
        for (StateMapping mapping : states) {
            this.lines.add(new EntryLine(new StateRow(mapping.sourceName(), mapping.sourceProperties())));
        }
    }

    private String initialDraft(Row row) {
        // 用"生效映射表"（内置+用户合并）预填：内置条目显示内置目标，用户条目显示用户目标，未映射为空
        return FutureCompatDrafts.initialDraftFor(row, FutureSchematicCompatibility.currentMappings());
    }

    private String freshMappingKey(StateRow row) {
        return StateMapping.keyOf(row.sourceId(), row.sourceProperties());
    }

    /** 状态行"恢复默认"按钮的可见性维护：输入偏离源 id（默认值）时可见可点，否则隐藏。 */
    private void updateResetButton(StateRow stateRow, EditBox field, int y) {
        boolean differs = !Ids.normalize(field.getValue()).equals(Ids.normalize(stateRow.sourceId()));
        Button button = this.resetButtons.computeIfAbsent(stateRow, key -> {
            Button created = Button.builder(
                            Component.translatable("quickcraft.future_compat.editor.default_state"),
                            b -> field.setValue(stateRow.sourceId()))
                    .bounds(this.fieldX - 88, y + 2, 62, 18)
                    .build();
            addRenderableWidget(created);
            return created;
        });
        button.visible = differs;
        button.active = differs;
    }

    private void validate(Row row, @Nullable String text) {
        String trimmed = text == null ? "" : text.trim();
        EditBox field = this.fields.get(row);
        if (trimmed.isEmpty()) {
            this.validity.put(row, "empty");
            if (field != null) {
                field.setTextColor(0xFFE0E0E0);
            }
        } else {
            String target = Ids.normalize(trimmed);
            boolean valid = this.snapshot != null && !target.isEmpty() && switch (row) {
                case BlockRow blockRow -> this.snapshot.hasBlock(target);
                case ItemRow itemRow -> this.snapshot.hasItem(target);
                case StateRow stateRow -> this.snapshot.hasBlock(target);
                case InvalidRow invalidRow -> invalidRow.kind().equals("block")
                        ? this.snapshot.hasBlock(target)
                        : this.snapshot.hasItem(target);
                case AutoMappedRow autoRow -> autoRow.kind().equals("block")
                        ? this.snapshot.hasBlock(target)
                        : this.snapshot.hasItem(target);
                case MergedRow mergedRow -> this.snapshot.hasBlock(target);
            };
            this.validity.put(row, valid ? "valid" : "invalid");
            if (field != null) {
                field.setTextColor(valid ? 0xFFE0E0E0 : 0xFFFF5555);
            }
        }
        if (row instanceof StateRow stateRow) {
            Button resetButton = this.resetButtons.get(row);
            if (resetButton != null) {
                boolean differs = !Ids.normalize(trimmed).equals(Ids.normalize(stateRow.sourceId()));
                resetButton.visible = differs;
                resetButton.active = differs;
            }
        }
    }

    private void save() {
        if (this.snapshot == null) {
            this.saveError = Component.translatable("quickcraft.future_compat.editor.no_registry").getString();
            return;
        }
        try {
            for (Line line : this.lines) {
                if (!(line instanceof EntryLine(Row row))) {
                    continue;
                }
                EditBox field = this.fields.get(row);
                if (field == null) {
                    continue; // 超出可视上限的条目：保存当前映射后重开编辑
                }
                String text = field.getValue().trim();
                switch (row) {
                    case BlockRow blockRow -> saveIdRow(blockRow, text, true);
                    case ItemRow itemRow -> saveIdRow(itemRow, text, false);
                    case StateRow stateRow -> saveStateRow(stateRow, text);
                    case InvalidRow invalidRow -> saveInvalidRow(invalidRow, text);
                    case AutoMappedRow autoRow -> saveIdRow(autoRow, text, autoRow.kind().equals("block"));
                    case MergedRow mergedRow -> saveMergedRow(mergedRow, text);
                }
            }
            FutureSchematicCompatibility.saveUserMappingsToConfig(this.userMappings);
        } catch (Exception e) {
            LOGGER.warn("Failed to save user future-compat mappings", e);
            this.saveError = Component.translatable("quickcraft.future_compat.editor.save_failed").getString();
            return;
        }
        FutureSchematicCompatibility.reloadMappings();
        FutureCompatScanService.get().invalidateMappings();
        this.onClose();
    }

    private void saveIdRow(Row row, String text, boolean block) {
        String sourceId = sourceIdOf(row);
        if (text.isEmpty()) {
            if (block) {
                this.userMappings.removeBlock(sourceId);
            } else {
                this.userMappings.removeItem(sourceId);
            }
            return;
        }
        if (!"valid".equals(this.validity.get(row))) {
            return;
        }
        String target = Ids.normalize(text);
        if (block) {
            this.userMappings.putBlock(sourceId, target);
            this.autoPairTarget(sourceId, target, false);
        } else {
            this.userMappings.putItem(sourceId, target);
            this.autoPairTarget(sourceId, target, true);
        }
    }

    private static String sourceIdOf(Row row) {
        return switch (row) {
            case BlockRow(String sourceId) -> sourceId;
            case ItemRow(String sourceId) -> sourceId;
            case InvalidRow(String kind, String sourceId, String targetId) -> sourceId;
            case AutoMappedRow(String kind, String sourceId, String targetId) -> sourceId;
            case MergedRow(String sourceId) -> sourceId;
            case StateRow stateRow -> stateRow.sourceId();
        };
    }

    /** 合并行：同时写入方块与物品映射（物品目标不存在时只写方块半边，改写时会自动跳过）。 */
    private void saveMergedRow(MergedRow row, String text) {
        String sourceId = row.sourceId();
        if (text.isEmpty()) {
            this.userMappings.removeBlock(sourceId);
            this.userMappings.removeItem(sourceId);
            return;
        }
        if (!"valid".equals(this.validity.get(row))) {
            return;
        }
        String target = Ids.normalize(text);
        this.userMappings.putBlock(sourceId, target);
        if (this.snapshot != null && this.snapshot.hasItem(target)) {
            this.userMappings.putItem(sourceId, target);
        } else {
            this.userMappings.removeItem(sourceId);
        }
    }

    /** v2.1 §7：同一 id 同时出现在方块与物品类时，保存自动补另一类（仅在目标确实注册为该类型时）。 */
    private void autoPairTarget(String sourceId, String target, boolean fromBlock) {
        for (Line line : this.lines) {
            if (!(line instanceof EntryLine(Row row))) {
                continue;
            }
            Row pair = null;
            if (fromBlock && row instanceof ItemRow(String pairId) && pairId.equals(sourceId)) {
                pair = row;
            } else if (!fromBlock && row instanceof BlockRow(String pairId) && pairId.equals(sourceId)) {
                pair = row;
            }
            if (pair == null) {
                continue;
            }
            EditBox field = this.fields.get(pair);
            if (field != null && field.getValue().trim().isEmpty() && !"invalid".equals(this.validity.get(pair))) {
                boolean pairValid = fromBlock
                        ? this.snapshot != null && this.snapshot.hasItem(target)
                        : this.snapshot != null && this.snapshot.hasBlock(target);
                if (pairValid) {
                    field.setValue(target);
                    this.saveIdRow(pair, target, !fromBlock);
                }
            }
        }
    }

    private void saveStateRow(StateRow row, String text) {
        String key = freshMappingKey(row);
        if (text.isEmpty()) {
            this.userMappings.removeState(key);
            return;
        }
        if (!"valid".equals(this.validity.get(row))) {
            return;
        }
        this.userMappings.putState(new StateMapping(
                row.sourceId(), row.sourceProperties(), Ids.normalize(text), Map.of()));
    }

    private void saveInvalidRow(InvalidRow row, String text) {
        boolean block = row.kind().equals("block");
        if (text.isEmpty()) {
            if (block) {
                this.userMappings.removeBlock(row.sourceId());
            } else {
                this.userMappings.removeItem(row.sourceId());
            }
            return;
        }
        if (!"valid".equals(this.validity.get(row))) {
            return;
        }
        if (block) {
            this.userMappings.putBlock(row.sourceId(), Ids.normalize(text));
        } else {
            this.userMappings.putItem(row.sourceId(), Ids.normalize(text));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        super.extractRenderState(extractor, mouseX, mouseY, tickDelta);
        extractor.text(this.font, this.title, MARGIN, 9, 0xFFFFFFFF, true);
        if (this.schematicFile != null) {
            extractor.text(this.font,
                    Component.translatable("quickcraft.future_compat.editor.file",
                            this.font.plainSubstrByWidth(this.schematicFile.getFileName().toString(), this.width / 2)),
                    MARGIN, 25, 0xFF9AA4B2, true);
        }
        if (this.snapshot == null) {
            extractor.text(this.font,
                    Component.translatable("quickcraft.future_compat.editor.no_registry"),
                    MARGIN, 41, 0xFFFF5555, true);
        }
        if (this.saveError != null) {
            extractor.text(this.font, this.saveError,
                    MARGIN, this.height - 20, 0xFFFF5555, true);
        }
        if (this.lines.isEmpty()) {
            extractor.text(this.font,
                    Component.translatable("quickcraft.future_compat.editor.empty"),
                    MARGIN, LIST_TOP + 4, 0xFF8A93A0, true);
        }

        int y = LIST_TOP;
        int shown = 0;
        for (Line line : this.lines) {
            if (shown >= this.visibleLines) {
                break;
            }
            boolean hovered = mouseX >= MARGIN && mouseX <= this.width - MARGIN && mouseY >= y && mouseY < y + ROW_HEIGHT;
            switch (line) {
                case HeaderLine(String title, String hint) -> {
                    extractor.text(this.font, title, MARGIN, y + 5, 0xFFFFD37F, true);
                    if (hint != null) {
                        int hintX = MARGIN + this.font.width(title) + 12;
                        extractor.text(this.font,
                                this.font.plainSubstrByWidth(hint, Math.max(0, this.width - MARGIN - hintX)),
                                hintX, y + 5, 0xFF8A93A0, true);
                    }
                }
                case EntryLine(Row row) -> {
                    if (hovered) {
                        extractor.fill(MARGIN, y, this.width - MARGIN, y + ROW_HEIGHT - 2, 0x28FFFFFF);
                    }
                    this.renderRow(extractor, row, y);
                }
            }
            y += ROW_HEIGHT;
            shown++;
        }
        if (this.totalLines > this.visibleLines) {
            extractor.text(this.font,
                    Component.translatable("quickcraft.future_compat.editor.clamped",
                            this.visibleLines, this.totalLines - this.visibleLines),
                    MARGIN, this.height - 34, 0xFFE0A050, true);
        }
    }

    private void renderRow(GuiGraphicsExtractor extractor, Row row, int y) {
        String source = switch (row) {
            case BlockRow(String sourceId) -> sourceId;
            case ItemRow(String sourceId) -> sourceId;
            case StateRow(String sourceId, Map<String, String> properties) -> sourceId
                    + " [" + propertySummary(properties) + "]";
            case InvalidRow(String kind, String sourceId, String targetId) ->
                    sourceId + " → " + targetId + kindSuffix(kind);
            case AutoMappedRow(String kind, String sourceId, String targetId) ->
                    sourceId + " → " + targetId + kindSuffix(kind);
            case MergedRow(String sourceId) -> sourceId
                    + " (" + Component.translatable("quickcraft.future_compat.marker.merged").getString() + ")";
        };
        if (row instanceof InvalidRow || row instanceof AutoMappedRow) {
            String originKey = isUserOwned(row)
                    ? "quickcraft.future_compat.marker.user"
                    : "quickcraft.future_compat.marker.builtin";
            source += " (" + Component.translatable(originKey).getString() + ")";
        }
        // 箭头紧贴输入框；状态行的"恢复默认"按钮占 fieldX-88 起，源文本按行类型收缩避让
        boolean isStateRow = row instanceof StateRow;
        int arrowX = this.fieldX - 16;
        int textLimit = (isStateRow ? this.fieldX - 88 : this.fieldX - 16) - MARGIN - 14;
        extractor.text(this.font,
                this.font.plainSubstrByWidth(source, Math.max(40, textLimit)),
                MARGIN + 4, y + 7, 0xFFD8DEE8, true);
        extractor.text(this.font, "→",
                arrowX, y + 7, 0xFF6C7684, true);
    }

    private static String kindSuffix(String kind) {
        return kind.equals("block")
                ? ""
                : " (" + Component.translatable("quickcraft.future_compat.marker.item").getString() + ")";
    }

    private String propertySummary(Map<String, String> properties) {
        StringBuilder builder = new StringBuilder();
        properties.forEach((key, value) -> builder.append(key).append('=').append(value).append(','));
        return builder.length() > 0 ? builder.substring(0, builder.length() - 1) : "";
    }

    private boolean isUserOwned(Row row) {
        return switch (row) {
            case BlockRow(String sourceId) -> this.userViewForHints.containsBlock(sourceId);
            case ItemRow(String sourceId) -> this.userViewForHints.containsItem(sourceId);
            case InvalidRow invalid -> invalid.kind().equals("block")
                    ? this.userViewForHints.containsBlock(invalid.sourceId())
                    : this.userViewForHints.containsItem(invalid.sourceId());
            case AutoMappedRow auto -> auto.kind().equals("block")
                    ? this.userViewForHints.containsBlock(auto.sourceId())
                    : this.userViewForHints.containsItem(auto.sourceId());
            case MergedRow(String sourceId) -> this.userViewForHints.containsBlock(sourceId)
                    || this.userViewForHints.containsItem(sourceId);
            case StateRow state -> this.userViewForHints.containsState(freshMappingKey(state));
        };
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
