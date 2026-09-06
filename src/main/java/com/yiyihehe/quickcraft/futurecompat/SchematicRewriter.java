package com.yiyihehe.quickcraft.futurecompat;

import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;

/**
 * 内存改写：仅当"源当前不存在（或状态不可构造）且目标当前可解析"时才动手；
 * 未命中映射一个字节不改（与原生行为逐位一致）；重复调用幂等。
 */
public final class SchematicRewriter {
    private SchematicRewriter() {
    }

    public record RewriteResult(boolean changed, int blocks, int items, int states, int invalidSkipped) {
        public static final RewriteResult NONE = new RewriteResult(false, 0, 0, 0, 0);
    }

    public static RewriteResult rewrite(NbtCompound root, RegistrySnapshot registries, FutureCompatMappings mappings) {
        int[] blocks = {0};
        int[] items = {0};
        int[] states = {0};
        int[] invalidSkipped = {0};
        boolean[] changed = {false};

        SchematicNbt.forEachRegion(root, region -> {
            SchematicNbt.forEachPaletteEntry(region, entry -> {
                if (!(entry instanceof NbtCompound compound)) {
                    return; // 旧版字符串 palette 无属性载体，保持 Litematica 原生行为
                }
                if (rewritePaletteEntry(compound, registries, mappings, blocks, states, invalidSkipped)) {
                    changed[0] = true;
                }
            });
            for (String listKey : new String[]{SchematicNbt.TILE_ENTITIES_KEY, SchematicNbt.ENTITIES_KEY}) {
                SchematicNbt.forEachItemList(region, listKey, list ->
                        SchematicNbt.forEachItemStack(list, stack -> {
                            if (rewriteItemStack(stack, registries, mappings, items, invalidSkipped)) {
                                changed[0] = true;
                            }
                        }));
            }
        });

        if (!changed[0]) {
            return RewriteResult.NONE;
        }
        return new RewriteResult(true, blocks[0], items[0], states[0], invalidSkipped[0]);
    }

    private static boolean rewritePaletteEntry(
            NbtCompound compound,
            RegistrySnapshot registries,
            FutureCompatMappings mappings,
            int[] blocks,
            int[] states,
            int[] invalidSkipped
    ) {
        String id = Ids.normalize(compound.getString(SchematicNbt.NAME_KEY).orElse(""));
        if (id.isEmpty()) {
            return false;
        }
        Map<String, String> properties = SchematicNbt.readProperties(compound);
        boolean blockRegistered = registries.hasBlock(id);

        if (blockRegistered && registries.blockStateExists(id, properties)) {
            return false;
        }

        // 状态级优先：覆盖"只改属性"与"改名+改属性"两种形态
        Optional<StateMapping> stateMapping = mappings.stateTarget(id, properties);
        if (stateMapping.isPresent()) {
            StateMapping mapping = stateMapping.get();
            if (!applyStateMapping(compound, mapping, registries)) {
                invalidSkipped[0]++;
                return false;
            }
            states[0]++;
            return true;
        }

        if (blockRegistered) {
            return false; // id 当前存在而状态不匹配且无状态映射：改 id 救不了属性，保持原生降级
        }

        String target = mappings.blockTarget(id);
        if (target == null) {
            return false;
        }
        if (!registries.hasBlock(target)) {
            invalidSkipped[0]++;
            return false;
        }
        // 与 v1 一致：只改 Name，Properties 原样保留（目标没有的属性由原版忽略，同名属性自动生效）
        compound.putString(SchematicNbt.NAME_KEY, target);
        blocks[0]++;
        return true;
    }

    private static boolean applyStateMapping(NbtCompound compound, StateMapping mapping, RegistrySnapshot registries) {
        if (!registries.hasBlock(mapping.targetName())
                || !registries.blockStateExists(mapping.targetName(), mapping.targetProperties())) {
            return false;
        }
        compound.putString(SchematicNbt.NAME_KEY, mapping.targetName());
        SchematicNbt.writeProperties(compound, mapping.targetProperties());
        return true;
    }

    private static boolean rewriteItemStack(
            NbtCompound stack,
            RegistrySnapshot registries,
            FutureCompatMappings mappings,
            int[] items,
            int[] invalidSkipped
    ) {
        String id = Ids.normalize(stack.getString(SchematicNbt.ITEM_ID_KEY).orElse(""));
        if (id.isEmpty() || registries.hasItem(id)) {
            return false;
        }
        String target = mappings.itemTarget(id);
        if (target == null) {
            return false;
        }
        if (!registries.hasItem(target)) {
            invalidSkipped[0]++;
            return false;
        }
        stack.putString(SchematicNbt.ITEM_ID_KEY, target);
        items[0]++;
        return true;
    }
}
