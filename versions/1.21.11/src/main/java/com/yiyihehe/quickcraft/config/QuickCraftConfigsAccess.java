package com.yiyihehe.quickcraft.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

import java.nio.file.Path;

final class QuickCraftConfigsAccess {
    private QuickCraftConfigsAccess() {
    }

    static Path getConfigDirectory() {
        return FileUtils.getConfigDirectory();
    }

    static JsonElement parseJsonFile(Path path) {
        return JsonUtils.parseJsonFile(path);
    }

    static void writeJsonToFile(JsonObject root, Path path) {
        JsonUtils.writeJsonToFile(root, path);
    }
}
