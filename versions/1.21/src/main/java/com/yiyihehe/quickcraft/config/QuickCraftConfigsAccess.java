package com.yiyihehe.quickcraft.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;

import java.nio.file.Path;

final class QuickCraftConfigsAccess {
    private QuickCraftConfigsAccess() {
    }

    static Path getConfigDirectory() {
        return FileUtils.getConfigDirectoryAsPath();
    }

    static JsonElement parseJsonFile(Path path) {
        return JsonUtils.parseJsonFileAsPath(path);
    }

    static void writeJsonToFile(JsonObject root, Path path) {
        JsonUtils.writeJsonToFileAsPath(root, path);
    }
}
