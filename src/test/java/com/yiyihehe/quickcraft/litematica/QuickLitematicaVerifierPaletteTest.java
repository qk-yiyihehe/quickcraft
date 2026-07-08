package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuickLitematicaVerifierPalette 纯数学/颜色计算单元测试。
 * 测试不需要 MismatchType 参数的方法，零Minecraft依赖。
 */
class QuickLitematicaVerifierPaletteTest {

    // ---- 基色常量测试 ----

    @Test
    @DisplayName("wrongFillRgb = 0xFF1744 (红色)")
    void wrongFillRgb() {
        assertThat(QuickLitematicaVerifierPalette.wrongFillRgb()).isEqualTo(0xFF1744);
    }

    @Test
    @DisplayName("missingFillRgb = 0x2979FF (蓝色)")
    void missingFillRgb() {
        assertThat(QuickLitematicaVerifierPalette.missingFillRgb()).isEqualTo(0x2979FF);
    }

    @Test
    @DisplayName("extraFillRgb = 0xD500F9 (紫色)")
    void extraFillRgb() {
        assertThat(QuickLitematicaVerifierPalette.extraFillRgb()).isEqualTo(0xD500F9);
    }

    @Test
    @DisplayName("wrongFillStateRgb = 0xFF9100 (橙色)")
    void wrongFillStateRgb() {
        assertThat(QuickLitematicaVerifierPalette.wrongFillStateRgb()).isEqualTo(0xFF9100);
    }

    @Test
    @DisplayName("基色唯一的，互不重复")
    void baseColorsAreUnique() {
        int[] colors = {
                QuickLitematicaVerifierPalette.wrongFillRgb(),
                QuickLitematicaVerifierPalette.missingFillRgb(),
                QuickLitematicaVerifierPalette.extraFillRgb(),
                QuickLitematicaVerifierPalette.wrongFillStateRgb()
        };
        assertThat(colors).doesNotHaveDuplicates();
    }

    // ---- 格式化代码测试 ----

    @Test
    @DisplayName("wrongFillFormattingCode = §c (红色)")
    void wrongFillFormattingCode() {
        assertThat(QuickLitematicaVerifierPalette.wrongFillFormattingCode()).isEqualTo("\u00a7c");
    }

    @Test
    @DisplayName("missingFillFormattingCode = §9 (蓝色)")
    void missingFillFormattingCode() {
        assertThat(QuickLitematicaVerifierPalette.missingFillFormattingCode()).isEqualTo("\u00a79");
    }

    @Test
    @DisplayName("extraFillFormattingCode = §d (紫色)")
    void extraFillFormattingCode() {
        assertThat(QuickLitematicaVerifierPalette.extraFillFormattingCode()).isEqualTo("\u00a7d");
    }

    @Test
    @DisplayName("wrongFillStateFormattingCode = §6 (金色)")
    void wrongFillStateFormattingCode() {
        assertThat(QuickLitematicaVerifierPalette.wrongFillStateFormattingCode()).isEqualTo("\u00a76");
    }

    @Test
    @DisplayName("格式化代码唯一，互不重复")
    void formattingCodesAreUnique() {
        String[] codes = {
                QuickLitematicaVerifierPalette.wrongFillFormattingCode(),
                QuickLitematicaVerifierPalette.missingFillFormattingCode(),
                QuickLitematicaVerifierPalette.extraFillFormattingCode(),
                QuickLitematicaVerifierPalette.wrongFillStateFormattingCode()
        };
        assertThat(codes).doesNotHaveDuplicates();
    }

    // ---- ghostItemAlpha ----

    @Test
    @DisplayName("ghostItemAlpha = 0.30F")
    void ghostItemAlpha() {
        assertThat(QuickLitematicaVerifierPalette.ghostItemAlpha()).isEqualTo(0.30F);
    }

    // ---- formatSectionTitle ----

    @Test
    @DisplayName("formatSectionTitle 用白色加粗包裹标题")
    void formatSectionTitle_wrapsWithWhiteBold() {
        assertThat(QuickLitematicaVerifierPalette.formatSectionTitle("容器验证"))
                .isEqualTo("\u00a7f\u00a7l容器验证\u00a7r");
    }

    @Test
    @DisplayName("formatSectionTitle 处理空字符串")
    void formatSectionTitle_handlesEmptyString() {
        assertThat(QuickLitematicaVerifierPalette.formatSectionTitle(""))
                .isEqualTo("\u00a7f\u00a7l\u00a7r");
    }

    @Test
    @DisplayName("formatSectionTitle 结果以 §r 结尾")
    void formatSectionTitle_endsWithReset() {
        assertThat(QuickLitematicaVerifierPalette.formatSectionTitle("任意标题"))
                .endsWith("\u00a7r");
    }

    @Test
    @DisplayName("formatSectionTitle 结果以 §f§l 开头")
    void formatSectionTitle_startsWithWhiteBold() {
        assertThat(QuickLitematicaVerifierPalette.formatSectionTitle("任意标题"))
                .startsWith("\u00a7f\u00a7l");
    }

    @ParameterizedTest(name = "formatSectionTitle(\"{0}\")")
    @ValueSource(strings = {"A", "验证", "Container Verification", "很长的标题文本用来测试格式化函数"})
    @DisplayName("formatSectionTitle 对多种标题均不抛出异常")
    void formatSectionTitle_variousInputs(String title) {
        String result = QuickLitematicaVerifierPalette.formatSectionTitle(title);
        assertThat(result).isNotNull()
                .startsWith("\u00a7f\u00a7l")
                .endsWith("\u00a7r");
    }
}
