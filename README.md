# AsyncLocator 1.18.2-Refined

[![CI](https://github.com/Vonix-Network/AsyncLocator-1.18.2-Refined/actions/workflows/build.yml/badge.svg)](https://github.com/Vonix-Network/AsyncLocator-1.18.2-Refined/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft: 1.18.2](https://img.shields.io/badge/Minecraft-1.18.2-green.svg)](#)
[![Forge: 40.x](https://img.shields.io/badge/Forge-40.x-orange.svg)](#)

A maintenance fork of **[brightspark/AsyncLocator]** that backports the **Lootr-compatibility fix**
from **[Alvaro842DEV/AsyncLocator-Refined]** to **Minecraft 1.18.2 Forge**, with additional
production-grade hardening.

[brightspark/AsyncLocator]: https://github.com/thebrightspark/AsyncLocator
[Alvaro842DEV/AsyncLocator-Refined]: https://github.com/Alvaro842DEV/AsyncLocator-Refined

---

## What this fork does

The original `thebrightspark/AsyncLocator` shipped a `1.18.2-1.1.0` release on CurseForge in
December 2022, then moved on to Minecraft 1.19+. The 1.18.2 source was never tagged on GitHub,
never released there, and never updated. The mod is still in heavy use by long-running 1.18.2
modpacks.

This fork:

1. **Fixes the Lootr incompatibility** that causes "Couldn't find item handler capability on
   chest" WARN spam and (under load) chunk-load deadlocks when treasure maps drop from Lootr
   chests. The fix is ported from Alvaro842DEV's NeoForge 1.21.1 fork, adapted to 1.18.2 where
   the implementation is actually simpler thanks to `ItemStack` reference equality.
2. **Backports** the post-`1.1.0` upstream improvements that were never released for 1.18.2:
   per-feature config toggles, block-pickup of in-progress maps, deferred map-name preservation.
3. **Hardens** the executor lifecycle and exception handling for long-running production
   servers (graceful shutdown drain, exception-isolated workers, daemon threads, SLF4J markers).

See [CHANGELOG.md](CHANGELOG.md) for the full version history.

## Features

| Feature | `1.18.2-1.1.0` | This fork |
|---|---|---|
| Lootr-chest async-map updates | ❌ WARN spam, map never finalises | ✅ Walks player containers first |
| Per-feature enable/disable config | ❌ All-or-nothing | ✅ Five independent toggles |
| Block pickup of in-progress maps | ❌ Player-yank orphans the task | ✅ `SlotMixin` keeps it pinned |
| Loot-table SetName preservation | ❌ Stuck on "Locating..." forever | ✅ Stashed in NBT, applied on completion |
| Server-stop drain of in-flight tasks | ❌ NPE-on-stop in log | ✅ 10 s `awaitTermination` |
| `findNearestMapFeature` throw isolation | ❌ Future leaks, placeholder stuck | ✅ Completes `null`, invalidates cleanly |
| Daemon worker threads | ❌ Can block JVM exit | ✅ |
| SLF4J `Marker` on log output | ❌ | ✅ `"AsyncLocator"` |

## Installation

Drop the jar into your server's `mods/` folder and **remove** the old
`asynclocator-1.18.2-1.1.0.jar` (or earlier). Versions are drop-in compatible — same mod ID,
same package paths, config is forward-compatible.

Server-side only: clients do not need the mod.

```text
mods/
├── asynclocator-1.18.2-1.3.0.jar        ← from GitHub Releases
└── lootr-forge-1.18.2-0.3.30.74.jar     ← unchanged
```

## Configuration

`config/asynclocator-server.toml` after first launch:

```toml
[General]
asyncLocatorThreads = 1
removeMerchantInvalidMapOffer = false

[Features]
dolphinTreasureEnabled = true
eyeOfEnderEnabled = true
explorationMapEnabled = true
locateCommandEnabled = true
villagerTradeEnabled = true
```

Each feature toggle independently enables/disables the async path for that game event.
Disabling a feature falls through to vanilla synchronous behaviour for *only* that event;
the rest of the mod continues to operate normally. This is the recommended troubleshooting
lever — if a specific feature misbehaves on your pack, disable just that one rather than
removing the mod.

## Building from source

```bash
git clone https://github.com/Vonix-Network/AsyncLocator-1.18.2-Refined.git
cd AsyncLocator-1.18.2-Refined
JAVA_HOME=/path/to/jdk17 ./gradlew build
# → build/libs/asynclocator-1.18.2-1.3.0.jar
```

Requirements: JDK 17, Gradle 7.4 (wrapper included), internet access for ForgeGradle to download
the 1.18.2-40.1.20 MDK on first run.

## Credits

- **bright_spark** ([thebrightspark/AsyncLocator]) — original mod, MIT-licensed.
- **Alvaro842DEV** ([Alvaro842DEV/AsyncLocator-Refined]) — the Lootr-fix idea this port is based on, MIT-licensed.
- **Vonix-Network** — this 1.18.2 backport + production-grade hardening.

## License

MIT — see [LICENSE](LICENSE). Original copyright `Copyright (c) 2022 bright_spark` retained.

## Status

Active maintenance fork. PRs welcome — particularly for compatibility patches with other
1.18.2 mods that interact with the locate path.
