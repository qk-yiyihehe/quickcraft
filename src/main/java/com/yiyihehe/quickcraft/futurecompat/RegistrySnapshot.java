package com.yiyihehe.quickcraft.futurecompat;

import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * 扫描/改写唯一依赖的"当前注册表"抽象：生产实现包 vanilla（运行时注册表，modded 环境天然兼容），
 * 测试用假实现——保证扫描与改写核心是可离线测试的纯函数。
 */
public interface RegistrySnapshot {
    boolean hasBlock(String id);

    boolean hasItem(String id);

    /** 当前注册表能否构造出 Name+Properties 的方块状态（块存在、属性键存在、属性值合法）。 */
    boolean blockStateExists(String id, Map<String, String> properties);

    /** 目标方块某属性的当前合法值集合（编辑器校验/提示用）；块或属性不存在时返回 null。 */
    @Nullable
    Set<String> propertyValues(String id, String property);
}
