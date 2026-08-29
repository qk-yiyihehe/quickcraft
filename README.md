# QuickCraft

QuickCraft 让 Minecraft 的合成、容器和投影操作更快更顺手。

> 当前项目处于开发阶段。功能、配置和兼容性仍可能调整，请在备份存档后使用。

## 功能

- 合成辅助：工作台、背包、切石机和铁砧的快捷操作。
- 容器工具：快速转移、整理、丢弃、交易、回存、复制、清空和容器锁定等操作。
- 投影辅助：Litematica 原理图预览、区域复制、容器材料统计、校验、填充和轻松放置辅助。
- 多版本维护：为不同 Minecraft 版本线分别适配 Minecraft、Fabric、MaLiLib 与 Litematica API。

## 支持版本

- Minecraft 1.21-1.21.1
- Minecraft 1.21.2-1.21.3
- Minecraft 1.21.4
- Minecraft 1.21.5
- Minecraft 1.21.6-1.21.8
- Minecraft 1.21.9-1.21.10
- Minecraft 1.21.11
- Minecraft 26.1-26.1.2
- Minecraft 26.2

每条版本线使用对应的 Fabric Loader、Fabric API、MaLiLib 和 Litematica 版本。Litematica 仅在使用投影辅助功能时需要，具体最低版本和兼容范围以各版本 `fabric.mod.json` 为准。

## 开发

项目使用 Java 21。Windows 下可执行：

```powershell
.\gradlew.bat build
```
