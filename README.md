# <p align="center">PlainBase</p>

<p align="center">
    <a href="https://papermc.io"><img src="https://img.shields.io/badge/Platform-Paper%20%7C%20Purpur%20%7C%20Folia-blue.svg" alt="Platform"></a>
  <a href="https://modrinth.com/plugin/plainbase"><img src="https://img.shields.io/badge/Minecraft-1.21.6%20--%2026.2-3fb58e?style=flat&logo=minecraft&logoColor=white" alt="Minecraft Version"></a>
  <a href="https://github.com/j-gaertig/PlainBase/releases/latest"><img src="https://img.shields.io/github/v/tag/j-gaertig/PlainBase?label=Version&color=orange" alt="Version"></a>
  <a href="https://github.com/j-gaertig/PlainBase/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License"></a>
</p>

<p align="center">
  <strong>PlainBase</strong> - The lightweight all-in-one core for your Minecraft server.
  <br>
  Essential features, zero bloat.
</p>

---

## Current Features

### Core System
*   **Modular Architecture:** You can enable and disable everything in the `config.yml`. No need for 20 different plugins.
*   **High Performance:** It's built for modern Paper servers and works fine with Folia and Purpur too.

### Teleportation Module (TPA & RTP)
<details open>
<summary>Details & Commands</summary>
This is a big one. I finally added a proper teleport system that actually feels good to use.
<ul>
  <li><strong>TPA System:</strong> Send requests to players with <code>/tpa</code> or bring them to you with <code>/tpahere</code>.</li>
  <li><strong>Auto-Accept:</strong> You can toggle auto-accept for your friends with <code>/tpauto</code>. Settings are saved even after you logout.</li>
  <li><strong>Random Teleport (RTP):</strong> Get a random spot in the world. It has cooldowns and checks if the biome or block is safe (no more drowning in the ocean).</li>
  <li><strong>Safety Features:</strong> Teleports can have a countdown and will cancel if you move or take damage.</li>
</ul>

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/tpa <player>` | Ask to teleport to someone. | `plainbase.teleport.tpa.tpa` |
| `/tpahere <player>` | Ask a player to teleport to you. | `plainbase.teleport.tpa.tpahere` |
| `/tpaccept` | Accept a pending request. | `plainbase.teleport.tpa.tpaccept` |
| `/tpdeny` | Deny a request. | `plainbase.teleport.tpa.tpdeny` |
| `/tpacancel` | Cancel your sent request. | `plainbase.teleport.tpa.tpacancel` |
| `/tpauto` | Toggle auto-accepting teleport requests. | `plainbase.teleport.tpa.tpauto` |
| `/rtp` | Teleport to a random location. | `plainbase.teleport.rtp.rtp` |
| *(all TPA commands)* | Grants every TPA permission above. | `plainbase.teleport.tpa.admin` |
| *(all RTP commands)* | Grants every RTP permission above. | `plainbase.teleport.rtp.admin` |
| *(all Teleport commands)* | Grants every permission of the Teleport module (TPA + RTP). | `plainbase.teleport.admin` |
</details>

### Advanced Spawn System
<details>
  <summary>Details & Commands</summary>
  <ul>
    <li>Set global or first-join spawn points.</li>
    <li>Use relative coords like <code>~</code> when setting spawns.</li>
    <li>Auto-teleport players when they join.</li>
  </ul>

| Command                  | Description                                  | Permission                          |
|:-------------------------|:---------------------------------------------|:------------------------------------|
| `/spawn`                 | Go to the spawn point.                       | `plainbase.spawn.spawn`             |
| `/setspawn [x y z]`      | Set the global spawn.                        | `plainbase.spawn.setspawn`          |
| `/disablespawn`          | Disable the global spawn.                    | `plainbase.spawn.disablespawn`      |
| `/setfirstspawn [x y z]` | Set the spawn for new players.               | `plainbase.spawn.setfirstspawn`     |
| `/disablefirstspawn`     | Disable the spawn for new players.           | `plainbase.spawn.disablefirstspawn` |
| *(all Spawn commands)*   | Grants every permission of the Spawn module. | `plainbase.spawn.admin`             |
</details>

### Join Items System
<details>
  <summary>Details & Features</summary>
  <ul>
    <li>Give items to players on join automatically.</li>
    <li>Full MiniMessage support for names and lore.</li>
    <li>Click actions that run commands as the player.</li>
    <li>Protection so players can't drop or move these items.</li>
    <li>Supports custom player skulls.</li>
  </ul>
</details>

### Messages & Broadcasts
<details>
  <summary>Details</summary>
  <ul>
    <li>Custom Join and Quit messages.</li>
    <li>MOTD for when players enter the server.</li>
    <li>Auto-Broadcast system with custom timers for announcements.</li>
  </ul>
</details>

### Vanish Module
<details>
  <summary>Details & Commands</summary>
  <ul>
    <li>Vanish yourself, specific players, whole worlds or everyone.</li>
    <li>Vanished players are hidden from the tab list, take no mob attention and projectiles pass through them.</li>
    <li>Optional: hide armor (or ghost mode), invincibility, persist across rejoin, no collision / step sounds.</li>
    <li>Players with the <code>plainbase.vanish.see</code> permission (or OPs, configurable) can still see vanished players.</li>
  </ul>

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/vanish` | Toggle your own vanish. | `plainbase.vanish.vanish` |
| `/vanish <player>` | Vanish another player. | `plainbase.vanish.vanish.other` |
| `/vanish world` | Vanish all players in your world. | `plainbase.vanish.world` |
| `/vanish all` | Vanish all online players. | `plainbase.vanish.all` |
| *(all Vanish commands)* | Grants every permission of the Vanish module. | `plainbase.vanish.admin` |
| *(see vanished)* | Allows seeing vanished players (default: OPs only). | `plainbase.vanish.see` |
</details>

