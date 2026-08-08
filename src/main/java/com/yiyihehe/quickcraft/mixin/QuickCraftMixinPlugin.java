package com.yiyihehe.quickcraft.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 按依赖环境决定哪些 mixin 参与加载。
 * 目前主要用于把全部 Litematica 相关 mixin 限制在安装了 Litematica 时才启用。
 */
public class QuickCraftMixinPlugin implements IMixinConfigPlugin {
    private static final String LITEMATICA_MIXIN_PREFIX = "com.yiyihehe.quickcraft.mixin.Litematica";
    private static final String JEI_MIXIN_PREFIX = "com.yiyihehe.quickcraft.mixin.Jei";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(LITEMATICA_MIXIN_PREFIX)) {
            return FabricLoader.getInstance().isModLoaded("litematica");
        }
        if (mixinClassName.startsWith(JEI_MIXIN_PREFIX)) {
            return FabricLoader.getInstance().isModLoaded("jei");
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
