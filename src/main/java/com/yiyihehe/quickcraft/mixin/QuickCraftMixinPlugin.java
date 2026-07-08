package com.yiyihehe.quickcraft.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * QuickCraft 的 mixin 条件加载插件。
 *
 * <p>Litematica 相关 mixin 的目标类只有在安装 Litematica 时才存在。
 * 这里按类名前缀统一拦截，避免未安装 Litematica 的客户端在 mixin 解析阶段直接找不到目标类。</p>
 */
public class QuickCraftMixinPlugin implements IMixinConfigPlugin {
    private static final String LITEMATICA_MIXIN_PREFIX = "com.yiyihehe.quickcraft.mixin.Litematica";

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
