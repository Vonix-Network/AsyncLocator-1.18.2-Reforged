# Changelog

All notable changes to this fork are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
relative to the `1.18.2-*` line of releases.

## [1.18.2-1.3.1] — 2026-06-22

Pre-deployment hardening pass on top of 1.3.0.

### Fixed
- **Config backwards-compatibility (critical).** v1.3.0 moved the existing `asyncLocatorThreads`
  and `removeMerchantInvalidMapOffer` keys into a new `[General]` TOML section. This silently
  reset any customised values on first boot of a server upgrading from 1.1.0. Restored the
  original flat layout — only new keys (the per-feature toggles) live under `[Features]`.
  Existing `asynclocator-server.toml` files written by 1.1.0 now upgrade cleanly with zero
  loss of customisation.
- **NPE on failed villager-trade locate (critical).** `MerchantLogic.updateMapAsync`
  (`HolderSet` overload) called `pair.getFirst()` without null-checking `pair`. With the
  hardened executor's exception isolation now returning `null` on search failure (instead of
  leaking a future), this would NPE inside the callback and leak the placeholder. Added
  explicit null guard so failed trade locates correctly invalidate the offer.
- `SetNameFunctionMixin` now null-guards the incoming `Component name` parameter (defensive —
  vanilla never passes null, but a mis-behaving loot mod could).

### Changed
- **`SlotMixin` allows creative-mode bypass.** Admins / OPs in creative mode can now manually
  remove a stuck pending map (e.g. after a misbehaving structure tag leaves a placeholder
  orphaned). Survival players still see the original block-pickup protection.
- `tryReplaceMapInPlayerSlots` and `broadcastMapInPlayerSlots` now also check the
  `AbstractContainerMenu.getCarried()` cursor stack — handles the edge case where a player
  is mid-drag with the placeholder on their mouse cursor when the locate task completes.

## [1.18.2-1.3.0] — 2026-06-22

### Added
- **Per-feature config toggles.** Each of the five async-locate paths can now be independently
  enabled/disabled in `config/asynclocator-server.toml`. Toggles:
  `dolphinTreasureEnabled`, `eyeOfEnderEnabled`, `explorationMapEnabled`,
  `locateCommandEnabled`, `villagerTradeEnabled`. Backport of upstream [`c974167`].
- **Block-pickup of in-progress maps.** New `SlotMixin` prevents players from yanking the
  placeholder map out of the source container before the locate task completes, which would
  otherwise orphan the locate task. Backport of upstream [`47c1c7f`] with a more reliable
  NBT-based detection.
- **Deferred map naming.** New `SetNameFunctionMixin` captures the loot-table-set hover name
  into NBT on the placeholder so the final map is named correctly after locating completes
  (instead of staying "Locating..." forever). Backport of upstream [`b93ef4a`] using NBT
  persistence instead of an `ItemStack`-keyed Guava cache, which survives stack copies and
  chunk reloads.
- SLF4J `Marker` (`"AsyncLocator"`) on every log line so log4j2/logback pipelines can route
  or filter mod output independently.
- `logError` helper alongside the existing log helpers.

### Changed
- **Hardened executor lifecycle.** `stopExecutor` now waits up to 10 s for in-flight tasks to
  drain via `awaitTermination` before forcing termination. Worker threads are daemonic and
  carry an `UncaughtExceptionHandler`. The executor reference is `volatile` and accessed under
  synchronized start/stop. Locate calls outside the server lifecycle now throw a clear
  `IllegalStateException` instead of NPE.
- **Full exception isolation in async workers.** A throw from `findNearestMapFeature` (which
  can happen for malformed structure tags, dim-out-of-bounds searches, or chunk-loader races)
  no longer leaks the placeholder. The result future is completed with `null` and the
  placeholder is invalidated normally.
- **`LocateTask.then` and `LocateTask.thenOnServerThread`** now use `whenComplete` with
  try/catch around the user callback so exceptions are logged at WARN instead of being
  silently swallowed.
- **Pending-map detection moved from hover-name to NBT** (`asynclocator_pending`). Survives
  item renames by anvils, commands, or mods.
- `mods.toml` cleaned up: removed all generator boilerplate, added `displayURL`, expanded
  description, real issue-tracker URL.
- Config layout reorganised into `General` and `Features` subcategories.

### Fixed
- **Upstream typo:** config key was `"explorationMspEnabled"`, now correct `"explorationMapEnabled"`.
- **`EYE_OF_ENDER_ENABLED=false` correctness.** With the feature disabled, the side effects
  (`signalTo`, advancement trigger, `awardStat`) previously remained no-ops, breaking vanilla
  behaviour. They are now invoked synchronously when the feature is disabled, matching
  vanilla.
- **`MerchantLogic.invalidateMap`** now clears the pending NBT tag on the invalidated map so
  the player can pick it up.

## [1.18.2-1.2.0] — 2026-06-22

### Added
- **Lootr-compatibility fix.** When an async locate task completes, the map-update and
  invalidate paths now walk every online player's open container and inventory for the
  in-progress map by `ItemStack` reference equality before falling back to the block-entity
  item-handler capability lookup. This is the first-line path that fixes the
  acknowledged-but-never-released-for-1.18.2 incompatibility with Lootr chests (no
  item-handler capability is exposed by Lootr's chest BE by design).
- Strict superset of `1.1.0` behaviour. No config changes. No new dependencies.
- Demoted the legacy "No item-handler capability on chest" message from `WARN` to `DEBUG`
  since it is no longer a failure condition with the new fallback chain.

## [1.18.2-1.1.0] — 2022-12-18

Imported from `thebrightspark/AsyncLocator@aca6083` — the exact upstream source for the
CurseForge-only `1.18.2-1.1.0` release that was never tagged or published on GitHub. See
the [original repository](https://github.com/thebrightspark/AsyncLocator) for prior history.

[`c974167`]: https://github.com/thebrightspark/AsyncLocator/commit/c974167b7c52d8e925b3026cd5dd877184c4296e
[`47c1c7f`]: https://github.com/thebrightspark/AsyncLocator/commit/47c1c7f48a44536bcf3f94b5fe4c233250b192c3
[`b93ef4a`]: https://github.com/thebrightspark/AsyncLocator/commit/b93ef4ab4c69ef861d4402397031bff21946f9aa
