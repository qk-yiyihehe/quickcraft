# QuickCraft

[![许可证](https://img.shields.io/github/license/qk-yiyihehe/quickcraft?style=flat-square&label=license&color=6B7280)](LICENSE) [![Modrinth 下载量](https://img.shields.io/modrinth/dt/totNXL64?style=flat-square&logo=modrinth&label=Modrinth%20downloads&color=1BD96A)](https://modrinth.com/mod/quickcraft-yiyihehe) [![GitHub 下载量](https://img.shields.io/github/downloads/qk-yiyihehe/quickcraft/total?style=flat-square&logo=github&label=GitHub%20downloads&color=24292F)](https://github.com/qk-yiyihehe/quickcraft/releases) [![Minecraft 版本](https://img.shields.io/badge/Minecraft-1.21--26.2-F97316?style=flat-square&logo=minecraft&logoColor=white)](https://github.com/qk-yiyihehe/quickcraft/branches) [![主力分支](https://img.shields.io/badge/branch-1.21--1.21.1-2563EB?style=flat-square&logo=git&logoColor=white)](https://github.com/qk-yiyihehe/quickcraft/tree/1.21-1.21.1)

中文 | [English](README_en.md)

QuickCraft 是面向 Fabric 的客户端实用模组，让合成、容器和 Litematica 投影操作更快、更顺手，支持 Minecraft 1.21-26.2。

## 依赖

<table>
  <thead>
    <tr><th>名称</th><th>类型</th><th>链接</th><th>说明</th></tr>
  </thead>
  <tbody>
    <tr><td nowrap>Fabric API</td><td nowrap>必需</td><td nowrap><a href="https://modrinth.com/mod/fabric-api">Modrinth</a></td><td>Fabric 模组运行所需的 API。</td></tr>
    <tr><td nowrap>MaLiLib</td><td nowrap>必需</td><td nowrap><a href="https://modrinth.com/mod/malilib">Modrinth</a>&nbsp;|&nbsp;<a href="https://github.com/maruohon/malilib">GitHub</a></td><td>QuickCraft 的配置、热键与基础功能依赖。</td></tr>
    <tr><td nowrap>Litematica</td><td nowrap>可选</td><td nowrap><a href="https://modrinth.com/mod/litematica">Modrinth</a>&nbsp;|&nbsp;<a href="https://github.com/maruohon/litematica">GitHub</a></td><td>启用投影预览、材料统计、容器校验、填充和轻松放置等投影辅助功能。</td></tr>
    <tr><td nowrap>Quick Shulker</td><td nowrap>可选</td><td nowrap><a href="https://modrinth.com/mod/quick-shulker">Modrinth</a>&nbsp;|&nbsp;<a href="https://github.com/MoRanpcy/quickshulker">GitHub</a></td><td>启用与快捷潜影盒的联动支持。</td></tr>
  </tbody>
</table>

## 版本支持

| 游戏版本 | 开发状态 | 分支 |
| --- | --- | --- |
| 1.21-1.21.1 | 主力维护 | [`1.21-1.21.1`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21-1.21.1) |
| 1.21.2-1.21.3 | 维护中 | [`1.21.2-1.21.3`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.2-1.21.3) |
| 1.21.4 | 维护中 | [`1.21.4`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.4) |
| 1.21.5 | 维护中 | [`1.21.5`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.5) |
| 1.21.6-1.21.8 | 维护中 | [`1.21.6-1.21.8`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.6-1.21.8) |
| 1.21.9-1.21.10 | 维护中 | [`1.21.9-1.21.10`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.9-1.21.10) |
| 1.21.11 | 维护中 | [`1.21.11`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.11) |
| 26.1-26.1.2 | 维护中 | [`26.1-26.1.2`](https://github.com/qk-yiyihehe/quickcraft/tree/26.1-26.1.2) |
| 26.2 | 维护中 | [`26.2`](https://github.com/qk-yiyihehe/quickcraft/tree/26.2) |

## 兼容性

<table>
  <thead>
    <tr><th>模组</th><th>结论</th><th>说明</th></tr>
  </thead>
  <tbody>
    <tr><td nowrap><a href="https://github.com/Kikugie/techutils">Tech Utils</a></td><td nowrap>不兼容</td><td>与所有版本的 Tech Utils 存在冲突，请勿同时安装。</td></tr>
    <tr><td nowrap><a href="https://modrinth.com/mod/inventory-profiles-next">Inventory Profiles Next (IPN)</a> + <a href="https://modrinth.com/mod/item-scroller">Item Scroller</a></td><td nowrap>不推荐</td><td>不建议与 QuickCraft 同时安装，以避免容器操作与快捷键功能重叠。</td></tr>
  </tbody>
</table>

## 文档

详细功能说明正在整理中。文档入口已预留在 [`doc/`](doc/README.md) 目录，后续内容会从这里维护。

## 开发

项目使用 Java 21。Windows 下可执行：

```powershell
.\gradlew.bat build
```

## 许可证

本项目采用 [MIT License](LICENSE)。

## 致谢

- [Tech Utils](https://github.com/Kikugie/techutils)：投影与实用功能的实现参考。
- [Item Scroller](https://github.com/maruohon/itemscroller)：物品栏操作设计参考。
- [MaLiLib](https://github.com/maruohon/malilib) 与 [Litematica](https://github.com/maruohon/litematica)：提供核心依赖和投影生态支持。

## GitHub Star 增长趋势

## Star History

<a href="https://www.star-history.com/?repos=qk-yiyihehe%2Fquickcraft&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=qk-yiyihehe/quickcraft&type=date&theme=dark&legend=top-left&sealed_token=uRylLK-r81ruivT-wIM6YqnkElmPuVzhFDI1krAHRnemmv7O0WwKw95C8gkhzjJbs8JKIePSq35e1MpCnUKm9yc9qSn64P_z_UajQ-GVidt69OFzYgo4-g" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=qk-yiyihehe/quickcraft&type=date&legend=top-left&sealed_token=uRylLK-r81ruivT-wIM6YqnkElmPuVzhFDI1krAHRnemmv7O0WwKw95C8gkhzjJbs8JKIePSq35e1MpCnUKm9yc9qSn64P_z_UajQ-GVidt69OFzYgo4-g" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=qk-yiyihehe/quickcraft&type=date&legend=top-left&sealed_token=uRylLK-r81ruivT-wIM6YqnkElmPuVzhFDI1krAHRnemmv7O0WwKw95C8gkhzjJbs8JKIePSq35e1MpCnUKm9yc9qSn64P_z_UajQ-GVidt69OFzYgo4-g" />
 </picture>
</a>
