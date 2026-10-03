# MTR Railway Point Advanced

![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-62a35a?style=flat-square) ![Forge 47.4.18](https://img.shields.io/badge/Forge-47.4.18-f59e0b?style=flat-square) ![MTR 4.0.3](https://img.shields.io/badge/MTR-4.0.3-3b82f6?style=flat-square) ![Version 0.1.5](https://img.shields.io/badge/version-0.1.5-2563eb?style=flat-square) ![License MIT](https://img.shields.io/badge/license-MIT-8b5cf6?style=flat-square)

**让 MTR 的道岔成为真实的轨道结构，而不只是普通轨道的贴图替换。**

MTR Railway Point Advanced 为 Minecraft 1.20.1 的 MTR 提供程序生成的道岔、辙叉、平交和交叉渡线几何。它读取 MTR 当前轨道曲线，在交汇处生成尖轨、基本轨、翼轨、护轨、岔心、轮缘槽、拉杆、扣件和岔枕，并沿真实的坡度、曲线和倾角放置。模型、默认材质和蓝图编辑界面都包含在 Mod 中，不依赖额外的轨道资源包。

**Give MTR turnouts a real track structure instead of a flat visual patch.**

MTR Railway Point Advanced generates turnout, frog, crossing and crossover geometry for Minecraft 1.20.1. It samples MTR rail curves and builds switch rails, stock rails, wing rails, guard rails, frogs, flangeways, rods, fittings and sleepers along the actual alignment, grade and cant. The default models, materials and blueprint editor are bundled with the mod, so a separate rail pack is optional.

## What it adds / 模组内容

| English | 中文 |
|---|---|
| **Procedural turnouts** — same-node Y turnouts, three-way turnouts, curved turnouts and coordinated crossover groups. | **程序化道岔**——同节点 Y 形道岔、三开道岔、曲线道岔以及协调生成的交叉渡线组合。 |
| **Fixed crossings** — non-interlocking diamonds and flat crossings with continuous rails and flangeways. | **固定交叉**——不互通菱形交叉和平交口，保持连续钢轨并生成轮缘槽。 |
| **Real support details** — turnout sleepers, common frog plates, slide plates, guard-rail seats, wing-rail seats, braces and bolts. | **真实支承细节**——岔枕、岔心共用底板、滑床板、护轨和翼轨承座、加强件及螺栓。 |
| **Animated appearance** — switch rails and movable frogs follow the visual movement observed from MTR and BRsignal snapshots. | **动画外观**——尖轨和可动岔心根据 MTR 与 BRsignal 的只读状态进行视觉运动。 |
| **Blueprint editing** — select a junction in the world, preview the generated mesh, adjust supported parameters and save the appearance. | **蓝图编辑**——在世界中选择交汇，预览生成网格，调整支持的参数并保存外观。 |
| **Resource-pack geometry** — use active rail-pack materials, UVs, model groups, cross-sections and LOD models for ordinary rails and turnouts. | **资源包几何**——普通轨道和道岔可使用当前资源包的材质、UV、模型分组、截面及 LOD 模型。 |

## Install / 安装

需要 Minecraft 1.20.1、Forge 47.4.18 和 MTR Forge 4.0.3。将下面的同一版本 JAR 同时放入客户端和服务器的 `mods/` 文件夹：

Requires Minecraft 1.20.1, Forge 47.4.18 and MTR Forge 4.0.3. Put the same JAR version in the client and server `mods/` folders:

```text
mtr_railway_point_advanced-0.1.5.jar
```

网络协议为 `3`，客户端与服务器必须使用相同的 Mod 版本。`point-runtime-probe` 是开发测试工具，不要安装到正式客户端或服务器。

The appearance protocol is `3`; clients and servers must use the same mod version. `point-runtime-probe` is a development probe and should not be installed in a normal game.

## First use / 初次使用

1. 按 MTR 原有方法铺设轨道。道岔使用同一节点和符合 MTR 拓扑的分支；平交口使用两条同平面交叉轨道。
2. 客户端会自动识别附近的道岔、辙叉和平交交汇，并生成对应几何。不需要放置额外方块或手工标记节点。
3. 按 **P** 打开 64 格内的岔区选择图。点击编号或列表项目后，按 Enter 或选择“编辑选中道岔”进入蓝图。
4. 在蓝图中预览几何、选择轨型、调整参数或移动单根岔枕。点击“保存外观”后，外观数据会写入独立的 `mtrpoint_appearance` 数据并同步给其他客户端。
5. “试动尖轨”只改变视觉预览；“实时”恢复跟随列车状态。未保存的预览在退出界面时撤销。

1. Lay rails using the normal MTR workflow. Turnouts must use MTR-compatible same-node branches; crossings use two coplanar intersecting rails.
2. The client detects nearby turnouts, frogs and crossings and generates their geometry automatically. No extra block or marker is required.
3. Press **P** to open the junction map within 64 blocks. Select an entry, then press Enter or choose **Edit selected turnout**.
4. Preview the mesh, choose a rail style, edit supported parameters or move an individual sleeper. **Save appearance** stores the result in separate `mtrpoint_appearance` data and synchronizes it to other clients.
5. **Test switch** is visual preview only; **Live** follows observed train state again. Unsaved previews are discarded when the screen closes.

## Resource-pack integration / 资源包适配

Mod 会读取当前启用的 Minecraft 资源栈，并优先使用轨型描述中声明的真实模型和材质。支持 MTR 原生轨型、OBJ/MTL、Blockbench 普通立方体模型，以及带 `modelGroups` 的轨型描述。轨型描述可以指定钢轨、轨枕、扣件、道砟、保留附件、钢轨截面、材质、UV 和近／中／远 LOD 模型。

The mod reads the active Minecraft resource stack and prefers the actual models and materials declared by a rail profile. It supports native MTR styles, OBJ/MTL, ordinary Blockbench cube models and profiles with `modelGroups`. A profile can define rail, sleeper, fitting, ballast and retained-attachment groups, rail cross-sections, textures, UVs and near/mid/far LOD models.

轨型描述的位置为 `assets/<namespace>/rail_profiles/<name>.json`。最小示例：

Rail profiles are stored at `assets/<namespace>/rail_profiles/<name>.json`. Minimal example:

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

Citizons Railway `0.1.1` 或更新版本可以通过 `modelGroups`、真实钢轨截面、方向别名、连续护轨端头和 LOD 模型为普通轨道及道岔提供完整材质。样式命名空间和方向后缀会规范化处理，不需要逐个资源包加入白名单，也不改变握手协议。

Citizons Railway `0.1.1` or newer can provide complete ordinary-rail and turnout materials through `modelGroups`, real rail cross-sections, direction aliases, continuous guard ends and LOD models. Namespaces and directional suffixes are normalized, so a pack does not need a per-pack whitelist and the handshake protocol remains unchanged.

## Detail distances / 精度距离

近／中和中／远两个分界点可以在“模组 → MTR Railway Point Advanced → 配置”中调整，默认值为 **4 m** 和 **12 m**，范围为 `0–4096 m`。第二个值必须大于或等于第一个值；两个值相等时跳过中精度档，两个值都为 `0` 时始终使用低精度档。配置只保存在客户端，保存后立即应用于普通轨道、倾斜轨道、道砟、枕木、扣件和道岔。

The high-to-medium and medium-to-low boundaries are configurable in **Mods → MTR Railway Point Advanced → Config**. Defaults are **4 m** and **12 m**, with a range of `0–4096 m`. The second value must be at least the first; equal values skip the medium tier, and two zeroes always select the low tier. The setting is client-side and applies immediately to ordinary rails, banked rails, ballast, sleepers, fittings and turnouts.

## Rendering and performance / 渲染与性能

世界网格使用 Minecraft 标准实体裁剪渲染类型，并在实体渲染阶段提交，兼容 Iris、Oculus 及 Optional Rail 的倾斜轨道采样。固定和活动部件分别缓存到持久 GPU 缓冲，按材质、距离档位和空间块批处理；视锥外几何跳过绘制，静止网格不会逐帧重建或上传。资源包材质、钢轨、道砟、岔枕和扣件因此使用同一套渲染路径。

World meshes use Minecraft's standard entity cutout render type and are submitted during the entity stage, which keeps Iris, Oculus and Optional Rail banking samples on the same rendering path. Static and moving parts use persistent GPU buffers and are batched by material, detail tier and spatial cell; out-of-frustum geometry is skipped, and unchanged meshes are not rebuilt or uploaded every frame. Rail, ballast, sleepers, fittings and resource-pack materials therefore share one rendering path.

这些优化针对已经确认的几何重建、GPU 上传和蓝图绘制热点。实际 FPS 仍取决于整合包、着色器、资源包和视距；可复现的回归结果见 [VALIDATION.md](VALIDATION.md)。

These optimizations target measured geometry rebuild, GPU upload and blueprint drawing costs. Actual FPS still depends on the modpack, shader, resource pack and view distance; reproducible checks are recorded in [VALIDATION.md](VALIDATION.md).

## Behaviour boundaries / 工作边界

- **MTR**：只读取轨道样式、轨道曲线和列车路径，不修改 Rail、PathData、连接、限速、寻路或列车位置。
- **BRsignal**：只读取现有授权快照和车辆快照推导视觉方向，不调用授权、放行、抢占、释放或信号显示写接口。
- **Optional Rail**：只使用已有的视觉曲线采样接口；采样失败时回退到 MTR 原生轨道几何。
- **平交口**：只生成交叉钢轨和轮缘槽，不新增连接、转线或冲突保护。
- **动画**：仅用于外观，不把动画完成作为列车放行条件。

- **MTR**: reads rail styles, rail curves and train paths without changing Rail, PathData, connections, speed limits, routing or train positions.
- **BRsignal**: reads existing authority and vehicle snapshots for visual direction only; it does not call write APIs for authority, release, reservation or signal display.
- **Optional Rail**: uses its existing visual curve sampling interface and falls back to native MTR geometry when sampling fails.
- **Crossings**: add visual crossing rails and flangeways only; they do not add connections, route changes or conflict protection.
- **Animation**: visual only and never a release condition for trains.

## Supported geometry and limitations / 支持范围与限制

当前稳定支持同平面 Y 形道岔、曲线道岔、三开道岔、不互通菱形／平交，以及四节点交叉渡线组合。几何枚举还覆盖 13 类现实布局；梯形道岔组、单／双交分中心和四臂复杂互通需要专用开通拓扑，不会静默按普通平交生成。不同轨距或不同钢轨截面在同一交汇处暂不支持精确过渡，极端短分支、极小交角、重叠交汇和过大高差需要按 [VALIDATION.md](VALIDATION.md) 的边界检查。

Stable support covers coplanar Y and curved turnouts, three-way turnouts, non-interlocking diamonds and flat crossings, and four-junction crossover groups. Geometry enumeration also checks 13 real-world layout families; ladder groups, single/double slips and four-arm junctions require dedicated opening topology and are not silently treated as ordinary crossings. Exact transitions between different gauges or rail cross-sections within one junction are not supported yet. Very short branches, tiny crossing angles, overlapping junctions and large height differences should be checked against [VALIDATION.md](VALIDATION.md).

自动识别到轨型并不等于每个第三方资源包都已单独验证。没有统一语义的复杂 Blockbench 网格、把隧道或桥梁和轨道放在同一模型内的资源，需要提供明确的轨型描述或在蓝图中手动选择样式。

Automatic rail-style recognition does not mean every third-party pack has been individually verified. Complex Blockbench meshes without semantic groups, or models that combine tunnels or bridges with rails, need an explicit profile or a manual style choice in the blueprint.

## Build and develop / 构建与开发

需要 JDK 17。MTR 编译依赖位于 `../MTR_BRsignal_addon/libs/MTR-forge-4.0.3+1.20.1.jar`。常用命令：

JDK 17 is required. The compile-time MTR dependency is `../MTR_BRsignal_addon/libs/MTR-forge-4.0.3+1.20.1.jar`. Common commands:

```powershell
.\gradlew.bat build --offline --no-daemon --console=plain
.\gradlew.bat regression --offline --no-daemon --console=plain
.\tools\runtime_probe.ps1 -RailPack -MtrOnly
```

`build` 包含离线几何回归并生成 `build/libs/mtr_railway_point_advanced-0.1.5.jar`；运行探针会在 `build/runtime-*` 创建隔离环境，不复制用户存档。资源包专项检查优先使用环境变量 `CITIZONS_RAILWAY_PACK`，否则读取同级 `MTR_Citizons_Railway` 子项目。

`build` includes offline geometry regression and produces `build/libs/mtr_railway_point_advanced-0.1.5.jar`. Runtime probes create isolated environments under `build/runtime-*` and do not copy user saves. Resource-pack checks prefer `CITIZONS_RAILWAY_PACK`, then the sibling `MTR_Citizons_Railway` project.

版本变化见 [CHANGELOG.md](CHANGELOG.md)，验证证据见 [VALIDATION.md](VALIDATION.md)。

Version changes are listed in [CHANGELOG.md](CHANGELOG.md), and verification evidence is recorded in [VALIDATION.md](VALIDATION.md).

## Compatibility and licence / 兼容性与许可

本 Mod 通过 Forge addon 和 Mixin 接入 MTR，不修改 MTR 源码或原始 JAR。源码采用 [MIT License](LICENSE)。第三方字体、MTR 资源和其他外部文件的许可与来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

The mod integrates with MTR through a Forge addon and Mixins without modifying MTR source or its original JAR. Source code is released under the [MIT License](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for licences and sources of third-party fonts, MTR assets and other external files.
