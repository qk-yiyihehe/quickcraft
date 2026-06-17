package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 投影容器填充专用替换名单。
 * 只给自动填充和自动收集使用，不参与验证、渲染或 HUD 展示。
 */
public final class QuickLitematicaContainerReplacements {
    private static final String SEPARATOR = "->";

    private QuickLitematicaContainerReplacements() {
    }

    public static boolean hasValidRules() {
        return !getRules().isEmpty();
    }

    public static QuickContainerCopy.TemplateSnapshot applyToSnapshot(QuickContainerCopy.TemplateSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }

        List<ReplacementRule> rules = getRules();
        if (rules.isEmpty()) {
            return snapshot;
        }

        List<ItemStack> replacedTemplates = new ArrayList<>(snapshot.slotTemplates().size());
        for (ItemStack template : snapshot.slotTemplates()) {
            replacedTemplates.add(applyToStack(template, rules));
        }

        return new QuickContainerCopy.TemplateSnapshot(
                snapshot.type(),
                replacedTemplates,
                List.copyOf(snapshot.disabledStates())
        );
    }

    public static ItemStack applyToStack(ItemStack stack) {
        return applyToStack(stack, getRules());
    }

    private static ItemStack applyToStack(ItemStack stack, List<ReplacementRule> rules) {
        if (stack.isEmpty() || rules.isEmpty()) {
            return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        }

        for (ReplacementRule rule : rules) {
            if (!matchesConfiguredItem(stack, rule.source())) {
                continue;
            }

            ItemStack replacement = rule.target().copy();
            replacement.setCount(getReplacementCount(stack, replacement));
            return replacement;
        }

        return stack.copy();
    }

    private static int getReplacementCount(ItemStack source, ItemStack target) {
        int sourceMax = Math.max(1, source.getItem().getDefaultStack().getMaxCount());
        int targetMax = Math.max(1, target.getMaxCount());

        if (sourceMax > 1 && targetMax == 1) {
            return 1;
        }

        int count = Math.round((float) source.getCount() * targetMax / sourceMax);
        return Math.max(1, Math.min(targetMax, count));
    }

    private static List<ReplacementRule> getRules() {
        List<ReplacementRule> rules = new ArrayList<>();
        for (String entry : QuickCraftConfigs.getContainerFillReplacements()) {
            ReplacementRule rule = parseRule(entry);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }

    private static ReplacementRule parseRule(String entry) {
        if (entry == null) {
            return null;
        }

        int separatorIndex = entry.indexOf(SEPARATOR);
        if (separatorIndex < 0) {
            return null;
        }

        String source = normalizeConfiguredItem(entry.substring(0, separatorIndex));
        String targetText = normalizeConfiguredItem(entry.substring(separatorIndex + SEPARATOR.length()));
        if (source.isEmpty() || targetText.isEmpty()) {
            return null;
        }

        ItemStack target = resolveConfiguredItem(targetText);
        return target.isEmpty() ? null : new ReplacementRule(source, target);
    }

    private static ItemStack resolveConfiguredItem(String value) {
        for (Item item : Registries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }

            ItemStack stack = item.getDefaultStack();
            if (matchesConfiguredItem(stack, value)) {
                ItemStack result = stack.copy();
                result.setCount(1);
                return result;
            }
        }

        return ItemStack.EMPTY;
    }

    private static boolean matchesConfiguredItem(ItemStack stack, String value) {
        if (stack.isEmpty() || value.isEmpty()) {
            return false;
        }

        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        String fullId = normalizeConfiguredItem(itemId.toString());
        String pathId = normalizeConfiguredItem(itemId.getPath());
        String displayName = normalizeConfiguredItem(stack.getItem().getDefaultStack().getName().getString());

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

    private record ReplacementRule(String source, ItemStack target) {
    }
}
