package com.yiyihehe.quickcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuickContainerLock JSON 序列化/反序列化单元测试。
 * 通过反射测试 readStringSet/readIntSet/toStringArray/toIntArray。
 * 这些方法是纯 Gson 操作，不依赖 Minecraft 运行时。
 */
class QuickContainerLockSerializationTest {

    // ---- readStringSet ----

    @Test
    @DisplayName("readStringSet: 合法 JSON 数组 → String Set")
    void readStringSet_parsesJsonArray() {
        JsonArray array = new JsonArray();
        array.add("key1");
        array.add("key2");
        array.add("key3");

        Set<String> target = new HashSet<>();
        invokeReadStringSet(array, target);

        assertThat(target).containsExactlyInAnyOrder("key1", "key2", "key3");
    }

    @Test
    @DisplayName("readStringSet: 空数组 → 空 Set")
    void readStringSet_emptyArray() {
        JsonArray array = new JsonArray();
        Set<String> target = new HashSet<>();
        invokeReadStringSet(array, target);
        assertThat(target).isEmpty();
    }

    @Test
    @DisplayName("readStringSet: null 元素 → 不抛异常，不修改集合")
    void readStringSet_nullElement() {
        Set<String> target = new HashSet<>();
        invokeReadStringSet(null, target);
        assertThat(target).isEmpty();
    }

    @Test
    @DisplayName("readStringSet: 非数组元素 → 不修改集合")
    void readStringSet_nonArrayElement() {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", "value");
        Set<String> target = new HashSet<>();
        invokeReadStringSet(obj, target);
        assertThat(target).isEmpty();
    }

    // ---- readIntSet ----

    @Test
    @DisplayName("readIntSet: 合法 JSON 数组 → Int Set")
    void readIntSet_parsesJsonArray() {
        JsonArray array = new JsonArray();
        array.add(1);
        array.add(42);
        array.add(100);

        Set<Integer> target = new HashSet<>();
        invokeReadIntSet(array, target);

        assertThat(target).containsExactlyInAnyOrder(1, 42, 100);
    }

    @Test
    @DisplayName("readIntSet: 空数组 → 空 Set")
    void readIntSet_emptyArray() {
        JsonArray array = new JsonArray();
        Set<Integer> target = new HashSet<>();
        invokeReadIntSet(array, target);
        assertThat(target).isEmpty();
    }

    @Test
    @DisplayName("readIntSet: null 元素 → 不抛异常")
    void readIntSet_nullElement() {
        Set<Integer> target = new HashSet<>();
        invokeReadIntSet(null, target);
        assertThat(target).isEmpty();
    }

    // ---- toStringArray ----

    @Test
    @DisplayName("toStringArray: String Set → JsonArray")
    void toStringArray_convertsSet() {
        Set<String> values = new HashSet<>();
        values.add("a");
        values.add("b");

        JsonArray result = invokeToStringArray(values);

        assertThat(result).isNotNull().hasSize(2);
        assertThat(result.get(0).getAsString()).isIn("a", "b");
        assertThat(result.get(1).getAsString()).isIn("a", "b");
    }

    @Test
    @DisplayName("toStringArray: 空 Set → 空数组")
    void toStringArray_emptySet() {
        JsonArray result = invokeToStringArray(new HashSet<>());
        assertThat(result).isNotNull();
        assertThat(result.size()).isZero();
    }

    // ---- toIntArray ----

    @Test
    @DisplayName("toIntArray: Int Set → JsonArray")
    void toIntArray_convertsSet() {
        Set<Integer> values = new HashSet<>();
        values.add(10);
        values.add(20);

        JsonArray result = invokeToIntArray(values);

        assertThat(result).isNotNull().hasSize(2);
        assertThat(result.get(0).getAsInt()).isIn(10, 20);
        assertThat(result.get(1).getAsInt()).isIn(10, 20);
    }

    @Test
    @DisplayName("toIntArray: 空 Set → 空数组")
    void toIntArray_emptySet() {
        JsonArray result = invokeToIntArray(new HashSet<>());
        assertThat(result).isNotNull();
        assertThat(result.size()).isZero();
    }

    // ---- 往返测试 ----

    @Test
    @DisplayName("String Set 往返: Set → JsonArray → Set (相同)")
    void stringSet_roundtrip() {
        Set<String> original = new HashSet<>();
        original.add("container_1");
        original.add("container_2");
        original.add("player");

        JsonArray json = invokeToStringArray(original);
        Set<String> restored = new HashSet<>();
        invokeReadStringSet(json, restored);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("Int Set 往返: Set → JsonArray → Set (相同)")
    void intSet_roundtrip() {
        Set<Integer> original = new HashSet<>();
        original.add(0);
        original.add(5);
        original.add(36);

        JsonArray json = invokeToIntArray(original);
        Set<Integer> restored = new HashSet<>();
        invokeReadIntSet(json, restored);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("大容量 Int Set 往返: 100个元素")
    void intSet_largeRoundtrip() {
        Set<Integer> original = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            original.add(i);
        }

        JsonArray json = invokeToIntArray(original);
        Set<Integer> restored = new HashSet<>();
        invokeReadIntSet(json, restored);

        assertThat(restored).isEqualTo(original);
    }

    // ---- 异常容错：包含 null 的 String Set ----

    @Test
    @DisplayName("toStringArray: 合并含 null 的 String Set（返回合法数组）")
    void toStringArray_nullElement_inSet() {
        Set<String> values = new HashSet<>();
        values.add(null);
        values.add("valid");
        JsonArray result = invokeToStringArray(values);
        // 会在调用 result.get(0).getAsString() 时抛异常，但序列化本身不抛
        assertThat(result).isNotNull();
        // 验证数组大小正确
        assertThat(result.size()).isEqualTo(2);
    }

    // ---- 反射辅助方法 ----

    private static void invokeReadStringSet(JsonElement element, Set<String> target) {
        invokePrivateStatic("readStringSet",
                new Class[]{JsonElement.class, Set.class},
                new Object[]{element, target});
    }

    private static void invokeReadIntSet(JsonElement element, Set<Integer> target) {
        invokePrivateStatic("readIntSet",
                new Class[]{JsonElement.class, Set.class},
                new Object[]{element, target});
    }

    private static JsonArray invokeToStringArray(Set<String> values) {
        return (JsonArray) invokePrivateStatic("toStringArray",
                new Class[]{Set.class},
                new Object[]{values});
    }

    private static JsonArray invokeToIntArray(Set<Integer> values) {
        return (JsonArray) invokePrivateStatic("toIntArray",
                new Class[]{Set.class},
                new Object[]{values});
    }

    private static Object invokePrivateStatic(String methodName,
                                               Class<?>[] paramTypes,
                                               Object[] args) {
        try {
            Method method = QuickContainerLock.class.getDeclaredMethod(methodName, paramTypes);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("无法反射访问 " + methodName, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
