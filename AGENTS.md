# Contributor instructions

This repository contains the Fabric building bridge and local Python tools. Keep world changes on the integrated server thread and file IO on the IO worker. Never edit an active Minecraft save directly.

Preserve the inventory escrow, session checks, world/dimension binding, deduplication and later-player-change protection. Unsupported block/entity data must fail explicitly rather than be silently removed.

Do not commit game instances, saved worlds, schematics owned by others, inventory snapshots, request/result journals, local reports or credentials. The root allowlist in .gitignore is intentional.

Compile with Java25. Run checks relevant to the change. Offline checks do not substitute for graphical client validation of world mutations. QA mods belong only in disposable instances; do not install them into normal gameplay.
