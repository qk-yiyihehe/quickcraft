package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

final class QuickLitematicaPreviewImageWriterAccess {
    private QuickLitematicaPreviewImageWriterAccess() {
    }

    @Nullable
    static LitematicaSchematic readSchematic(Path directory, String fileName) {
        return LitematicaSchematic.createFromFile(directory.toFile(), fileName);
    }

    static boolean writeSchematic(LitematicaSchematic schematic, Path directory, String fileName, boolean override) {
        return schematic.writeToFile(directory.toFile(), fileName, override);
    }
}
