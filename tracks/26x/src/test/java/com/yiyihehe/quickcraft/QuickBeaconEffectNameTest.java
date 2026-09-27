package com.yiyihehe.quickcraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuickBeacon 效果名规范化逻辑单元测试。
 * 通过反射访问 private 方法 normalizeEffectName，测试字符串标准化逻辑。
 * 该方法不依赖任何实例字段，纯字符串处理。
 */
class QuickBeaconEffectNameTest {

    private static String normalizeEffectName(String raw) {
        try {
            Method method = QuickBeacon.class.getDeclaredMethod(
                    "normalizeEffectName", String.class);
            method.setAccessible(true);
            return (String) method.invoke(allocateUnsafe(QuickBeacon.class), raw);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("无法反射访问 normalizeEffectName", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocateUnsafe(Class<T> clazz) {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            return (T) unsafe.allocateInstance(clazz);
        } catch (Exception e) {
            throw new RuntimeException("Cannot allocate instance of " + clazz, e);
        }
    }

    // ---- 基础功能 ----

    @Test
    @DisplayName("trim 去除首尾空白")
    void trimsWhitespace() {
        assertThat(normalizeEffectName("  haste  ")).isEqualTo("haste");
    }

    @Test
    @DisplayName("统一转为小写")
    void convertsToLowercase() {
        assertThat(normalizeEffectName("HASTE")).isEqualTo("haste");
        assertThat(normalizeEffectName("Speed")).isEqualTo("speed");
    }

    @Test
    @DisplayName("去除 minecraft: 前缀")
    void removesMinecraftPrefix() {
        assertThat(normalizeEffectName("minecraft:haste")).isEqualTo("haste");
    }

    @Test
    @DisplayName("去除空格")
    void removesSpaces() {
        assertThat(normalizeEffectName("jump boost")).isEqualTo("jumpboost");
    }

    @Test
    @DisplayName("去除下划线")
    void removesUnderscores() {
        assertThat(normalizeEffectName("jump_boost")).isEqualTo("jumpboost");
    }

    @Test
    @DisplayName("去除连字符")
    void removesDashes() {
        assertThat(normalizeEffectName("jump-boost")).isEqualTo("jumpboost");
    }

    // ---- 组合场景 ----

    @Test
    @DisplayName("混合所有需要规范化的元素")
    void combinesAllNormalizations() {
        assertThat(normalizeEffectName("  Minecraft:Haste_II  "))
                .isEqualTo("hasteii");
    }

    @Test
    @DisplayName("带空格和连字符的多词效果")
    void multiWordEffects() {
        assertThat(normalizeEffectName("jump boost")).isEqualTo("jumpboost");
        assertThat(normalizeEffectName("jump-boost")).isEqualTo("jumpboost");
        assertThat(normalizeEffectName("jump_boost")).isEqualTo("jumpboost");
    }

    // ---- 边界值 ----

    @ParameterizedTest(name = "空白输入: \"{0}\"")
    @ValueSource(strings = {"", "   ", "\t  \n  "})
    @DisplayName("空白输入不抛异常")
    void blankInput_doesNotThrow(String blank) {
        String result = normalizeEffectName(blank);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("null 输入返回空字符串")
    void nullInput_returnsEmptyString() {
        String result = normalizeEffectName(null);
        assertThat(result).isNotNull().isEmpty();
    }

    // ---- 参数化：规范化结果一致性 ----

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @CsvSource({
            "HASTE,           haste",
            "  Speed  ,       speed",
            "minecraft:regeneration, regeneration",
            "Jump Boost,      jumpboost",
            "jump_boost,      jumpboost",
            "jump-boost,      jumpboost",
            "Resistance,      resistance",
            "MINECRAFT:STRENGTH, strength",
    })
    @DisplayName("多种输入格式规范化到相同输出")
    void normalizesVariousFormats(String input, String expected) {
        assertThat(normalizeEffectName(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("中文效果名也正确处理")
    void chineseEffectNames() {
        assertThat(normalizeEffectName("急迫")).isEqualTo("急迫");
        assertThat(normalizeEffectName("力量")).isEqualTo("力量");
        assertThat(normalizeEffectName(" 生命恢复 ")).isEqualTo("生命恢复");
    }
}
