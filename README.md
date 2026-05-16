# PlainBase

PlainBase is a lightweight, all-in-one Paper plugin designed to simplify server management. Instead of juggling dozens of different plugins for basic features like EssentialsX, ItemJoin, or TAB, PlainBase combines these essential functions into a single, easy-to-use framework while maintaining a clean and minimalist approach.

## Current Features
*   **Modular System:** Enable or disable features via the central `config.yml`.
*   **Advanced Spawn System:**
    *   Set global spawn points and unique first-join spawn points.
    *   Flexible coordinate system (supports relative `~` and absolute coordinates).
    *   Automated teleportation on join for new and returning players.
    *   **Commands:**
        *   `/spawn` - Teleports you to the global spawn point.
        *   `/setspawn [x y z]` - Sets the global spawn point at your location or specified coordinates.
        *   `/setfirstspawn [x y z]` - Sets the spawn point specifically for new players.
        *   `/disablespawn` - Disables the global spawn system.
        *   `/disablefirstspawn` - Disables the first-join spawn system.
        *   *Note: Management commands require Operator (OP) status.*
*   **Join Items System:**
    *   Give players items automatically when they join.
    *   **MiniMessage Formatting:** Full support for colors, gradients, and decorations in item names and lore.
    *   **Custom Actions:** Execute commands when players click their join items (supports `%player%` placeholder).
    *   **Inventory Protection:** Prevent players from moving, dropping, or swapping join items.
    *   Highly configurable via `joinitems.yml`.
*   **Config Versioning:** Automated notifications for OPs if configuration files are outdated.

## Planned Features
*   **Join Enhancements:** Customizable Join/Quit Messages and a feature-rich MOTD.
*   **Tablist & Side Menus:** Integrated TAB-list customization and Scoreboards (Sidebars).
*   **Home & Warp System:** Core teleportation management for players to save and visit locations.
*   **Basic Moderation Tools:** Essential commands to keep your server safe and manageable.
*   **And more:** We aim to bridge the gap between complexity and usability for many more standard features.

## Help Me
This is a **one-man project**, and I am working hard to make server management as possible. Your support means everything!
*   **Found a bug?** Please open an [Issue](https://github.com/jgaertig/PlainBase/issues) so I can fix it.
*   **Like the project?** Please leave a **Star** on GitHub to show your support and help others find it.
*   **Spread the word!** Feel free to share this project with others who might find it useful.

Every bit of feedback and support helps grow this project!