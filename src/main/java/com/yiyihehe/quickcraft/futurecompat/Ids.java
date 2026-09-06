package com.yiyihehe.quickcraft.futurecompat;

/**
 * id 归一化：旧版文件可能省略命名空间，比对与映射查找前统一补 minecraft: 前缀。
 * 含非法字符的 id（损坏/敌意文件）返回空串，调用方按"无法识别"跳过，不抛异常。
 */
final class Ids {
    private Ids() {
    }

    static String normalize(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String normalized = id.indexOf(':') < 0 ? "minecraft:" + id : id;
        return isValid(normalized) ? normalized : "";
    }

    private static boolean isValid(String id) {
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == ':' || c == '/';
            if (!valid) {
                return false;
            }
        }
        return true;
    }
}
