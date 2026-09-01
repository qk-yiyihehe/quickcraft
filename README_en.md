# QuickCraft

[![License](https://img.shields.io/github/license/qk-yiyihehe/quickcraft?style=flat-square&label=license&color=6B7280)](LICENSE) [![Modrinth downloads](https://img.shields.io/modrinth/dt/totNXL64?style=flat-square&logo=modrinth&label=Modrinth%20downloads&color=1BD96A)](https://modrinth.com/mod/quickcraft-yiyihehe) [![GitHub downloads](https://img.shields.io/github/downloads/qk-yiyihehe/quickcraft/total?style=flat-square&logo=github&label=GitHub%20downloads&color=24292F)](https://github.com/qk-yiyihehe/quickcraft/releases) [![Minecraft version](https://img.shields.io/badge/Minecraft-1.21--26.2-F97316?style=flat-square&logo=minecraft&logoColor=white)](https://github.com/qk-yiyihehe/quickcraft/branches) [![Primary branch](https://img.shields.io/badge/branch-1.21--1.21.1-2563EB?style=flat-square&logo=git&logoColor=white)](https://github.com/qk-yiyihehe/quickcraft/tree/1.21-1.21.1)

[中文](README.md) | English

QuickCraft is a Fabric client-side utility mod that makes crafting, container management, and Litematica workflows faster and easier to use, with support for Minecraft 1.21-26.2.

## Dependencies

<table>
  <thead>
    <tr><th>Name</th><th>Type</th><th>Links</th><th>Notes</th></tr>
  </thead>
  <tbody>
    <tr><td nowrap>Fabric API</td><td nowrap>Required</td><td nowrap><a href="https://modrinth.com/mod/fabric-api">Modrinth</a></td><td>Required Fabric mod API.</td></tr>
    <tr><td nowrap>MaLiLib</td><td nowrap>Required</td><td nowrap><a href="https://modrinth.com/mod/malilib">Modrinth</a>&nbsp;|&nbsp;<a href="https://github.com/maruohon/malilib">GitHub</a></td><td>Required for QuickCraft configuration, hotkeys, and core features.</td></tr>
    <tr><td nowrap>Litematica</td><td nowrap>Optional</td><td nowrap><a href="https://modrinth.com/mod/litematica">Modrinth</a>&nbsp;|&nbsp;<a href="https://github.com/maruohon/litematica">GitHub</a></td><td>Enables schematic preview, material statistics, container verification, filling, and easy-place helpers.</td></tr>
    <tr><td nowrap>Quick Shulker</td><td nowrap>Optional</td><td nowrap><a href="https://modrinth.com/mod/quick-shulker">Modrinth</a>&nbsp;|&nbsp;<a href="https://github.com/MoRanpcy/quickshulker">GitHub</a></td><td>Enables Quick Shulker integration.</td></tr>
  </tbody>
</table>

## Version Support

| Minecraft version | Status | Branch |
| --- | --- | --- |
| 1.21-1.21.1 | Primary maintenance line | [`1.21-1.21.1`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21-1.21.1) |
| 1.21.2-1.21.3 | Maintained | [`1.21.2-1.21.3`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.2-1.21.3) |
| 1.21.4 | Maintained | [`1.21.4`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.4) |
| 1.21.5 | Maintained | [`1.21.5`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.5) |
| 1.21.6-1.21.8 | Maintained | [`1.21.6-1.21.8`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.6-1.21.8) |
| 1.21.9-1.21.10 | Maintained | [`1.21.9-1.21.10`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.9-1.21.10) |
| 1.21.11 | Maintained | [`1.21.11`](https://github.com/qk-yiyihehe/quickcraft/tree/1.21.11) |
| 26.1-26.1.2 | Maintained | [`26.1-26.1.2`](https://github.com/qk-yiyihehe/quickcraft/tree/26.1-26.1.2) |
| 26.2 | Maintained | [`26.2`](https://github.com/qk-yiyihehe/quickcraft/tree/26.2) |

## Compatibility

| Mod | Status | Notes |
| --- | --- | --- |
| [Tech Utils](https://github.com/Kikugie/techutils) | Incompatible | Conflicts with every version of Tech Utils; do not install both mods. |
| [Inventory Profiles Next (IPN)](https://modrinth.com/mod/inventory-profiles-next) + [Item Scroller](https://modrinth.com/mod/item-scroller) | Not recommended | Do not use with QuickCraft at the same time, to avoid overlapping container actions and hotkeys. |

## Documentation

Detailed feature documentation is being prepared. The entry point is reserved in [`doc/`](doc/README_en.md).

## Development

The project uses Java 21. On Windows, run:

```powershell
.\gradlew.bat build
```

## License

This project is licensed under the [MIT License](LICENSE).

## Acknowledgements

- [Tech Utils](https://github.com/Kikugie/techutils): reference for schematic and utility features.
- [Item Scroller](https://github.com/maruohon/itemscroller): reference for inventory interaction design.
- [MaLiLib](https://github.com/maruohon/malilib) and [Litematica](https://github.com/maruohon/litematica): core dependencies and schematic ecosystem support.

## GitHub Star Growth

<a href="https://www.star-history.com/?repos=qk-yiyihehe%2Fquickcraft&amp;type=date&amp;legend=top-left">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=qk-yiyihehe/quickcraft&amp;type=date&amp;theme=dark&amp;legend=top-left&amp;sealed_token=Yox50aNbXJxvwqV5q6WmujEzZD5698jvCHAeDBqJBQ7X2MfA04wkvWUFUFfCtrtI109G7EhpITm7jq__pwqzQZ52ZzWJzg0pqC2CNA-7dwkY-df2xv7gmQ">
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=qk-yiyihehe/quickcraft&amp;type=date&amp;legend=top-left&amp;sealed_token=Yox50aNbXJxvwqV5q6WmujEzZD5698jvCHAeDBqJBQ7X2MfA04wkvWUFUFfCtrtI109G7EhpITm7jq__pwqzQZ52ZzWJzg0pqC2CNA-7dwkY-df2xv7gmQ">
    <img alt="QuickCraft GitHub Star Growth" src="https://api.star-history.com/chart?repos=qk-yiyihehe/quickcraft&amp;type=date&amp;legend=top-left&amp;sealed_token=Yox50aNbXJxvwqV5q6WmujEzZD5698jvCHAeDBqJBQ7X2MfA04wkvWUFUFfCtrtI109G7EhpITm7jq__pwqzQZ52ZzWJzg0pqC2CNA-7dwkY-df2xv7gmQ">
  </picture>
</a>
