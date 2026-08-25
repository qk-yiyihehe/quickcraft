package com.yiyihehe.quickcraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuickPersistentState.buildDirectoryName 纯字符串逻辑单元测试。
 * 通过反射访问 private 方法，零 Minecraft/jvm 依赖。
 */
class QuickPersistentStateBuildDirTest {

    private static String buildDirectoryName(String displayName, String rawKey) {
        try {
            Method method = QuickPersistentState.class.getDeclaredMethod(
                    "buildDirectoryName", String.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, displayName, rawKey);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("无法反射访问 buildDirectoryName", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    // ---- 基础功能 ----

    @Test
    @DisplayName("正常输入生成合法目录名")
    void normalInput_generatesValidName() {
        String result = buildDirectoryName("My World", "saves/MyWorld");
        assertThat(result)
                .startsWith("My_World-")
                .doesNotContain("\\", "/", ":", "*", "?", "\"", "<", ">", "|");
    }

    @Test
    @DisplayName("空格替换为下划线")
    void spacesAreReplacedWithUnderscores() {
        String result = buildDirectoryName("Hello World Test", "key");
        assertThat(result).startsWith("Hello_World_Test-");
        assertThat(result).doesNotContain(" ");
    }

    @Test
    @DisplayName("非法文件名字符替换为下划线")
    void illegalFilenameCharsReplacedWithUnderscore() {
        String result = buildDirectoryName("test:file*name?query", "key");
        assertThat(result).startsWith("test_file_name_query-");
        assertThat(result).doesNotContain("\\", "/", ":", "*", "?", "\"", "<", ">", "|");
    }

    @Test
    @DisplayName("末尾点号和空格被移除（但空格先被替换为下划线）")
    void trailingDotsAreRemoved() {
        String result = buildDirectoryName("World...", "key");
        String namePart = result.substring(0, result.lastIndexOf('-'));
        assertThat(namePart).doesNotMatch("[.]$");
    }

    @Test
    @DisplayName("末尾点号被移除（空格不影响）")
    void trailingDotsWithSpacesRemoved() {
        String result = buildDirectoryName("World...   ", "key");
        String namePart = result.substring(0, result.lastIndexOf('-'));
        assertThat(namePart).doesNotMatch("[.]$");
    }

    // ---- 边界值 ----

    @ParameterizedTest(name = "空白输入: \"{0}\"")
    @ValueSource(strings = {"", "   ", "\t  "})
    @DisplayName("空白 displayName 回退为 profile")
    void blankDisplayName_fallsBackToProfile(String blank) {
        String result = buildDirectoryName(blank, "any-key");
        assertThat(result).startsWith("profile-");
    }

    @Test
    @DisplayName("null displayName 回退为 profile")
    void nullDisplayName_fallsBackToProfile() {
        String result = buildDirectoryName(null, "any-key");
        assertThat(result).startsWith("profile-");
    }

    @Test
    @DisplayName("超过64字符的 displayName 被截断")
    void overlyLongDisplayName_isTruncated() {
        String longName = "a".repeat(200);
        String result = buildDirectoryName(longName, "key");
        // 截断后最多64字符 + "-" + 8位UUID后缀
        String namePart = result.substring(0, result.lastIndexOf('-'));
        assertThat(namePart.length()).isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("恰好64字符不截断")
    void exactly64Chars_isNotTruncated() {
        String exact64 = "a".repeat(64);
        String result = buildDirectoryName(exact64, "key");
        String namePart = result.substring(0, result.lastIndexOf('-'));
        assertThat(namePart.length()).isEqualTo(64);
    }

    @Test
    @DisplayName("混合非法字符+空格+超长的极限输入")
    void extremeInput_handlesGracefully() {
        String extreme = "C:\\Users\\Test/Docs: \"file\" <important> *really?* This.is.a.very.long.name.......   ";
        String result = buildDirectoryName(extreme, "extreme-key");
        assertThat(result).isNotNull()
                .doesNotContain("\\", "/", ":", "\"", "<", ">", "*", "?")
                .doesNotContain("  ");
        // 追加验证不以点号或空格结尾
        String namePart = result.substring(0, result.lastIndexOf('-'));
        assertThat(namePart).doesNotMatch("[. ]$");
    }

    // ---- UUID 后缀 ----

    @Test
    @DisplayName("相同的 rawKey 产生相同的 UUID 后缀")
    void sameRawKey_producesSameSuffix() {
        String r1 = buildDirectoryName("World", "same-key");
        String r2 = buildDirectoryName("World", "same-key");
        assertThat(r1).isEqualTo(r2);
    }

    @Test
    @DisplayName("不同的 rawKey 很可能产生不同的 UUID 后缀")
    void differentRawKey_producesDifferentResult() {
        String r1 = buildDirectoryName("World", "key-a");
        String r2 = buildDirectoryName("World", "key-b");
        assertThat(r1).isNotEqualTo(r2);
    }

    @Test
    @DisplayName("UUID 后缀是8位十六进制")
    void suffixIs8CharHex() {
        String result = buildDirectoryName("World", "any-key");
        String suffix = result.substring(result.lastIndexOf('-') + 1);
        assertThat(suffix).hasSize(8)
                .matches("[0-9a-f]{8}");
    }

    // ---- 一致性 ----

    @ParameterizedTest(name = "displayName=\"{0}\", rawKey=\"{1}\" 总是可用作目录名")
    @CsvSource({
            "SimpleWorld, saves/SimpleWorld",
            "Mundo Español, saves/mundo_espanol",
            "日本語ワールド, saves/jp_world",
            "Test: Server, servers/test.example.com:25565",
    })
    @DisplayName("合法文件名：不包含任何非法字符")
    void resultIsAlwaysValidFilename(String displayName, String rawKey) {
        String result = buildDirectoryName(displayName, rawKey);
        assertThat(result)
                .isNotNull()
                .isNotEmpty()
                .doesNotContain("\\", "/", ":", "*", "?", "\"", "<", ">", "|");
    }

    @ParameterizedTest(name = "displayName={0} → 产生非空合法结果")
    @ValueSource(strings = {
            "JustANormalWorld",
            "My World",
            "special:chars",
            "énfánt"
    })
    @DisplayName("各种 displayName 都产生合法文件名")
    void variousDisplayNames_produceValidFilenames(String displayName) {
        String result = buildDirectoryName(displayName, "test-key");
        assertThat(result).isNotNull().isNotEmpty()
                .doesNotContain("\\", "/", ":", "*", "?", "\"", "<", ">", "|");
    }
}
