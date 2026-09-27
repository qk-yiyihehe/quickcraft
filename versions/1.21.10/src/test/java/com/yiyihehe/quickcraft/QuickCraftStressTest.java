package com.yiyihehe.quickcraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuickCraft 核心函数压力测试。
 * 测试边界值、大规模输入、并发安全、性能等。
 */
class QuickCraftStressTest {

    private static final Random RNG = new Random(42); // 固定种子确保可复现

    // ===========================
    // buildDirectoryName 压力测试
    // ===========================

    @Test
    @DisplayName("buildDirectoryName: 10000个随机输入 → 全部产生合法文件名")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void buildDirectoryName_10000randomInputs() {
        for (int i = 0; i < 10000; i++) {
            String displayName = randomString(RNG.nextInt(0, 200));
            String rawKey = randomString(RNG.nextInt(1, 100));

            String result = invokeBuildDirectoryName(displayName, rawKey);
            assertThat(result).isNotNull().isNotEmpty()
                    .doesNotContain("\\", "/", ":", "*", "?", "\"", "<", ">", "|");
        }
    }

    @Test
    @DisplayName("buildDirectoryName: 100000个相同输入 → 结果一致")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void buildDirectoryName_deterministic() {
        String[] inputs = {"My World", "Test Server", "C:\\Program Files\\Game"};
        for (String displayName : inputs) {
            String first = invokeBuildDirectoryName(displayName, "consistent-key");
            for (int i = 0; i < 100000; i++) {
                assertThat(invokeBuildDirectoryName(displayName, "consistent-key"))
                        .isEqualTo(first);
            }
        }
    }

    @ParameterizedTest(name = "displayName 长度 = {0}")
    @MethodSource("lengthProvider")
    @DisplayName("buildDirectoryName: 各种长度输入不崩")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void buildDirectoryName_variousLengths(int length) {
        String displayName = "a".repeat(Math.max(0, length));
        String result = invokeBuildDirectoryName(displayName, "test-key");
        assertThat(result).isNotNull().isNotEmpty();
    }

    static IntStream lengthProvider() {
        return IntStream.of(0, 1, 31, 32, 63, 64, 65, 127, 128, 255, 1000, 10000);
    }

    // ===========================
    // normalizeEffectName 压力测试
    // ===========================

    @Test
    @DisplayName("normalizeEffectName: 100000个随机输入 → 不抛异常")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void normalizeEffectName_100000randomInputs() {
        for (int i = 0; i < 100000; i++) {
            String raw = randomString(RNG.nextInt(0, 200));
            String result = invokeNormalizeEffectName(raw);
            assertThat(result).isNotNull()
                    .doesNotContain("minecraft:")
                    .doesNotContain(" ");
        }
    }

    @Test
    @DisplayName("normalizeEffectName: MIDI 键盘效果名边界数组")
    void normalizeEffectName_unicode() {
        String[] unicodeTests = {
                "\u0000\u0001\u0002",        // 控制字符
                "速度✨💥🎯",                // emoji + 中文
                "Résistance",                // 重音字符
                "日本語効果名",              // 日文
                "한글효과이름",              // 韩文
                "a".repeat(10000)            // 极长字符串
        };

        for (String test : unicodeTests) {
            String result = invokeNormalizeEffectName(test);
            assertThat(result).isNotNull();
        }
    }

    // ===========================
    // JSON 序列化压力测试
    // ===========================

    @Test
    @DisplayName("Int Set 往返: 10000个元素 → 不丢数据")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void intSet_10000elementRoundtrip() {
        Set<Integer> original = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            original.add(i);
        }

        com.google.gson.JsonArray json = invokeToIntArray(original);
        assertThat(json).isNotNull().hasSize(10000);

        Set<Integer> restored = new HashSet<>();
        invokeReadIntSet(json, restored);
        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("Int Set 往返: 随机间隙元素 → 不丢数据")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void intSet_randomGapsRoundtrip() {
        Set<Integer> original = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            original.add(RNG.nextInt());
        }

        com.google.gson.JsonArray json = invokeToIntArray(original);
        Set<Integer> restored = new HashSet<>();
        invokeReadIntSet(json, restored);

        assertThat(restored).isEqualTo(original);
    }

    // ===========================
    // 并发安全测试（只读静态方法）
    // ===========================

    @RepeatedTest(value = 10, name = "并发调用 buildDirectoryName 第 {currentRepetition} 次")
    @DisplayName("buildDirectoryName: 多线程并发调用不崩")
    void buildDirectoryName_concurrentAccess() throws Exception {
        Thread[] threads = new Thread[10];
        RuntimeException[] failures = new RuntimeException[1];

        for (int t = 0; t < threads.length; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    String result = invokeBuildDirectoryName(
                            "Thread-" + threadId + "-" + i,
                            "key-" + i);
                    if (result == null || result.isEmpty()) {
                        failures[0] = new RuntimeException("Null/empty result");
                        return;
                    }
                }
            });
        }

        for (Thread thread : threads) thread.start();
        for (Thread thread : threads) thread.join();

        if (failures[0] != null) throw failures[0];
    }

    // ===========================
    // 反射辅助方法
    // ===========================

    private static String invokeBuildDirectoryName(String displayName, String rawKey) {
        try {
            Method method = QuickPersistentState.class.getDeclaredMethod(
                    "buildDirectoryName", String.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, displayName, rawKey);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    private static String invokeNormalizeEffectName(String raw) {
        try {
            // 使用 QuickPersistentStateBuildDirTest 中的Unsafe方式创建实例
            java.lang.reflect.Field unsafeField =
                    sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            QuickBeacon instance = (QuickBeacon) unsafe.allocateInstance(QuickBeacon.class);

            Method method = QuickBeacon.class.getDeclaredMethod(
                    "normalizeEffectName", String.class);
            method.setAccessible(true);
            return (String) method.invoke(instance, raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static com.google.gson.JsonArray invokeToIntArray(Set<Integer> values) {
        try {
            Method method = QuickContainerLock.class.getDeclaredMethod(
                    "toIntArray", Set.class);
            method.setAccessible(true);
            return (com.google.gson.JsonArray) method.invoke(null, values);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void invokeReadIntSet(
            com.google.gson.JsonElement element, Set<Integer> target) {
        try {
            Method method = QuickContainerLock.class.getDeclaredMethod(
                    "readIntSet", com.google.gson.JsonElement.class, Set.class);
            method.setAccessible(true);
            method.invoke(null, element, target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            // 混合 ASCII、Unicode、特殊字符
            char c = switch (RNG.nextInt(5)) {
                case 0 -> (char) RNG.nextInt(32, 127);   // ASCII 可打印
                case 1 -> (char) RNG.nextInt(0x4E00, 0x9FFF); // 中文
                case 2 -> (char) RNG.nextInt(0, 32);     // 控制字符
                case 3 -> (char) RNG.nextInt(0x3040, 0x309F); // 平假名
                default -> "<>:\"/\\|?*".charAt(RNG.nextInt(9)); // 非法文件名字符
            };
            sb.append(c);
        }
        return sb.toString();
    }
}
