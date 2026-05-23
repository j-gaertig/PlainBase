# <p align="center">🛠️ PlainBase</p>

<p align="center">
    <a href="https://papermc.io"><img src="https://img.shields.io/badge/Platform-Paper%20%7C%20Purpur%20%7C%20Folia-blue.svg" alt="Platform"></a>
  <a href="https://modrinth.com/plugin/plainbase"><img src="https://img.shields.io/badge/Minecraft-1.21.6%20--%2026.1.2-3fb58e?style=flat&logo=minecraft&logoColor=white" alt="Minecraft Version"></a>
  <a href="https://github.com/j-gaertig/PlainBase/releases/latest"><img src="https://img.shields.io/github/v/tag/j-gaertig/PlainBase?label=Version&color=orange" alt="Version"></a>
  <a href="https://github.com/j-gaertig/PlainBase/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License"></a>
</p>

<p align="center">
  <strong>PlainBase</strong> - The lightweight all-in-one core for your Minecraft server.
  <br>
  Essential features, zero bloat.
</p>

<p align="center">
  <a href="#current-features">Features</a> •
  <a href="#commands">Commands</a> •
  <a href="#planned">Planned</a> •
  <a href="#support">Support</a>
</p>

---

## 🚀 <a name="current-features"></a>Current Features

### 🧩 Core System
*   **Modular Architecture:** Enable only what you need (Spawn, JoinItems, Messages) via the central `config.yml`.
*   **⚡ High Performance:** Built from the ground up for modern Paper servers with native Folia and Purpur support.

### 📍 Advanced Spawn System
<details open>
  <summary><b>View Module Details & Commands</b></summary>
  <ul>
    <li>Set global and unique first-join spawn points.</li>
    <li>Supports <b>relative coordinates</b> (<code>~</code>) for precise placement.</li>
    <li>Automated teleportation on join for new and returning players.</li>
  </ul>

  #### Spawn Commands
  | Command | Description | Permission |
  | :--- | :--- | :--- |
  | `/spawn` | Teleports you to the global spawn. | `Everyone` |
  | `/setspawn [x y z]` | Sets the global spawn point. | `OP` |
  | `/setfirstspawn [x y z]` | Sets the first-join spawn point. | `OP` |
  | `/disablespawn` | Disables the global spawn system. | `OP` |
  | `/disablefirstspawn` | Disables the first-join spawn system. | `OP` |
</details>

### 🎒 Join Items System
<details open>
  <summary><b>View Module Details</b></summary>
  <ul>
    <li>Give players items automatically when they join.</li>
    <li><b>MiniMessage Support:</b> Use modern formatting (colors, gradients, decorations).</li>
    <li><b>Custom Actions:</b> Execute commands on click (supports <code>%player%</code>).</li>
    <li><b>Protection:</b> Prevent moving, dropping, or swapping items.</li>
    <li><b>Skull Support:</b> Custom heads (e.g., player's own head).</li>
  </ul>
</details>

### 💬 Messages & Broadcasts
<details open>
  <summary><b>View Module Details</b></summary>
  <ul>
    <li><b>Join/Quit:</b> Fully customizable entry and exit messages.</li>
    <li><b>MOTD:</b> Display a welcome message when players join.</li>
    <li><b>Auto-Broadcasts:</b> Send periodic announcements with individual cooldowns.</li>
    <li><b>Formatting:</b> Full MiniMessage support for all messages.</li>
  </ul>
</details>

---

## ⌨️ <a name="commands"></a>General Commands

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/plainbase reload` | **Core Maintenance:** Reloads all configurations and reinitializes every module instantly without server restart. | `OP` |
| `/plainbase update` | **Version Check:** Queries Modrinth for the latest compatible release and provides a direct download link. | `OP` |
| `/plainbase toggle <module>` | **Live Configuration:** Instantly enables or disables specific features (e.g., `spawn`, `joinitems`) on the fly. | `OP` |

---

## 📅 <a name="planned"></a>Planned Features

*   **📊 Tablist & Side Menus:** Integrated TAB-list and Scoreboards/Sidebars.
*   **🏠 Home & Warp System:** Allow players to save and visit private or public locations.
*   **👮 Basic Moderation:** Essential tools like kicks, bans, and muting.
*   **⚙️ GUI Management:** Manage all modules through an intuitive in-game menu.
*   **➕ And more...** We are constantly working on new features to make server management even easier.

---

## 💎 <a name="support"></a>Support & Community

This is a **one-man project**, and I am working hard to make server management as simple as possible. Your support means everything!

*   **🐛 Issues?** Found a bug? Open an [Issue](https://github.com/jgaertig/PlainBase/issues).
*   **⭐ GitHub:** Leave a **Star** if you like the project!
*   **❤️ Modrinth:** Follow on [Modrinth](https://modrinth.com/plugin/plainbase) and leave a **Heart**!
*   **📢 Share:** Tell your friends about PlainBase!

---
<p align="center">Built with ❤️ by j-gaertig</p>
