package com.yiyihehe.quickcraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftLanguageFormatTest {
    private static final String KEY = "quickcraft.config.container_tools.comment.matchingTransferHighlightColor";

    @Test
    void highlightCommentFormatsAsOnePercent() throws Exception {
        assertFormatted("en_us.json", "33% opacity", "33%");
        assertFormatted("zh_cn.json", "33% 不透明", "33%");
    }

    private static void assertFormatted(String file, String expectedMeaning, String percent) throws Exception {
        var stream = QuickCraftLanguageFormatTest.class.getClassLoader().getResourceAsStream(
                "assets/quickcraft/lang/" + file
        );
        assertThat(stream).isNotNull();
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            String raw = json.get(KEY).getAsString();
            String formatted = String.format(Locale.ROOT, raw);
            assertThat(formatted).contains(expectedMeaning).contains(percent);
            assertThat(formatted).doesNotContain("Format error", "Format Error");
        }
    }
}
