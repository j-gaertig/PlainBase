# <p align="center">PlainBase</p>
<p align="center">
  <a href="https://papermc.io"><img src="https://img.shields.io/badge/Platform-Paper%20%7C%20Purpur%20%7C%20Folia-blue.svg" alt="Platform"></a>
  <a href="https://modrinth.com/plugin/plainbase"><img src="https://img.shields.io/badge/Minecraft-1.21.6%20--%2026.3-3fb58e?style=flat&logo=minecraft&logoColor=white" alt="Minecraft versions"></a>
  <a href="https://github.com/j-gaertig/PlainBase/releases/latest"><img src="https://img.shields.io/github/v/tag/j-gaertig/PlainBase?label=Version&color=orange" alt="Latest version"></a>
  <a href="https://github.com/j-gaertig/PlainBase/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License"></a>
</p>
<p align="center">
  <strong>PlainBase</strong> is a modular core plugin for Minecraft servers.
  <br>
  Enable the features your server needs and configure them in one place.
</p>

<!-- RELEASE-SYNC:START -->
> **Latest release:** [0.3.0-Beta](https://github.com/j-gaertig/PlainBase/releases/tag/0.3.0-Beta) | 2026-10-09 | [Modrinth](https://modrinth.com/project/plainbase/version/b1B7eoWJ) | [Hangar](https://hangar.papermc.io/j-gaertig/PlainBase/versions/0.3.0-Beta)
<!-- RELEASE-SYNC:END -->

---

## Features

PlainBase requires Paper APIs. It is marked as compatible with Paper, Purpur, and Folia. Module activation is controlled in `plugins/PlainBase/config.yml`. Modules are disabled by default.

| Module | Features |
| :--- | :--- |
| **Teleport** | Player-to-player requests, request controls, and random teleport to a safe location. |
| **Spawn** | Global spawn and a separate location for first-time joins. |
| **Join Items** | Configurable items with click commands and inventory protection options. |
| **Messages** | Join and quit messages, a join MOTD, and scheduled broadcasts. |
| **Vanish** | Hide players and configure visibility, collision, damage, and mob behavior. |
| **Menu** | Configure inventory menus and the actions available when items are clicked. |
| **Moderation** | Ban, tempban, unban, kick, and IP ban commands with SQLite or MySQL storage. |
| **Team** | Configured teams, member and admin roles, invitations, join requests, and scoreboard integration where supported. |

PlaceholderAPI is optional. When installed, PlainBase registers its `%plainbase_*%` expansion. See the [PlaceholderAPI documentation](https://github.com/j-gaertig/PlainBase/wiki/PlaceholderAPI) for the available placeholders.

The [GitHub Wiki](https://github.com/j-gaertig/PlainBase/wiki) documents installation, commands, permissions, and module settings.

Quick links: [Installation](https://github.com/j-gaertig/PlainBase/wiki/Installation) | [Configuration and Versioning](https://github.com/j-gaertig/PlainBase/wiki/Configuration-and-Versioning) | [Permissions](https://github.com/j-gaertig/PlainBase/wiki/Permissions) | [Commands](https://github.com/j-gaertig/PlainBase/wiki/Commands)

---

## Planned

- **Claims and warps**
- **Tablist and sidebar**

Plans may change as development continues.

---

## Support

Report bugs and request features through [GitHub Issues](https://github.com/j-gaertig/PlainBase/issues). You can also find PlainBase on [Modrinth](https://modrinth.com/plugin/plainbase) and [Hangar](https://hangar.papermc.io/j-gaertig/PlainBase).

---

<p align="center">Maintained by j-gaertig</p>
