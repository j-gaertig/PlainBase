# PlainBase

PlainBase is a modular core plugin for Paper based servers. It includes spawn management, teleport requests, random teleport, join items, messages, menus, vanish, moderation, and configurable player teams.

The plugin metadata marks Folia as supported. PlaceholderAPI is optional and adds the `%plainbase_*%` expansion when installed. PlainBase requires Paper APIs and does not support standalone Bukkit or Spigot.

<!-- RELEASE-SYNC:START -->
> Latest release: [0.3.0-Beta](https://github.com/j-gaertig/PlainBase/releases/tag/0.3.0-Beta) (2026-10-09) | [Modrinth](https://modrinth.com/project/plainbase/version/b1B7eoWJ) | [Hangar](https://hangar.papermc.io/j-gaertig/PlainBase/versions/0.3.0-Beta)
<!-- RELEASE-SYNC:END -->

## Requirements

- Paper, Purpur, or Folia with Minecraft 1.21.6 or newer. The build currently targets Paper API 26.2 or newer.
- Java 25, as configured by the Maven build.

Check the release notes for the versions supported by a specific release.

## Install

1. Download the JAR from [GitHub Releases](https://github.com/j-gaertig/PlainBase/releases), [Modrinth](https://modrinth.com/plugin/plainbase), or [Hangar](https://hangar.papermc.io/j-gaertig/PlainBase).
2. Put it in the server's `plugins` directory and start the server.
3. Set the modules you want to use to `true` in `plugins/PlainBase/config.yml`. They are disabled by default.
4. Configure each enabled module in `plugins/PlainBase/modules/` and run `/plainbase reload`.

## Modules

| Module | Features |
| --- | --- |
| Spawn | Main spawn and first-join spawn locations |
| Teleport | TPA requests, auto-accept, and safe random teleport |
| Join Items | Configurable items with click commands and inventory protections |
| Messages | Join and quit messages, MOTD, scheduled broadcasts |
| Vanish | Player visibility controls and configurable vanish behavior |
| Menu | Configured inventory menus and click actions |
| Moderation | Ban, tempban, unban, kick, IP bans, and SQLite or MySQL storage |
| Team | Configured teams, membership, roles, requests, and optional scoreboard integration |

See the [Wiki](https://github.com/j-gaertig/PlainBase/wiki) for setup, commands, permissions, and module configuration.

## Build from source

The project uses Maven. Run `mvn clean package` with JDK 25. The plugin JAR is written to `target/`.

## Links

- [Wiki](https://github.com/j-gaertig/PlainBase/wiki)
- [Releases](https://github.com/j-gaertig/PlainBase/releases)
- [Modrinth](https://modrinth.com/plugin/plainbase)
- [Hangar](https://hangar.papermc.io/j-gaertig/PlainBase)
- [Issues](https://github.com/j-gaertig/PlainBase/issues)

## License

PlainBase is licensed under the MIT License. See [LICENSE](LICENSE).
