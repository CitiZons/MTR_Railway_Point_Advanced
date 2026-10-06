# MTR Railway Point Advanced

[English](README.en.md) | [简体中文](README.zh-CN.md)

![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-62a35a?style=flat-square) ![Forge 47.4.18](https://img.shields.io/badge/Forge-47.4.18-f59e0b?style=flat-square) ![MTR 4.0.3](https://img.shields.io/badge/MTR-4.0.3-3b82f6?style=flat-square) ![Version 0.1.6](https://img.shields.io/badge/version-0.1.6-2563eb?style=flat-square) ![License MIT](https://img.shields.io/badge/license-MIT-8b5cf6?style=flat-square)

A visual turnout addon for [MTR](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway), version **0.1.6**. It generates stock rails, switch rails, wings, guards, frogs, flangeways, rods, fittings and sleepers along the actual alignment, grade and cant. Default models and materials are included; rail resource packs are optional.

## Installation

Requires Minecraft **1.20.1**, Forge **47.4.18** and MTR Forge **4.0.3**.

Install `mtr_railway_point_advanced-0.1.6.jar` in both client and server `mods` folders. Use the same build on both sides and remove older copies. The network protocol is `3`; do not install the development-only `point-runtime-probe` in a normal game.

The companion pack is **Citizons Railway 0.1.2**, with ballast, slab, direct-support and continuous-guard styles. The mod and pack have separate version numbers.

## Usage

1. Lay rails using MTR. Turnout branches share a node; fixed crossings use coplanar intersecting rails.
2. Nearby junctions are detected automatically; no extra block or marker is needed.
3. Press **P** for the junction map within 64 blocks, select a junction, then press Enter to open its blueprint.
4. Edit the rail style, geometry or individual sleepers, then choose **Save appearance**. Separate `mtrpoint_appearance` data is synchronized to other clients.
5. **Test switch** previews motion; **Live** follows operating state. Closing the screen discards unsaved previews.

## Features

- Same-node Y, curved and three-way turnouts, fixed diamonds, flat crossings and scissors crossover groups.
- Closed switch tips meet the stock head; the planed lower section changes from an asymmetric L-section to the full I-section.
- Three-way turnouts use two complementary adjacent-route blade pairs. The middle route keeps wings and omits redundant guards; V frogs use the ordinary turnout section.
- All three beds share fitting placement. Overlapping blade fittings use slide plates; guard seats replace ordinary fittings, and slab blocks follow the final seats.
- Only fittings inside the V nose are omitted; outside guard clips, braces and plates remain, as do sleepers, support blocks and beds.
- Matching stock, fixed switch-heel, wing, continuous-guard and ballast joints receive patches for gaps up to 6 mm, preserving flangeways and real openings.
- Switch and movable-frog animation follows read-only MTR and BRsignal operating state.

## Resource packs

Models and materials come from the active resource stack. Native MTR styles, OBJ/MTL, ordinary Blockbench cubes and `modelGroups` profiles are supported. Profiles at `assets/<namespace>/rail_profiles/<name>.json` declare sections, groups, textures, UVs, LODs, continuous supports and guard ends. Directional suffixes are normalized; no pack whitelist is required.

Minimal section profile:

```json
{
  "style": "my_custom_rail",
  "track": true,
  "gauge": 1.435,
  "top": 0.26428,
  "headWidth": 0.068,
  "footWidth": 0.14,
  "railHeight": 0.165,
  "steelTexture": "my_pack:textures/steel.png",
  "sleeperTexture": "my_pack:textures/concrete.png"
}
```

Complex meshes or models combining rails with bridges or tunnels need explicit profiles or a manual style selection. Citizons Railway 0.1.2 shares OBJ indices to reduce duplicate parsing records; the mod still converts each face to `Mesh.Quad`, so indexing alone does not reduce runtime face counts.

## Detail and rendering

Adjust client presentation in **Mods → MTR Railway Point Advanced → Config**. Distance sliders cover 0–256 m; config files allow 0–4096 m. Saving applies the settings locally without changing server operations.

| Setting | Default | Effect |
|---|---|---|
| UI scale | 100% | Blueprint and selection screens, 50%–125%. |
| High/mid and mid/low boundaries | 4 m / 12 m | Distance thresholds for high, medium and low detail. |
| Turnout animation distance | 24 m | Freeze visual animation beyond this distance; zero freezes all animation. |
| Turnout takeover distance | 64 m | Keep native MTR rendering beyond this distance; zero disables takeover. |

World meshes use entity cutout rendering with Iris/Oculus support and Optional Rail banking samples. Static and moving parts are cached separately and batched by material, LOD and space. Unchanged geometry is not rebuilt each frame; joint patches update when source meshes change. FPS depends on the pack, shaders and view distance.

## Limitations

- Visual changes only: no changes to MTR connections, routing, speed limits, train positions or BRsignal authority and signals. Animation does not control train release.
- Fixed crossings add neither route connections nor conflict protection. Double slips, ladders and other layouts requiring a dedicated switch graph are not implemented.
- Folded, vertically separated or recrossing Y/three-way branches retain native MTR rendering. Dense layouts exceeding detection budgets are not taken over for that pass; not every unsupported shape can be detected.
- Optional Rail sampling failures fall back to native MTR geometry.

## Build

Requires JDK 17 and the compile-time dependency `../MTR_BRsignal_addon/libs/MTR-forge-4.0.3+1.20.1.jar`.

```powershell
.\gradlew.bat build --offline --no-daemon --console=plain
```

The build produces `build/libs/mtr_railway_point_advanced-0.1.6.jar`.

## Documentation and licence

This project is primarily implemented with ChatGPT.
