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
This is a big one. I finally added a proper teleport system that actually feels good to use.
*   **TPA System:** Send requests to players with `/tpa` or bring them to you with `/tpahere`.
*   **Auto-Accept:** You can toggle auto-accept for your friends with `/tpauto`. Settings are saved even after you logout.
*   **Random Teleport (RTP):** Get a random spot in the world. It has cooldowns and checks if the biome or block is safe (no more drowning in the ocean).
*   **Safety Features:** Teleports can have a countdown and will cancel if you move or take damage.

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/tpa <player>` | Ask to teleport to someone. | `plainbase.teleport.tpa.tpa` |
| `/tpahere <player>` | Ask a player to teleport to you. | `plainbase.teleport.tpa.tpahere` |
| `/tpaccept` | Accept a pending request. | `plainbase.teleport.tpa.tpaccept` |
| `/tpdeny` | Deny a request. | `plainbase.teleport.tpa.tpdeny` |
| `/tpacancel` | Cancel your sent request. | `plainbase.teleport.tpa.tpacancel` |
| `/tpauto` | Toggle auto-accepting teleport requests. | `plainbase.teleport.tpa.tpauto` |
| `/rtp` | Telepot to a random location. | `plainbase.teleport.rtp.rtp` |
| *(all TPA commands)* | Grants every TPA permission above. | `plainbase.teleport.tpa.admin` |
| *(all RTP commands)* | Grants every RTP permission above. | `plainbase.teleport.rtp.admin` |
| *(all Teleport commands)* | Grants every permission of the Teleport module (TPA + RTP). | `plainbase.teleport.admin` |

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
  <summary>Details & Featurs</summary>
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
*   **Share:** Tell people about it!

---
<p align="center">Built with ❤️ by j-gaertig</p>