package com.yiyihehe.quickcraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防止业务代码绕过 QuickCraftKeyBindings 再次直接读取输入状态。
 */
class QuickCraftInputArchitectureTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path INPUT_HELPER = MAIN_JAVA.resolve(Path.of(
            "com", "yiyihehe", "quickcraft", "QuickCraftKeyBindings.java"));
    private static final Path HOTKEY_CALLBACKS = MAIN_JAVA.resolve(Path.of(
            "com", "yiyihehe", "quickcraft", "malilib", "QuickCraftHotkeyCallbacks.java"));
    private static final Path SLOT_LOCK_SCREEN_MIXIN = MAIN_JAVA.resolve(Path.of(
            "com", "yiyihehe", "quickcraft", "mixin", "QuickContainerLockScreenMixin.java"));

    private static final List<String> DIRECT_INPUT_READS = List.of(
            ".isKeybindHeld()",
            "GLFW.glfwGetKey(",
            "GLFW.glfwGetMouseButton(",
            "Screen.hasShiftDown()",
            "Screen.hasAltDown()",
            "client.options.useKey.isPressed()",
            "client.options.sneakKey.isPressed()");

    @Test
    @DisplayName("输入状态只能由 QuickCraftKeyBindings 统一读取")
    void inputStateReadsStayCentralized() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".java"))
                    .filter(candidate -> !candidate.equals(INPUT_HELPER))
                    .toList()) {
                String source = Files.readString(path);
                for (String directRead : DIRECT_INPUT_READS) {
                    if (source.contains(directRead)) {
                        violations.add(MAIN_JAVA.relativize(path) + " -> " + directRead);
                    }
                }
            }
        }

        assertThat(violations)
                .as("新增输入读取时应扩展 QuickCraftKeyBindings，而不是散落在业务类中")
                .isEmpty();
    }

    @Test
    @DisplayName("槽位锁只能由可配置热键回调触发")
    void slotLockUsesConfiguredHotkeyCallback() throws IOException {
        String callbacks = Files.readString(HOTKEY_CALLBACKS);
        String screenMixin = Files.readString(SLOT_LOCK_SCREEN_MIXIN);

        assertThat(callbacks)
                .contains("QuickContainerLock.handleSlotLockHotkey(MinecraftClient.getInstance())");
        assertThat(screenMixin)
                .doesNotContain("handleSlotLockHotkey(")
                .doesNotContain("isAltDown()");
    }
}