### Menu Module
<details>
  <summary>Details & Commands</summary>
  <ul>
    <li>Create, delete and open fully config-driven menus via <code>/menu</code>.</li>
    <li>Everything lives in <code>modules/menu.yml</code>: title (MiniMessage), size, fill material and per-slot items.</li>
    <li>Items can run commands, play sounds, show messages and close the menu on click.</li>
    <li>Menus are locked GUIs — players can't move or take items, and their own inventory below is not usable.</li>
    <li>Full PlaceholderAPI support (<code>%player_name%</code>, <code>%plainbase_vanished%</code>, ...) in titles, names, lore and messages.</li>
  </ul>

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/menu new <name>` | Creates a new menu template. | `plainbase.menu.new` |
| `/menu delete <name>` | Deletes a menu. | `plainbase.menu.delete` |
| `/menu open <name>` | Opens a menu. | `plainbase.menu.open` |
| `/menu list` | Lists all available menus. | `plainbase.menu.list` |
| *(all Menu commands)* | Grants every permission of the Menu module. | `plainbase.menu.admin` |
</details>

### PlaceholderAPI
<details>
  <summary>Details</summary>
  <ul>
    <li>Optional soft dependency — PlainBase works fully without it.</li>
    <li>When installed, the <code>%plainbase_*%</code> expansion is registered automatically.</li>
    <li>Placeholders are resolved in menu titles, item names/lore, messages and commands.</li>
  </ul>

| Placeholder | Description |
| :--- | :--- |
| `%plainbase_version%` | The current PlainBase version. |
| `%plainbase_vanished%` | Whether the player is currently vanished (`true`/`false`). |
</details>

---

## Permissions

**Default:** Every PlainBase permission defaults to **`OP`** — regular players won't have access to any command out of the box.

To allow non-OP players (or specific ranks/groups) to use a command, grant the permission manually via your permissions plugin, e.g. with LuckPerms:
`/lp group default permission set plainbase.teleport.tpa.tpa true`

Use `plainbase.<module>.admin` nodes (or `plainbase.admin` for everything) to grant a whole module/module-section at once instead of individual commands.

---

## General Management

| Command | Description | Permission |
| :--- |:--------------------------------------------------------| :--- |
| `/plainbase reload` | Reloads all configs and modules instantly. | `plainbase.admin` |
| `/plainbase update` | Checks Modrinth for a newer version. | `plainbase.admin` |
| `/plainbase toggle <module>` | Enable/Disable modules while the server is running. | `plainbase.admin` |
| *(everything above)* | Grants *every PlainBase permission across all modules*. | `plainbase.admin` |

---

## Planned
*   **Homes & Warps:** Its on my list for next updates.
*   **Tablist & Sidebar:** Some simple stats and custom headers.
*   **Moderations:** Kick, ban, etc.
*   **GUIs:** A nice menu so you don't have to type commands for everything.
*   **And more...**

---

## Support & Community
I'm doing this all by myself, so if you find a bug or have an idea, let me know. Every star on GitHub helps a lot!

*   **Issues:** Found a bug? Open an [Issue](https://github.com/jgaertig/PlainBase/issues).
*   **GitHub:** Leave a **Star**!
*   **Modrinth:** Leave a **Heart** [here](https://modrinth.com/plugin/plainbase).
*   **Hangar:** Leave a **Star** [here](https://hangar.papermc.io/j-gaertig/PlainBase).
*   **Share:** Tell people about it!

---
<p align="center">Built with ❤️ by j-gaertig</p>