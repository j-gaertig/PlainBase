<div align="center">

# 🛠️ PlainBase
**The minimalist all-in-one solution for your Paper server.**

[![Build Status](https://img.shields.io/badge/Platform-Paper-blue.svg)](https://papermc.io)
[![Version](https://img.shields.io/github/v/tag/j-gaertig/PlainBase?label=Version&color=orange)](https://github.com/j-gaertig/PlainBase/releases/latest)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

---

[Features](#-current-features) • [Planned](#-planned-features) • [Support](#-support--community) • [Issues](https://github.com/jgaertig/PlainBase/issues)

</div>

**PlainBase** is a lightweight, all-in-one Paper plugin designed to simplify server management. Instead of juggling dozens of different plugins for basic features like EssentialsX, ItemJoin, or TAB, PlainBase combines these essential functions into a single, easy-to-use framework while maintaining a clean and minimalist approach.

---

## 🚀 Current Features

### ⚙️ Core System
*   **🧩 Modular Design:** Enable only what you need. Keep your server performance-focused by disabling unused modules via `config.yml`.
*   **📡 Intelligence:** Automated notifications for OPs if configuration files are outdated, ensuring you always have the latest options.

### 📍 Advanced Spawn System
<details open>
  <summary><b>View Module Details & Commands</b></summary>
  <br>
  
  Powerful spawn management that handles both new and returning players with precision.

  *   **Dual-Spawn Support:** Set a global spawn and a unique landing spot for first-timers.
  *   **Smart Coordinates:** Full support for relative `~` coordinates—set spawns without moving your character.
  *   **Auto-Teleport:** Optional join-teleportation to keep your world entry organized.

  #### ⌨️ Commands
  | Command | Description | Permission |
  | :--- | :--- | :--- |
  | `/spawn` | Teleports you to the global spawn point. | *Everyone* |
  | `/setspawn [x y z]` | Sets the global spawn point. | `OP` |
  | `/setfirstspawn [x y z]` | Sets the spawn for new players. | `OP` |
  | `/disablespawn` | Disables the global spawn system. | `OP` |
  | `/disablefirstspawn` | Disables the first-join spawn system. | `OP` |
</details>

### 🎒 Join Items System
<details>
  <summary><b>View Module Details & Features</b></summary>
  <br>

  Engage your players from the second they land with interactive hotbar items.

  *   **🎨 MiniMessage Power:** Create stunning item names and lore with gradients, RGB, and hover effects.
  *   **🖱️ Interactive:** Assign custom commands to items (supports `%player%` placeholder).
  *   **🔒 Inventory Lock:** Protect essential items from being dropped, moved, or lost.
  *   **📄 Easy Config:** Manage everything in the dedicated `joinitems.yml`.
</details>

---

## 📅 Planned Features

*   **✨ Join Enhancements:** Customizable Join/Quit Messages and a feature-rich MOTD.
*   **📊 Tablist & Side Menus:** Integrated TAB-list customization and Scoreboards (Sidebars).
*   **🏠 Home & Warp System:** Core teleportation management for players.
*   **👮 Basic Moderation Tools:** Essential commands to keep your server safe.
*   **📈 More to come:** Bridging the gap between complexity and usability.

---

## 💎 Support & Community

This is a **one-man project**. I'm building this to make server management easier for everyone. If you find it useful, there are several ways to support the development:

*   **🐛 Bug Reports:** Found something wrong? Open an [Issue](https://github.com/jgaertig/PlainBase/issues).
*   **⭐ GitHub Star:** If you like the project, please leave a star - it helps more than you think!
*   **❤️ Modrinth Heart:** Follow the project on [Modrinth](https://modrinth.com/plugin/plainbase) and show some love!
*   **📢 Share:** Tell your friends or fellow server owners about PlainBase.

---
<div align="center">
  <sub>Built with ❤️ by j-gaertig</sub>
</div>
