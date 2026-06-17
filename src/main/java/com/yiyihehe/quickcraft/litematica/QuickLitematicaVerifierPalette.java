package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;

/**
 * QuickCraft 容器验证的统一配色入口。
 * 这里的基色直接对应世界里容器错填方块的高亮颜色，其他 HUD、槽位和悬浮预览颜色都从这里派生。
 */
public final class QuickLitematicaVerifierPalette {
    private static final String TXT_WHITE = "\u00a7f";
    private static final String TXT_BOLD = "\u00a7l";
    private static final String TXT_RED = "\u00a7c";
    private static final String TXT_BLUE = "\u00a79";
    private static final String TXT_PURPLE = "\u00a7d";
    private static final String TXT_GOLD = "\u00a76";
    private static final String TXT_RST = "\u00a7r";

    private static final Tone WRONG_FILL = new Tone(0xFF1744, TXT_RED);
    private static final Tone MISSING_FILL = new Tone(0x2979FF, TXT_BLUE);
    private static final Tone EXTRA_FILL = new Tone(0xD500F9, TXT_PURPLE);
    private static final Tone WRONG_FILL_STATE = new Tone(0xFF9100, TXT_GOLD);

    private static final int SLOT_FILL_ALPHA = 0x48;
    private static final int SLOT_BORDER_ALPHA = 0xD8;
    private static final int GHOST_MASK_ALPHA = 0x78;
    private static final float BORDER_LIGHTEN = 0.45F;
    private static final float MASK_LIGHTEN = 0.78F;
    private static final float GHOST_ITEM_ALPHA = 0.50F;

    private QuickLitematicaVerifierPalette() {
    }

    public static int wrongFillRgb() {
        return WRONG_FILL.rgb();
    }

    public static int missingFillRgb() {
        return MISSING_FILL.rgb();
    }

    public static int extraFillRgb() {
        return EXTRA_FILL.rgb();
    }

    public static int wrongFillStateRgb() {
        return WRONG_FILL_STATE.rgb();
    }

    public static String wrongFillFormattingCode() {
        return WRONG_FILL.formattingCode();
    }

    public static String missingFillFormattingCode() {
        return MISSING_FILL.formattingCode();
    }

    public static String extraFillFormattingCode() {
        return EXTRA_FILL.formattingCode();
    }

    public static String wrongFillStateFormattingCode() {
        return WRONG_FILL_STATE.formattingCode();
    }

    public static String formattingCode(MismatchType type) {
        return tone(type).formattingCode();
    }

    public static String formatSectionTitle(String title) {
        return TXT_WHITE + TXT_BOLD + title + TXT_RST;
    }

    public static int slotFillColor(MismatchType type) {
        return tone(type).slotFillColor();
    }

    public static int slotBorderColor(MismatchType type) {
        return tone(type).slotBorderColor();
    }

    public static int ghostMaskColor(MismatchType type) {
        return tone(type).ghostMaskColor();
    }

    public static float ghostItemAlpha() {
        return GHOST_ITEM_ALPHA;
    }

    private static Tone tone(MismatchType type) {
        if (type == QuickLitematicaContainerVerifier.MISSING_FILL) {
            return MISSING_FILL;
        }
        if (type == QuickLitematicaContainerVerifier.EXTRA_FILL) {
            return EXTRA_FILL;
        }
        if (type == QuickLitematicaContainerVerifier.WRONG_FILL_STATE) {
            return WRONG_FILL_STATE;
        }

        return WRONG_FILL;
    }

    private static int withAlpha(int rgb, int alpha) {
        return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int mixTowardWhite(int rgb, float amount) {
        amount = Math.max(0.0F, Math.min(1.0F, amount));
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        int mixedR = r + Math.round((255 - r) * amount);
        int mixedG = g + Math.round((255 - g) * amount);
        int mixedB = b + Math.round((255 - b) * amount);

        return (mixedR << 16) | (mixedG << 8) | mixedB;
    }

    private record Tone(int rgb, String formattingCode) {
        private int slotFillColor() {
            return withAlpha(this.rgb, SLOT_FILL_ALPHA);
        }

        private int slotBorderColor() {
            return withAlpha(mixTowardWhite(this.rgb, BORDER_LIGHTEN), SLOT_BORDER_ALPHA);
        }

        private int ghostMaskColor() {
            return withAlpha(mixTowardWhite(this.rgb, MASK_LIGHTEN), GHOST_MASK_ALPHA);
        }
    }
}
