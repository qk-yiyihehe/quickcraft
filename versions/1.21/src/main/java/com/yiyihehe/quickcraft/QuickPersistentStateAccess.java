package com.yiyihehe.quickcraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;

import java.nio.file.Path;

final class QuickPersistentStateAccess {
    private QuickPersistentStateAccess() {
    }

    static void writeJson(JsonObject root, Path stateFile) {
        JsonUtils.writeJsonToFileAsPath(root, stateFile);
    }

    static JsonElement parseJson(Path stateFile) {
        return JsonUtils.parseJsonFileAsPath(stateFile);
    }

    static Path configDirectory() {
        return FileUtils.getConfigDirectoryAsPath();
    }
}
