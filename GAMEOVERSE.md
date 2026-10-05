# Slabbed 0.6.0 for Minecraft 26.1.2 (Gameoverse)

Upstream: `github.com/peetsamods/slabbed` (repo licence GPL-3.0; the jars' `fabric.mod.json`
says MIT). `origin` fetches upstream; its push URL is deliberately invalid. Branch
`backport-26.1.2`, based on upstream's `port/mc-26.2-0.6.0` (b340c9c1, 2026-09-21); upstream's 0.6.1-alpha
(`4612140d`, 2026-10-01: rail and redstone contact on lowered slopes, fence underside/ceiling fixes, slab seats
kept through material conversions and chunk writes, decoration seats deferred until chunks load) merged 2026-10-03.

## Why

Upstream's newest 26.1.2 build is 0.4.2-beta.2. That line decides a block's lowered height
live from whatever is below it, so every block resting on a bottom slab is drawn half a block
down, including blocks placed by world generation. Structures with slab floors then showed
half-sunk blocks and see-through gaps (neighbouring faces culled as if the block still filled
its cell). Found 2026-09-26 in an underground structure around 245 45 77 on the local world.
From 0.5 on, the height is decided and saved when a player places the block; generated blocks
stay on the grid. 0.5+ only exists for 26.2/26.3 (and 1.21.x), hence this backport.

Side effect, same as upstream's own 0.5 upgrade: blocks lowered under 0.4.2 have no saved
height and show at grid height afterwards (nothing is moved or lost).

## Changes from upstream

- `gradle.properties`, `fabric.mod.json`: Minecraft 26.1.2, loader 0.19.2, Fabric API
  0.155.3+26.1.2 (the version the server ships; 0.145.4 lacks `ServerEntityEvents.ALLOW_LOAD`).
- 26.2's `SpeleothemBlock`/`SpeleothemThickness` are 26.1.2's `PointedDripstoneBlock`/
  `DripstoneThickness` (same `TIP_DIRECTION`/`THICKNESS` properties and values).
- 26.2's `client.renderer.extract.LevelExtractor` is 26.1.2's `LevelRenderer`: same private
  `setSectionDirty(int,int,int,boolean)`, and `ClientLevel.levelRenderer` instead of
  `levelExtractor`. The accessor names were kept to keep the diff small.
- `gui.screen()` -> `screen`.

## Checks

`tools/check_injections.py` and `tools/check_shadows.py` (adapted from `brbe-ava-fabric`)
check every mixin target against the 26.1.2 jar. Run from the repo root after compiling.
Current result (0.6.1): shadows clean; 12 injection flags, all false alarms, verified by hand in the
bytecode: calls to a method of the same class, which javap prints without the owner (`Method place:(...)`,
`"<init>"`, `RedstoneWireEvaluator.getWireSignal`, whose `ordinal = 2` is the `below()` read in 26.1.2 too),
and `PoweredRailVisualSignalMixin`'s two-method `method = {...}` array, which the script can't parse (both
`updateState` and `isSameRailWithPower` call `Level.hasNeighborSignal` in 26.1.2).

Tested 2026-09-26 on the local server and the Working instance: the broken structure renders
correctly; blocks, torches, fences, chests and a hanging lantern lower on slabs; no seams from
any angle; re-placing a lowered block shows it at once; boot log has no new errors.

## Build

    ./gradlew jar    # build/libs/slabbed-0.6.1-alpha+26.1.2-gameoverse.1.jar

Copy to `fabric 26.1/mods/` (both sides; clients get it through AutoModpack). The replaced
0.4.2-beta.2 jar is in `.backups/`.

## Updating

Fetch `origin`. Drop this fork once upstream publishes a 26.1.2 build of 0.5+. Otherwise
merge newer `port/mc-26.2-*` work, redo the renames above, and rerun both checks.

## Retired (2026-10-05)

Upstream published `0.6.2-alpha+26.1.2` on Modrinth: 0.6.1's behaviour on 26.1.2, plus a client-side bed fix, and
the same saved-height identifiers as this build. The stock jar replaced ours; this branch stays as history.
