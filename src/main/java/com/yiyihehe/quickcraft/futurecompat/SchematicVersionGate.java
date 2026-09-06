package com.yiyihehe.quickcraft.futurecompat;

/**
 * 版本探测门（纯函数）：按方案 v2.1 §3 判定 .litematic 是否进入映射流程。
 * 判定依据已对 Litematica LTS/1.21.8 源码核实：本体只校验 Version ∈ [1, SCHEMATIC_VERSION]，
 * SubVersion 只写不读不参与判定；MinecraftDataVersion 高于当前即"未来版本"。
 */
public record SchematicVersionGate(int minSchematicVersion, int maxSchematicVersion, int currentDataVersion) {
    public enum ProbeDecision {
        /** 普通文件，不提示不扫描 */
        NOT_FUTURE,
        /** 未来版本：进入扫描 */
        FUTURE,
        /** Schema 超出当前 Litematica 支持范围：提示升级，改 id 也救不了 */
        SCHEMA_UNSUPPORTED
    }

    /** 运行时门：阈值取自 Litematica 公开常量。仅在游戏内调用（类初始化依赖 vanilla 常量）。 */
    public static SchematicVersionGate vanilla() {
        return new SchematicVersionGate(
                1,
                fi.dy.masa.litematica.schematic.LitematicaSchematic.SCHEMATIC_VERSION,
                fi.dy.masa.litematica.schematic.LitematicaSchematic.MINECRAFT_DATA_VERSION);
    }

    /** @param schematicVersion 根节点 Version；缺失传 -1。@param dataVersion 根节点 MinecraftDataVersion；缺失传 0。 */
    public ProbeDecision classify(int schematicVersion, int dataVersion) {
        if (schematicVersion < this.minSchematicVersion || schematicVersion > this.maxSchematicVersion) {
            return ProbeDecision.SCHEMA_UNSUPPORTED;
        }
        return dataVersion > this.currentDataVersion ? ProbeDecision.FUTURE : ProbeDecision.NOT_FUTURE;
    }
}
