package com.yiyihehe.quickcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuickCraftNoticeTest {
    private static final String NOTICE = "<!-- quickcraft-notice\n"
            + "{\"id\":\"update-1.0.4\",\"enabled\":true,\"message\":{\"zh_cn\":\"去 PCL 更新\","
            + "\"en_us\":\"Update on Modrinth\"}}\n-->";

    @Test
    void onlyExplicitShortNoticeIsShownAndLanguagesStaySeparate() {
        var notice = select(release("1.0.4+mc1.21", "长日志".repeat(2000) + NOTICE), "1.0.3");
        assertThat(notice).isNotNull();
        assertThat(notice.message("zh_cn")).isEqualTo("去 PCL 更新");
        assertThat(notice.message("en_us")).isEqualTo("Update on Modrinth");
        assertThat(notice.message("de_de")).isEqualTo("Update on Modrinth");
        assertThat(notice.version()).isEqualTo("1.0.4");
        assertThat(notice.url()).isEqualTo("https://modrinth.com/mod/totNXL64/version/abcdefgh");
        assertThat(select(release("1.0.4", "普通长日志"), "1.0.3")).isNull();
        assertThat(select(release("1.0.4", NOTICE.replace("true", "false")), "1.0.3")).isNull();
        assertThat(select(release("1.0.4", "<!-- quickcraft-notice {broken} -->"), "1.0.3")).isNull();
    }

    @Test
    void comparesNumericVersionsAndDoesNotSuggestDowngrades() {
        assertThat(select(release("1.0.10+mc1.21", NOTICE), "1.0.9")).isNotNull();
        assertThat(select(release("1.0.4+mc1.21-1.21.1", NOTICE), "1.0.4")).isNull();
        assertThat(select(release("1.0.3", NOTICE), "1.0.4")).isNull();
    }

    @Test
    void unannouncedLatestReleaseSuppressesOlderNoticeAndIgnoresIncompatibleReleases() {
        JsonArray releases = new JsonArray();
        releases.add(release("1.0.4", NOTICE));
        releases.add(release("1.0.5", "未选择推送"));
        assertThat(QuickCraftNotice.select(releases, "1.21", "1.0.3")).isNull();

        releases.remove(1);
        JsonObject incompatible = release("2.0.0", NOTICE);
        incompatible.add("game_versions", JsonParser.parseString("[\"26.3\"]"));
        releases.add(incompatible);
        JsonObject alpha = release("3.0.0", "无通知");
        alpha.addProperty("version_type", "alpha");
        releases.add(alpha);
        assertThat(QuickCraftNotice.select(releases, "1.21", "1.0.3").version()).isEqualTo("1.0.4");
        incompatible.add("game_versions", JsonParser.parseString("[\"1.21\"]"));
        incompatible.add("loaders", JsonParser.parseString("[\"neoforge\"]"));
        assertThat(QuickCraftNotice.select(releases, "1.21", "1.0.3").version()).isEqualTo("1.0.4");
    }

    @Test
    void announcementWorksOnInstalledVersionAndMessageIsPlainAndBounded() {
        JsonObject release = release("1.0.4", "");
        JsonObject payload = new JsonObject();
        payload.addProperty("id", "announcement-1");
        payload.addProperty("type", "announcement");
        payload.addProperty("enabled", true);
        payload.addProperty("message", "§\n" + "😀".repeat(110));
        release.addProperty("changelog", "<!-- quickcraft-notice\n" + payload + "\n-->");
        var notice = select(release, "1.0.4");
        assertThat(notice.announcement()).isTrue();
        assertThat(notice.message("en_us")).doesNotContain("§", "\n").endsWith("…");
        assertThat(notice.englishMessage().codePointCount(0, notice.englishMessage().length())).isEqualTo(100);
    }

    private static QuickCraftNotice select(JsonObject release, String installed) {
        JsonArray releases = new JsonArray();
        releases.add(release);
        return QuickCraftNotice.select(releases, "1.21", installed);
    }

    private static JsonObject release(String version, String changelog) {
        JsonObject release = new JsonObject();
        release.addProperty("id", "abcdefgh");
        release.addProperty("project_id", "totNXL64");
        release.addProperty("version_number", version);
        release.addProperty("version_type", "beta");
        release.addProperty("status", "listed");
        release.addProperty("date_published", "2026-10-07T00:00:00Z");
        release.add("game_versions", JsonParser.parseString("[\"1.21\"]"));
        release.add("loaders", JsonParser.parseString("[\"fabric\"]"));
        release.addProperty("changelog", changelog);
        return release;
    }
}
