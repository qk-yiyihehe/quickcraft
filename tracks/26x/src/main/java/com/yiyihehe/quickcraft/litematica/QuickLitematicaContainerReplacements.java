package com.yiyihehe.quickcraft.litematica;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.malilib.util.data.ItemType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HexFormat;
import java.util.Optional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 投影材料替换规则。
 * 普通投影使用“投影上下文 + 物品”保存；容器材料使用“投影上下文 + 容器组 + 槽位 + 物品”保存。
 */
public final class QuickLitematicaContainerReplacements {
    private static final String SEPARATOR = "->";
    private static final String CUSTOM_NAME_SEPARATOR = "#";
    private static final String CONTEXT_SEPARATOR = "||";
    private static Scope cachedScope;
    private static String cachedContextKey = "";
    private static List<String> cachedConfiguredEntries = List.of();
    private static List<ReplacementRule> cachedRules = List.of();
    private static HolderLookup.Provider cachedRegistryLookup;

    private QuickLitematicaContainerReplacements() {
    }

    public enum Scope {
        CONTAINER,
        PROJECTION
    }

    public static boolean isReplacementAllowed(ItemStack source, ItemStack target, Scope scope) {
        if (source == null || target == null || source.isEmpty() || target.isEmpty()) {
            return false;
        }
        return scope != Scope.PROJECTION
                || QuickLitematicaProjectionReplacementCompatibility.isAllowed(source, target);
    }

    public static boolean hasValidRules() {
        return hasValidRules(Scope.CONTAINER);
    }

    public static boolean hasValidRules(Scope scope) {
        return hasValidRules(scope, "");
    }

    public static boolean hasValidRules(Scope scope, String contextKey) {
        return !getRules(scope, contextKey).isEmpty();
    }

    public static QuickContainerCopy.TemplateSnapshot applyToSnapshot(QuickContainerCopy.TemplateSnapshot snapshot) {
        if (snapshot == null) { return null; }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("litematica")
                && QuickLitematicaContainerMemory.isMaterialized(snapshot.replacementContextKey())) {
            return snapshot;
        }

        List<ItemStack> replacedTemplates = new ArrayList<>(snapshot.slotTemplates().size());
        for (int slotIndex = 0; slotIndex < snapshot.slotTemplates().size(); slotIndex++) {
            ItemStack template = snapshot.slotTemplates().get(slotIndex);
            replacedTemplates.add(applyToStack(
                    template,
                    Scope.CONTAINER,
                    snapshot.replacementContextKey(),
                    snapshot.containerGroupKey(),
                    slotIndex
            ));
        }

