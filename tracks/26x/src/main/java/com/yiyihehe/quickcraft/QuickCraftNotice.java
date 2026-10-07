package com.yiyihehe.quickcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.VersionParsingException;

import java.time.Instant;

/** Modrinth 发布筛选与短通知解析，不读取完整日志作为玩家提示。 */
record QuickCraftNotice(String id, String version, boolean announcement,
                        String chineseMessage, String englishMessage, String url) {
    private static final String MARKER = "<!-- quickcraft-notice";

    static QuickCraftNotice select(JsonArray releases, String minecraft, String installed) {
        JsonObject latest = null;
        SemanticVersion latestVersion = null;
        Instant latestDate = Instant.MIN;
        for (JsonElement element : releases) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject release = element.getAsJsonObject();
            if (!"totNXL64".equals(string(release, "project_id"))
                    || !"listed".equals(string(release, "status"))
                    || !("release".equals(string(release, "version_type"))
                            || "beta".equals(string(release, "version_type")))
                    || !contains(release.get("loaders"), "fabric")
                    || !contains(release.get("game_versions"), minecraft)) {
                continue;
            }
            try {
                SemanticVersion version = SemanticVersion.parse(string(release, "version_number"));
                Instant date = Instant.parse(string(release, "date_published"));
                if (latestVersion == null || version.compareTo(latestVersion) > 0
                        || version.compareTo(latestVersion) == 0 && date.isAfter(latestDate)) {
                    latest = release;
                    latestVersion = version;
                    latestDate = date;
                }
            } catch (VersionParsingException | IllegalArgumentException | java.time.DateTimeException ignored) {
                // 无法确认版本或发布时间的发布不参与选择。
            }
        }
        // 只读最新兼容发布的标记，移除或禁用它时不回退到旧通知。
        if (latest == null) {
            return null;
        }
        try {
            String changelog = string(latest, "changelog");
            int start = changelog.indexOf(MARKER);
            int end = start < 0 ? -1 : changelog.indexOf("-->", start + MARKER.length());
            if (end < 0 || end - start > 4096) {
                return null;
            }
            JsonObject notice = JsonParser.parseString(
                    changelog.substring(start + MARKER.length(), end).strip()).getAsJsonObject();
            JsonElement enabled = notice.get("enabled");
            String id = string(notice, "id");
            String releaseId = string(latest, "id");
            if (enabled == null || !enabled.isJsonPrimitive()
                    || !enabled.getAsJsonPrimitive().isBoolean() || !enabled.getAsBoolean()
                    || !id.matches("[A-Za-z0-9._-]{1,80}") || !releaseId.matches("[A-Za-z0-9]{8}")) {
                return null;
            }
            String type = string(notice, "type");
            boolean announcement = "announcement".equals(type);
            if (!type.isEmpty() && !"update".equals(type) && !announcement) {
                return null;
            }
            if (!announcement && latestVersion.compareTo(SemanticVersion.parse(installed)) <= 0) {
                return null;
            }
            JsonElement message = notice.get("message");
            String chinese = message != null && message.isJsonObject()
                    ? string(message.getAsJsonObject(), "zh_cn") : string(notice, "message");
            String english = message != null && message.isJsonObject()
                    ? string(message.getAsJsonObject(), "en_us") : string(notice, "message");
            chinese = shortMessage(chinese.isBlank() ? english : chinese);
            english = shortMessage(english.isBlank() ? chinese : english);
            if (chinese.isEmpty() || english.isEmpty()) {
                return null;
            }
            return new QuickCraftNotice(id, latestVersion.getFriendlyString().split("\\+", 2)[0],
                    announcement, chinese, english, "https://modrinth.com/mod/totNXL64/version/" + releaseId);
        } catch (RuntimeException | VersionParsingException ignored) {
            return null;
        }
    }

    String message(String language) {
        return language.startsWith("zh_") ? chineseMessage : englishMessage;
    }

    private static String shortMessage(String text) {
        String plain = text.replace('§', ' ').replaceAll("[\\p{Cc}\\s]+", " ").strip();
        int length = plain.codePointCount(0, plain.length());
        return length <= 100 ? plain : plain.substring(0, plain.offsetByCodePoints(0, 99)) + "…";
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }

    private static boolean contains(JsonElement value, String expected) {
        if (value != null && value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                        && expected.equals(element.getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }
}
