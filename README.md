# MTR Railway Point Advanced

![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-62a35a?style=flat-square) ![Forge 47.4.18](https://img.shields.io/badge/Forge-47.4.18-f59e0b?style=flat-square) ![MTR 4.0.3](https://img.shields.io/badge/MTR-4.0.3-3b82f6?style=flat-square) ![Version 0.1.6](https://img.shields.io/badge/version-0.1.6-2563eb?style=flat-square) ![License MIT](https://img.shields.io/badge/license-MIT-8b5cf6?style=flat-square)

MTR 的道岔外观扩展，当前版本 **0.1.6**。根据实际轨道曲线、坡度和倾角生成基本轨、尖轨、翼轨、护轨、岔心、轮缘槽、连杆、扣件及岔枕。自带默认模型和材质，也可使用轨道资源包。

A visual turnout addon for MTR, version **0.1.6**. It generates stock rails, switch rails, wings, guards, frogs, flangeways, rods, fittings and sleepers along the actual alignment, grade and cant. Default models and materials are included; rail resource packs are optional.

## Installation / 安装

需要 Minecraft **1.20.1**、Forge **47.4.18**、MTR Forge **4.0.3**。

Requires Minecraft **1.20.1**, Forge **47.4.18** and MTR Forge **4.0.3**.

将 `mtr_railway_point_advanced-0.1.6.jar` 放入客户端和服务器的 `mods` 文件夹；两端使用相同构建，不要同时安装多个版本。网络协议为 `3`。`point-runtime-probe` 是开发探针，不用于正式游戏。

Install `mtr_railway_point_advanced-0.1.6.jar` in both client and server `mods` folders. Use the same build on both sides and remove older copies. The network protocol is `3`; do not install the development-only `point-runtime-probe` in a normal game.

配套资源包为 **Citizons Railway 0.1.2**，启用后提供有砟、无砟有枕、无砟无枕及连续护轨样式。Mod 和资源包的版本号独立。

The companion pack is **Citizons Railway 0.1.2**, with ballast, slab, direct-support and continuous-guard styles. The mod and pack have separate version numbers.

## Usage / 使用

1. 按 MTR 原有方式铺轨。道岔分支使用同一节点，固定平交使用同平面相交轨道。
2. 客户端自动识别附近交汇并生成外观，无需额外方块或节点标记。
3. 按 **P** 打开 64 格内的岔区选择图，选中道岔后按 Enter 进入蓝图。
4. 调整轨型、几何参数或单根岔枕，点击“保存外观”。数据独立存储于 `mtrpoint_appearance`，并同步至其他客户端。
5. “试动尖轨”只用于预览；“实时”恢复跟随运行状态。退出界面会撤销未保存的预览。

1. Lay rails using MTR. Turnout branches share a node; fixed crossings use coplanar intersecting rails.
2. Nearby junctions are detected automatically; no extra block or marker is needed.
3. Press **P** for the junction map within 64 blocks, select a junction, then press Enter to open its blueprint.
4. Edit the rail style, geometry or individual sleepers, then choose **Save appearance**. Separate `mtrpoint_appearance` data is synchronized to other clients.
5. **Test switch** previews motion; **Live** follows operating state. Closing the screen discards unsaved previews.

## Features / 当前功能

- 同节点 Y 形、曲线和三开道岔，以及固定菱形交叉、平交和交叉渡线组合。
- 闭合尖轨贴合基本轨轨头，削薄段下部采用不对称 L 形截面，逐渐恢复完整工字形。
- 三开采用两组相邻进路尖轨联动；中间进路保留翼轨，省略冗余护轨。V 形岔心使用普通道岔的完整截面。
- 三种道床共用扣件布局；活动尖轨处普通扣件实际重叠时使用滑床，护轨共座替换该位置的普通扣件。有枕承接块随扣件座定位。
- V 岔心尖端仅省略内部扣件，外部护轨侧夹具、支架和底座保留；枕木、承接块及道床保留。
- 基本轨、尖轨固定尾端、翼轨、连续护轨和道砟的匹配接头补齐不超过 6 mm 的微缝，保留轮缘槽与真实间隙。
- 尖轨及可动岔心按 MTR、BRsignal 的只读运行状态显示视觉动画。

- Same-node Y, curved and three-way turnouts, fixed diamonds, flat crossings and scissors crossover groups.
- Closed switch tips meet the stock head; the planed lower section changes from an asymmetric L-section to the full I-section.
- Three-way turnouts use two complementary adjacent-route blade pairs. The middle route keeps wings and omits redundant guards; V frogs use the ordinary turnout section.
- All three beds share fitting placement. Overlapping blade fittings use slide plates; guard seats replace ordinary fittings, and slab blocks follow the final seats.
- Only fittings inside the V nose are omitted; outside guard clips, braces and plates remain, as do sleepers, support blocks and beds.
- Matching stock, fixed switch-heel, wing, continuous-guard and ballast joints receive patches for gaps up to 6 mm, preserving flangeways and real openings.
- Switch and movable-frog animation follows read-only MTR and BRsignal operating state.

## Resource packs / 资源包适配

读取当前启用资源栈中的材质和模型，支持 MTR 原生轨型、OBJ/MTL、普通 Blockbench 立方体模型和带 `modelGroups` 的轨型描述。描述文件位于 `assets/<namespace>/rail_profiles/<name>.json`，可声明截面、模型分组、材质、UV、LOD、连续支承及护轨端头。方向后缀会规范化处理，无需资源包白名单。

Models and materials come from the active resource stack. Native MTR styles, OBJ/MTL, ordinary Blockbench cubes and `modelGroups` profiles are supported. Profiles at `assets/<namespace>/rail_profiles/<name>.json` declare sections, groups, textures, UVs, LODs, continuous supports and guard ends. Directional suffixes are normalized; no pack whitelist is required.

最小截面描述：

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

复杂网格或包含桥梁、隧道的混合模型需要明确的轨型描述，或在蓝图中手动选择样式。Citizons Railway 0.1.2 的共享 OBJ 索引减少资源解析的重复记录；Mod 仍将各面转换为 `Mesh.Quad`，不会因此减少运行时面数。

Complex meshes or models combining rails with bridges or tunnels need explicit profiles or a manual style selection. Citizons Railway 0.1.2 shares OBJ indices to reduce duplicate parsing records; the mod still converts each face to `Mesh.Quad`, so indexing alone does not reduce runtime face counts.

## Detail and rendering / 精度与渲染

在“模组 → MTR Railway Point Advanced → 配置”调整客户端外观设置：

| 配置 | 默认 | 作用 |
|---|---|---|
| 界面缩放 | 100% | 蓝图及选择图缩放，范围 50%–125%。 |
| 近／中、中／远分界 | 4 m / 12 m | 第二个值不得小于第一个；相等跳过中档，均为 0 时仅使用低档。 |
| 道岔动画距离 | 24 m | 距离外冻结外观动画，0 冻结全部动画。 |
| 道岔接管距离 | 64 m | 距离外保留 MTR 原生绘制，0 关闭接管。 |

距离滑块范围为 0–256 m，配置文件允许 0–4096 m。保存后应用于客户端，不修改服务器行车逻辑。

Adjust client presentation in **Mods → MTR Railway Point Advanced → Config**. Distance sliders cover 0–256 m; config files allow 0–4096 m. Saving applies the settings locally without changing server operations.

| Setting | Default | Effect |
|---|---|---|
| UI scale | 100% | Blueprint and selection screens, 50%–125%. |
| High/mid and mid/low boundaries | 4 m / 12 m | The second must be at least the first. Equal values skip mid; both zero select low only. |
| Turnout animation distance | 24 m | Freeze visual animation beyond this distance; zero freezes all animation. |
| Turnout takeover distance | 64 m | Keep native MTR rendering beyond this distance; zero disables takeover. |

世界模型使用实体裁剪渲染路径，支持 Iris／Oculus，并读取 Optional Rail 的倾斜采样。静态与活动部件分别缓存，按材质、LOD 和空间批处理；未变化的网格不逐帧重建，接缝补面随源网格变化更新。实际帧率取决于资源包、光影和视距。

World meshes use entity cutout rendering with Iris/Oculus support and Optional Rail banking samples. Static and moving parts are cached separately and batched by material, LOD and space. Unchanged geometry is not rebuilt each frame; joint patches update when source meshes change. FPS depends on the pack, shaders and view distance.

## Limitations / 限制

- 只修改外观，不修改 MTR 连接、寻路、限速、列车位置或 BRsignal 授权和信号逻辑；动画不控制放行。
- 固定平交不新增转线连接或冲突保护。复式交分、梯线等需要专用开关拓扑的布局尚未实现。
- 普通及三开道岔检测到折返、分支高差或完全分离后的再交叉时保留 MTR 原生绘制。过密布局触发检测预算时本轮不接管；不能保证识别所有异形道岔。
- Optional Rail 采样失败时使用 MTR 原生几何。

- Visual changes only: no changes to MTR connections, routing, speed limits, train positions or BRsignal authority and signals. Animation does not control train release.
- Fixed crossings add neither route connections nor conflict protection. Double slips, ladders and other layouts requiring a dedicated switch graph are not implemented.
- Folded, vertically separated or recrossing Y/three-way branches retain native MTR rendering. Dense layouts exceeding detection budgets are not taken over for that pass; not every unsupported shape can be detected.
- Optional Rail sampling failures fall back to native MTR geometry.

## Build / 构建

需要 JDK 17，MTR 编译依赖为 `../MTR_BRsignal_addon/libs/MTR-forge-4.0.3+1.20.1.jar`。

Requires JDK 17 and the compile-time dependency `../MTR_BRsignal_addon/libs/MTR-forge-4.0.3+1.20.1.jar`.

```powershell
.\gradlew.bat build --offline --no-daemon --console=plain
```

构建包含几何回归，产物为 `build/libs/mtr_railway_point_advanced-0.1.6.jar`。资源包回归读取 `CITIZONS_RAILWAY_PACK` 指定的资源包目录，未设置时读取同级 `MTR_Citizons_Railway/resourcepacks/Citizons_Railway`。

The build includes geometry regression and produces `build/libs/mtr_railway_point_advanced-0.1.6.jar`. Pack tests use `CITIZONS_RAILWAY_PACK`, or the sibling `MTR_Citizons_Railway/resourcepacks/Citizons_Railway` when unset.

## Documentation and licence / 文档与许可

- [版本更新记录 / Version changelog](./CHANGELOG.md)：各版本相对前一版的变化 / Changes from the preceding version.
- [验证记录 / Validation](./VALIDATION.md)：构建、回归与实机反馈范围 / Build, regression and in-game evidence.
- [MIT License](./LICENSE)：源码许可 / Source licence。
- [第三方文件来源 / Third-party notices](./THIRD_PARTY_NOTICES.md)。