        return new QuickContainerCopy.TemplateSnapshot(
                snapshot.type(),
                replacedTemplates,
                List.copyOf(snapshot.disabledStates()),
                snapshot.replacementContextKey(),
                snapshot.containerGroupKey()
        );
    }

    public static ItemStack applyToStack(ItemStack stack) {
        return applyToStack(stack, Scope.CONTAINER);
    }

    public static ItemStack applyToStack(ItemStack stack, Scope scope) {
        return applyToStack(stack, scope, "");
    }

    public static ItemStack applyToStack(ItemStack stack, Scope scope, String contextKey) {
        return applyToStack(stack, scope, contextKey, "", -1);
    }

    public static ItemStack applyToStack(
            ItemStack stack,
            Scope scope,
            String contextKey,
            String groupKey,
            int slotIndex
    ) {
        return applyToStack(stack, scope, getRules(scope, contextKey), contextKey, groupKey, slotIndex);
    }

    public static Optional<ItemStack> getReplacementTarget(ItemStack stack) {
        return getReplacementTarget(stack, Scope.CONTAINER);
    }

    public static Optional<ItemStack> getReplacementTarget(ItemStack stack, Scope scope) {
        return getReplacementTarget(stack, scope, "");
    }

    public static Optional<ItemStack> getReplacementTarget(ItemStack stack, Scope scope, String contextKey) {
        return getReplacementTarget(stack, scope, contextKey, "", -1);
    }

    public static Optional<ItemStack> getReplacementTarget(
            ItemStack stack,
            Scope scope,
            String contextKey,
            String groupKey,
            int slotIndex
    ) {
        if (stack == null) {
            return Optional.empty();
        }
        ReplacementRule rule = findRule(getRules(scope, contextKey), stack, scope, contextKey, groupKey, slotIndex);
        return rule == null ? Optional.empty() : Optional.of(resolveTarget(stack, rule));
    }

    public static boolean hasSlotOverride(ItemStack source, String contextKey, String groupKey, int slotIndex) {
        return getRules(Scope.CONTAINER, contextKey).stream().anyMatch(rule ->
                matchesLocation(rule, Scope.CONTAINER, contextKey, groupKey, slotIndex) && matchesSource(source, rule));
    }

    private static final java.util.Set<String> migratedContexts = new java.util.HashSet<>();

    public static void migrateContainerContext(String oldContext, String fileContext) {
        if (fileContext.isBlank() || !migratedContexts.add(oldContext + "->" + fileContext)) { return; }
        List<String> configured = getConfiguredRules(Scope.CONTAINER);
        List<String> migrated = migrateContainerEntries(configured, oldContext, fileContext, registryLookup());
        if (!migrated.equals(configured)) {
            setConfiguredRules(Scope.CONTAINER, migrated);
            QuickCraftConfigs.saveToFile();
        }
    }

    static List<String> migrateContainerEntries(List<String> entries, String oldContext, String fileContext,
                                                HolderLookup.Provider lookup) {
        int suffix = fileContext.lastIndexOf("#regions=");
        String filePrefix = suffix >= 0 ? fileContext.substring(0, suffix) + "#regions=" : fileContext;
        List<String> unrelated = new ArrayList<>();
        java.util.Map<String, String> related = new java.util.LinkedHashMap<>();
        for (String entry : entries) {
            ScopedRule scoped = parseScopedRule(entry, Scope.CONTAINER);
            if (scoped == null || !(!oldContext.isBlank() && scoped.contextKey().equals(oldContext)
                    || scoped.contextKey().startsWith(filePrefix))) {
                unrelated.add(entry);
                continue;
            }
            String renamed;
            if (entry.trim().startsWith("{")) {
                JsonObject json = JsonParser.parseString(entry).getAsJsonObject();
                json.addProperty("context", fileContext);
                renamed = json.toString();
            } else {
                renamed = fileContext + entry.substring(scoped.contextKey().length());
            }
            ReplacementRule rule = parseRule(scoped.ruleText(), Scope.CONTAINER, scoped.contextKey(),
                    scoped.groupKey(), scoped.slotIndex(), lookup);
            if (rule == null) { unrelated.add(renamed); continue; }
            ItemStack source = rule.exactSource() != null ? rule.exactSource() : resolveConfiguredItem(rule.source());
            String identity = scoped.groupKey() + "||" + scoped.slotIndex() + "||" + itemSignature(source);
            related.remove(identity);
            related.put(identity, renamed);
        }
        unrelated.addAll(related.values());
        return unrelated;
    }

    public record ContainerReplacement(ItemStack source, ItemStack target, String contextKey,
                                       String groupKey, int slotIndex, boolean preserveCount) {
    }

    public static void setContainerReplacementRules(List<ContainerReplacement> replacements) {
        HolderLookup.Provider lookup = registryLookup();
        List<String> configured = new ArrayList<>(getConfiguredRules(Scope.CONTAINER));
        List<String> encoded = replacements.stream().map(rule -> encodeContainerRule(rule, lookup)).toList();
        for (int index = 0; index < replacements.size(); index++) {
            ContainerReplacement replacement = replacements.get(index);
            configured.removeIf(entry -> matchesConfiguredSource(entry, replacement.source(), Scope.CONTAINER,
                    replacement.contextKey(), replacement.groupKey(), replacement.slotIndex()));
            configured.add(encoded.get(index));
        }
        setConfiguredRules(Scope.CONTAINER, configured);
        QuickCraftConfigs.saveToFile();
        replacements.stream().map(ContainerReplacement::contextKey).distinct().forEach(QuickLitematicaContainerMemory::rulesChanged);
    }

    static String encodeContainerRule(ContainerReplacement rule, HolderLookup.Provider lookup) {
        if (!rule.target().isEmpty() && rule.target().getCount() > rule.target().getMaxStackSize()) {
            throw new IllegalArgumentException("Invalid container replacement count");
        }
        JsonObject json = new JsonObject();
        json.addProperty("version", 2);
        json.addProperty("context", rule.contextKey());
        json.addProperty("group", rule.groupKey());
        json.addProperty("slot", rule.slotIndex());
        json.addProperty("source", ItemStack.OPTIONAL_CODEC.encodeStart(lookup.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), rule.source()).getOrThrow().toString());
        json.addProperty("target", ItemStack.OPTIONAL_CODEC.encodeStart(lookup.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), rule.target()).getOrThrow().toString());
        json.addProperty("preserveCount", rule.preserveCount());
        return json.toString();
    }

    static HolderLookup.Provider registryLookup() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.level != null ? client.level.registryAccess()
                : RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    public static void migrateProjectionContext(String oldContext, String fileContext) {
        if (isBlank(fileContext)) { return; }
        List<String> configured = getConfiguredRules(Scope.PROJECTION);
        int separator = fileContext.indexOf("#regions=");
        String filePrefix = separator < 0 ? "" : fileContext.substring(0, separator + 9);
        List<String> migrated = new ArrayList<>();
        for (String entry : configured) {
            ScopedRule rule = parseScopedRule(entry, Scope.PROJECTION);
            boolean sameFile = rule != null && !filePrefix.isEmpty() && rule.contextKey().startsWith(filePrefix);
            if (rule != null && (sameFile || (!isBlank(oldContext) && rule.contextKey().equals(oldContext)))) {
                if (entry.trim().startsWith("{")) {
                    JsonObject json = JsonParser.parseString(entry).getAsJsonObject();
                    json.addProperty("context", fileContext);
                    migrated.add(json.toString());
                } else { migrated.add(fileContext + CONTEXT_SEPARATOR + rule.ruleText()); }
            } else { migrated.add(entry); }
        }
        if (!configured.equals(migrated)) {
            setConfiguredRules(Scope.PROJECTION, migrated);
            QuickCraftConfigs.saveToFile();
        }
    }

    public static void setProjectionReplacementRules(List<ItemStack> sources, ItemStack target, String context) {
        if (isBlank(context) || sources.isEmpty() || sources.stream().anyMatch(source -> !isReplacementAllowed(source, target, Scope.PROJECTION))) { return; }
        List<String> rules = new ArrayList<>(getConfiguredRules(Scope.PROJECTION));
        for (ItemStack source : sources) {
            rules.removeIf(entry -> matchesConfiguredSource(entry, source, Scope.PROJECTION, context, "", -1));
            if (!ItemStack.isSameItemSameComponents(source, target)) {
                ItemStack sourceCopy = source.copyWithCount(1);
                ItemStack targetCopy = target.copyWithCount(1);
                rules.add(encodeContainerRule(new ContainerReplacement(sourceCopy, targetCopy, context, "", -1, true), registryLookup()));
            }
        }
        setConfiguredRules(Scope.PROJECTION, rules);
        QuickCraftConfigs.saveToFile();
        QuickLitematicaContainerMemory.rulesChanged(context);
    }

    public static void setReplacementRule(ItemStack source, ItemStack target) {
        setReplacementRule(source, target, Scope.CONTAINER);
    }

    public static void setReplacementRule(ItemStack source, ItemStack target, Scope scope) {
        setReplacementRule(source, target, scope, "");
    }

    public static void setReplacementRule(
            ItemStack source,
            ItemStack target,
            Scope scope,
            String contextKey
    ) {
        setReplacementRule(source, target, scope, contextKey, "", -1);
    }

    public static void setReplacementRule(
            ItemStack source,
            ItemStack target,
            Scope scope,
            String contextKey,
            String groupKey,
            int slotIndex
    ) {
        if (scope == Scope.CONTAINER) {
            if (!isReplacementAllowed(source, target, scope)) {
                return;
            }
            setContainerReplacementRules(List.of(new ContainerReplacement(source, target, contextKey,
                    groupKey, slotIndex, true)));
            return;
        }
        if (!isReplacementAllowed(source, target, scope)) {
            return;
        }
        if (scope == Scope.PROJECTION && isBlank(contextKey)) {
            return;
        }

        String sourceRule = stackToRule(source);
        String targetRule = stackToRule(target);
        List<String> rules = new ArrayList<>(getConfiguredRules(scope));
        rules.removeIf(entry -> matchesConfiguredSource(entry, source, scope, contextKey, groupKey, slotIndex));

        String rule = sourceRule + SEPARATOR + targetRule;
        if (scope == Scope.PROJECTION) {
            rules.add(sanitizeContextPart(contextKey) + CONTEXT_SEPARATOR + rule);
        } else if (isBlank(contextKey)) {
            rules.add(rule);
        } else {
            rules.add(sanitizeContextPart(contextKey)
                    + CONTEXT_SEPARATOR
                    + sanitizeContextPart(groupKey)
                    + CONTEXT_SEPARATOR
                    + slotIndex
                    + CONTEXT_SEPARATOR
                    + rule);
        }

        setConfiguredRules(scope, rules);
        QuickCraftConfigs.saveToFile();
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("litematica")) {
            QuickLitematicaContainerMemory.rulesChanged(contextKey == null ? "" : contextKey);
        }
    }

    public static void removeReplacementRule(ItemStack source) {
        removeReplacementRule(source, Scope.CONTAINER);
    }

    public static void removeReplacementRule(ItemStack source, Scope scope) {
        removeReplacementRule(source, scope, "");
    }

    public static void removeReplacementRule(ItemStack source, Scope scope, String contextKey) {
        removeReplacementRule(source, scope, contextKey, "", -1);
    }

    public static void removeReplacementRule(
            ItemStack source,
            Scope scope,
            String contextKey,
            String groupKey,
            int slotIndex
    ) {
        if (source == null || (source.isEmpty() && (scope != Scope.CONTAINER || slotIndex < 0))) {
            return;
        }
        if (scope == Scope.PROJECTION && isBlank(contextKey)) {
            return;
        }

        List<String> rules = new ArrayList<>(getConfiguredRules(scope));
        rules.removeIf(entry -> matchesConfiguredSource(entry, source, scope, contextKey, groupKey, slotIndex));
        setConfiguredRules(scope, rules);
        QuickCraftConfigs.saveToFile();
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("litematica")) {
            QuickLitematicaContainerMemory.rulesChanged(contextKey == null ? "" : contextKey);
        }
    }

    public static void removeReplacementRulesForContext(Scope scope, String contextKey) {
        if (scope == null) {
            return;
        }

        List<String> rules = new ArrayList<>(getConfiguredRules(scope));
        if (scope == Scope.PROJECTION) {
            if (isBlank(contextKey)) {
                return;
            }
            rules.removeIf(entry -> matchesContext(entry, scope, contextKey));
        } else if (isBlank(contextKey)) {
            rules.removeIf(entry -> matchesContext(entry, scope, ""));
        } else {
            rules.removeIf(entry -> matchesContext(entry, scope, contextKey));
        }

        setConfiguredRules(scope, rules);
        QuickCraftConfigs.saveToFile();
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("litematica")) {
            QuickLitematicaContainerMemory.rulesChanged(contextKey == null ? "" : contextKey);
        }
    }

    public static String itemSignature(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }

        ItemStack copy = stack.copy();
        copy.setCount(1);
        return new ItemType(copy, true, true).toString();
    }

    public static String slotSignature(int slotIndex, ItemStack stack) {
        return slotIndex + ":" + itemSignature(stack) + ":" + Math.max(0, stack.getCount());
    }

    public static String containerGroupSignature(ItemStack containerStack, List<ItemStack> slotStacks) {
        StringBuilder builder = new StringBuilder();
        builder.append(itemSignature(containerStack)).append('|');

        for (int i = 0; i < slotStacks.size(); i++) {
            builder.append(slotSignature(i, slotStacks.get(i))).append(';');
        }

        return hashSignature(builder.toString());
    }

    private static String hashSignature(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static ItemStack applyToStack(
            ItemStack stack,
            Scope scope,
            List<ReplacementRule> rules,
            String contextKey,
            String groupKey,
            int slotIndex
    ) {
        if (rules.isEmpty()) {
            return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        }
        ReplacementRule rule = findRule(rules, stack, scope, contextKey, groupKey, slotIndex);
        return rule == null ? stack.copy() : resolveTarget(stack, rule);
    }

    static ReplacementRule findRule(List<ReplacementRule> rules, ItemStack source, Scope scope,
                                    String contextKey, String groupKey, int slotIndex) {
        // 精确槽位优先；保持原始也是精确规则，不能被后续总体修改覆盖。
        for (int pass = 0; pass < 2; pass++) {
            int wantedSlot = pass == 0 ? slotIndex : -1;
            if (pass == 1 && (scope != Scope.CONTAINER || slotIndex < 0)) {
                break;
            }
            for (ReplacementRule rule : rules) {
                if (matchesLocation(rule, scope, contextKey, groupKey, wantedSlot) && matchesSource(source, rule)) {
                    ItemStack target = resolveTarget(source, rule);
                    if ((scope == Scope.CONTAINER && target.isEmpty())
                            || (!target.isEmpty() && target.getCount() <= target.getMaxStackSize()
                            && (scope == Scope.CONTAINER || isReplacementAllowed(source, target, scope)))) {
                        return rule;
                    }
                    return null;
                }
            }
        }
        return null;
    }

    static ItemStack resolveTarget(ItemStack source, ReplacementRule rule) {
        ItemStack target = rule.target().copy();
        if (target.isEmpty()) { return ItemStack.EMPTY; }
        target.setCount(rule.scope() == Scope.CONTAINER
                ? (rule.preserveCount() ? source.getCount() : target.getCount())
                : getReplacementCount(source, target));
        return target;
    }

    static boolean matchesSource(ItemStack source, ReplacementRule rule) {
        return rule.exactSource() != null
                ? ItemStack.isSameItemSameComponents(source, rule.exactSource())
                : matchesConfiguredItem(source, rule.source());
    }

    static int getReplacementCount(ItemStack source, ItemStack target) {
        int sourceMax = Math.max(1, source.getItem().getDefaultInstance().getMaxStackSize());
        int targetMax = Math.max(1, target.getMaxStackSize());

        if (sourceMax > 1 && targetMax == 1) {
            return 1;
        }

        int count = Math.round((float) source.getCount() * targetMax / sourceMax);
        return Math.max(1, Math.min(targetMax, count));
    }

    public static boolean enabled() {
        return QuickCraftConfigs.ProjectionTools.SHOW_LITEMATICA_MATERIAL_REPLACEMENTS.getBooleanValue();
    }

    private static synchronized void setConfiguredRules(Scope scope, List<String> entries) {
        // MaLiLib 原地 clear/addAll，必须与 ProtoChunk 工作线程读取规则缓存使用同一锁。
        getConfig(scope).setStrings(entries);
    }

    private static synchronized List<ReplacementRule> getRules(Scope scope, String contextKey) {
        if (!enabled()) { return List.of(); }
        String effectiveContextKey = contextKey == null ? "" : contextKey;
        if (effectiveContextKey.isBlank()) { return List.of(); }
        List<String> configuredEntries = getConfiguredRules(scope);
        HolderLookup.Provider lookup = registryLookup();
        if (scope == cachedScope
                && lookup == cachedRegistryLookup
                && effectiveContextKey.equals(cachedContextKey)
                && configuredEntries.equals(cachedConfiguredEntries)) {
            return cachedRules;
        }

        List<ReplacementRule> rules = new ArrayList<>();
        for (String configuredEntry : configuredEntries) {
            ScopedRule scopedRule = parseScopedRule(configuredEntry, scope);
            if (scopedRule == null) {
                continue;
            }

            if (scope == Scope.PROJECTION) {
                if (isBlank(effectiveContextKey) || !scopedRule.contextKey().equals(effectiveContextKey)) {
                    continue;
                }
            } else if (isBlank(effectiveContextKey)) {
                if (!scopedRule.contextKey().isEmpty() || !scopedRule.groupKey().isEmpty()) {
                    continue;
                }
            } else if (!scopedRule.contextKey().equals(effectiveContextKey)) {
                continue;
            }

            ReplacementRule rule = parseRule(
                    scopedRule.ruleText(),
                    scope,
                    scopedRule.contextKey(),
                    scopedRule.groupKey(),
                    scopedRule.slotIndex(), lookup
            );
            if (rule != null) {
                rules.add(rule);
            }
        }

        cachedScope = scope;
        cachedRegistryLookup = lookup;
        cachedContextKey = effectiveContextKey;
        cachedConfiguredEntries = new ArrayList<>(configuredEntries);
        cachedRules = rules;
        return rules;
    }

    private static boolean matchesContext(String configuredEntry, Scope scope, String contextKey) {
        ScopedRule scopedRule = parseScopedRule(configuredEntry, scope);
        return scopedRule != null && scopedRule.contextKey().equals(contextKey);
    }

    private static boolean matchesConfiguredSource(
            String configuredEntry,
            ItemStack source,
            Scope scope,
            String contextKey,
            String groupKey,
            int slotIndex
    ) {
        ScopedRule scopedRule = parseScopedRule(configuredEntry, scope);
        if (scopedRule == null || !matchesLocation(scopedRule, scope, contextKey, groupKey, slotIndex)) {
            return false;
        }
        if (configuredEntry.trim().startsWith("{")) {
            ReplacementRule rule = decodeContainerRule(configuredEntry, registryLookup());
            return rule != null && matchesSource(source, rule);
        }

        int separatorIndex = scopedRule.ruleText().indexOf(SEPARATOR);
        return separatorIndex >= 0
                && matchesConfiguredItem(source, parseConfiguredItem(scopedRule.ruleText().substring(0, separatorIndex)));
    }

    private static boolean matchesLocation(
            ReplacementRule rule,
            Scope scope,
            String contextKey,
            String groupKey,
            int slotIndex
    ) {
        if (rule.scope() != scope) {
            return false;
        }

        if (scope == Scope.PROJECTION) {
            return rule.contextKey().equals(contextKey) && rule.groupKey().isEmpty() && rule.slotIndex() < 0;
        }

        if (isBlank(contextKey)) {
            return rule.contextKey().isEmpty() && rule.groupKey().isEmpty() && rule.slotIndex() < 0;
        }

        return rule.contextKey().equals(contextKey)
                && rule.groupKey().equals(groupKey == null ? "" : groupKey)
                && rule.slotIndex() == slotIndex;
    }

    private static boolean matchesLocation(
            ScopedRule rule,
            Scope scope,
            String contextKey,
            String groupKey,
            int slotIndex
    ) {
        if (scope == Scope.PROJECTION) {
            return rule.contextKey().equals(contextKey) && rule.groupKey().isEmpty() && rule.slotIndex() < 0;
        }

        if (isBlank(contextKey)) {
            return rule.contextKey().isEmpty() && rule.groupKey().isEmpty() && rule.slotIndex() < 0;
        }

        return rule.contextKey().equals(contextKey)
                && rule.groupKey().equals(groupKey == null ? "" : groupKey)
                && rule.slotIndex() == slotIndex;
    }

    private static ScopedRule parseScopedRule(String entry, Scope scope) {
        if (entry == null) {
            return null;
        }

        if (entry.trim().startsWith("{")) {
            try {
                JsonObject json = JsonParser.parseString(entry).getAsJsonObject();
                if (json.get("version").getAsInt() != 2) {
                    return null;
                }
                return new ScopedRule(json.get("context").getAsString(), json.get("group").getAsString(),
                        json.get("slot").getAsInt(), entry);
            } catch (RuntimeException exception) {
                return null;
            }
        }

        if (scope == Scope.PROJECTION) {
            int separatorIndex = entry.indexOf(CONTEXT_SEPARATOR);
            if (separatorIndex <= 0 || separatorIndex + CONTEXT_SEPARATOR.length() >= entry.length()) {
                return null;
            }

            return new ScopedRule(
                    entry.substring(0, separatorIndex),
                    "",
                    -1,
                    entry.substring(separatorIndex + CONTEXT_SEPARATOR.length())
            );
        }

        int first = entry.indexOf(CONTEXT_SEPARATOR);
        if (first < 0) {
            return new ScopedRule("", "", -1, entry);
        }

        int second = entry.indexOf(CONTEXT_SEPARATOR, first + CONTEXT_SEPARATOR.length());
        if (second < 0) {
            return null;
        }

        int third = entry.indexOf(CONTEXT_SEPARATOR, second + CONTEXT_SEPARATOR.length());
        if (third < 0) {
            return null;
        }

        int slotIndex;
        try {
            slotIndex = Integer.parseInt(entry.substring(second + CONTEXT_SEPARATOR.length(), third));
        } catch (NumberFormatException exception) {
            return null;
        }

        return new ScopedRule(
                entry.substring(0, first),
                entry.substring(first + CONTEXT_SEPARATOR.length(), second),
                slotIndex,
                entry.substring(third + CONTEXT_SEPARATOR.length())
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static ReplacementRule parseRule(
            String entry,
            Scope scope,
            String contextKey,
            String groupKey,
            int slotIndex,
            HolderLookup.Provider lookup
    ) {
        if (entry == null) {
            return null;
        }
        if (entry.trim().startsWith("{")) {
            ReplacementRule decoded = decodeContainerRule(entry, lookup);
            return decoded == null ? null : new ReplacementRule(scope, decoded.contextKey(), decoded.groupKey(), decoded.slotIndex(),
                    decoded.source(), decoded.target(), decoded.exactSource(), decoded.preserveCount());
        }

        int separatorIndex = entry.indexOf(SEPARATOR);
        if (separatorIndex < 0) {
            return null;
        }

        ConfiguredItem source = parseConfiguredItem(entry.substring(0, separatorIndex));
        ConfiguredItem targetText = parseConfiguredItem(entry.substring(separatorIndex + SEPARATOR.length()));
        if (source.itemName().isEmpty() || targetText.itemName().isEmpty()) {
            return null;
        }

        ItemStack target = resolveConfiguredItem(targetText);
        return target.isEmpty() ? null : new ReplacementRule(scope, contextKey, groupKey, slotIndex, source, target, null, true);
    }

    static ReplacementRule decodeContainerRule(String entry, HolderLookup.Provider lookup) {
        try {
            JsonObject json = JsonParser.parseString(entry).getAsJsonObject();
            if (json.get("version").getAsInt() != 2) {
                return null;
            }
            var sourceNbt = QuickLitematicaContainerMaterialsAccess.parseNbt(json.get("source").getAsString());
            ItemStack source = ItemStack.EMPTY;
            if (!sourceNbt.isEmpty()) {
                var decodedSource = ItemStack.OPTIONAL_CODEC.parse(lookup.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), sourceNbt).result();
                if (decodedSource.isEmpty()) {
                    return null;
                }
                source = decodedSource.get();
            }
            var targetNbt = QuickLitematicaContainerMaterialsAccess.parseNbt(json.get("target").getAsString());
            ItemStack target = ItemStack.EMPTY;
            if (!targetNbt.isEmpty()) {
                var decodedTarget = ItemStack.OPTIONAL_CODEC.parse(lookup.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), targetNbt).result();
                if (decodedTarget.isEmpty()) { return null; }
                target = decodedTarget.get();
                if (target.getCount() > target.getMaxStackSize()) { return null; }
            }
            return new ReplacementRule(Scope.CONTAINER, json.get("context").getAsString(),
                    json.get("group").getAsString(), json.get("slot").getAsInt(), null, target,
                    source, json.get("preserveCount").getAsBoolean());
        } catch (Exception exception) {
            return null;
        }
    }

    static ItemStack resolveConfiguredItem(ConfiguredItem value) {
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }

            ItemStack stack = item.getDefaultInstance();
            if (matchesConfiguredItemName(stack, value.itemName())) {
                ItemStack result = stack.copy();
                result.setCount(1);
                if (value.customName() != null) {
                    result.set(DataComponents.CUSTOM_NAME, Component.literal(value.customName()));
                }
                return result;
            }
        }

        return ItemStack.EMPTY;
    }

    private static boolean matchesConfiguredItem(ItemStack stack, ConfiguredItem value) {
        if (!matchesConfiguredItemName(stack, value.itemName())) {
            return false;
        }

        // 写了“物品#名字”时，必须匹配投影物品栈上的自定义名。
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        return value.customName() == null
                || (customName != null && value.customName().equals(customName.getString().trim()));
    }

    private static boolean matchesConfiguredItemName(ItemStack stack, String value) {
        if (stack.isEmpty() || value.isEmpty()) {
            return false;
        }

        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String fullId = normalizeConfiguredItem(itemId.toString());
        String pathId = normalizeConfiguredItem(itemId.getPath());
        String displayName = normalizeConfiguredItem(stack.getItem().getDefaultInstance().getHoverName().getString());

        return value.equals(displayName)
                || value.equals(fullId)
                || value.equals(pathId)
                || isKnownChineseAlias(value, fullId);
    }

    private static boolean isKnownChineseAlias(String value, String fullId) {
        // 用户示例里的“红石”对应原版显示名“红石粉”，这里按物品 ID 精确补别名。
        return value.equals("红石") && fullId.equals("minecraft:redstone");
    }

    private static String normalizeConfiguredItem(String entry) {
        return entry == null ? "" : entry.trim().toLowerCase(Locale.ROOT);
    }

    private static ConfiguredItem parseConfiguredItem(String entry) {
        if (entry == null) {
            return new ConfiguredItem("", null);
        }

        int separatorIndex = entry.indexOf(CUSTOM_NAME_SEPARATOR);
        if (separatorIndex < 0) {
            return new ConfiguredItem(normalizeConfiguredItem(entry), null);
        }

        String itemName = normalizeConfiguredItem(entry.substring(0, separatorIndex));
        String customName = entry.substring(separatorIndex + CUSTOM_NAME_SEPARATOR.length()).trim();
        return new ConfiguredItem(itemName, customName.isEmpty() ? null : customName);
    }

    private static String stackToRule(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String rule = id == null ? "minecraft:air" : id.toString();
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName != null && !customName.getString().isBlank()) {
            rule += CUSTOM_NAME_SEPARATOR + customName.getString().replace(SEPARATOR, "").replace(CONTEXT_SEPARATOR, "").trim();
        }
        return rule;
    }

    private static String sanitizeContextPart(String value) {
        return value == null ? "" : value.replace(CONTEXT_SEPARATOR, "");
    }

    private static List<String> getConfiguredRules(Scope scope) {
        return scope == Scope.PROJECTION
                ? QuickCraftConfigs.getProjectionMaterialReplacements()
                : QuickCraftConfigs.getContainerFillReplacements();
    }

    private static fi.dy.masa.malilib.config.options.ConfigStringList getConfig(Scope scope) {
        return scope == Scope.PROJECTION
                ? QuickCraftConfigs.ProjectionTools.PROJECTION_MATERIAL_REPLACEMENTS
                : QuickCraftConfigs.ProjectionTools.CONTAINER_FILL_REPLACEMENTS;
    }

    private record ScopedRule(String contextKey, String groupKey, int slotIndex, String ruleText) {
    }

    private record ConfiguredItem(String itemName, String customName) {
    }

    record ReplacementRule(
            Scope scope,
            String contextKey,
            String groupKey,
            int slotIndex,
            ConfiguredItem source,
            ItemStack target,
            ItemStack exactSource,
            boolean preserveCount
    ) {
    }
}
