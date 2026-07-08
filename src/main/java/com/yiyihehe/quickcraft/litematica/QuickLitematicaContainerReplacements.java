package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.QuickContainerCopy;
import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 投影容器填充专用替换名单。
 *
 * <p>规则只给自动填充和自动收集使用，不参与验证、渲染或 HUD 展示；这样投影仍按原始内容校验，
 * 只有真正准备材料或填容器时才把“可替代物品”换成玩家配置的目标物品。</p>
 */
public final class QuickLitematicaContainerReplacements {
    private static final String SEPARATOR = "->";
    private static final String CUSTOM_NAME_SEPARATOR = "#";

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

        // 可堆叠物品替换成不可堆叠物品时按 1 个目标物品处理，避免把一格 64 个需求膨胀成 64 个工具。
        if (sourceMax > 1 && targetMax == 1) {
            return 1;
        }

        // 按两种物品的最大堆叠数折算槽位占用；结果仍限制在目标物品一格能容纳的范围内。
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

        ConfiguredItem source = parseConfiguredItem(entry.substring(0, separatorIndex));
        ConfiguredItem targetText = parseConfiguredItem(entry.substring(separatorIndex + SEPARATOR.length()));
        if (source.itemName().isEmpty() || targetText.itemName().isEmpty()) {
            return null;
        }

        ItemStack target = resolveConfiguredItem(targetText);
        return target.isEmpty() ? null : new ReplacementRule(source, target);
    }

    /**
     * 解析配置里的目标物品。
     *
     * <p>配置允许写完整 id、路径 id 或显示名；目标端如果写了 {@code #自定义名}，这里会直接生成带名称组件的模板。</p>
     */
    private static ItemStack resolveConfiguredItem(ConfiguredItem value) {
        for (Item item : Registries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }

            ItemStack stack = item.getDefaultStack();
            if (matchesConfiguredItemName(stack, value.itemName())) {
                ItemStack result = stack.copy();
                result.setCount(1);
                if (value.customName() != null) {
                    result.set(DataComponentTypes.CUSTOM_NAME, Text.literal(value.customName()));
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
        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        return value.customName() == null
                || (customName != null && value.customName().equals(customName.getString().trim()));
    }

    private static boolean matchesConfiguredItemName(ItemStack stack, String value) {
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

    private record ConfiguredItem(String itemName, String customName) {
    }

    private record ReplacementRule(ConfiguredItem source, ItemStack target) {
    }
}
