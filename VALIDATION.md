# 验证记录

## 0.1.6 当前实现（2026-10-05）

发布构建：`gradlew.bat build --offline --no-daemon` 通过，包含几何回归、`reobfJar` 和 `reobfSmokeJar`。日志为 `build-release-016.log`，耗时 3 分 45 秒；本次构建涵盖当前几何、动画／接管距离配置和缓存改动。

`RENDER_CACHE` 验证相同回调只构建一次、分组缓存有界、空帧清理、返回原岔区复用网格及设置修改使缓存失效。资源包 0.1.2 简化高精度扣件后，LOD 回归改为每档至少减半且保留扣件；实际高／中／低单侧扣件为 774／278／39 面。此前配置未加载和客户端实例为空的回归失败已由当前源码修复，并由本次完整构建重新验证。

| 检查 | 当前结果 |
|---|---|
| `BLADE_CONTACT` / `BLADE_L_SECTION` | 内置及三种资源模型的闭合尖端贴合、打开间距、左右 L 形轨底和完整截面恢复通过。 |
| `THREE_WAY_SAFETY` / `THREE_WAY_SHAPE` | 左／中／右进路互补动作、削薄范围、中间翼轨保留、冗余护轨省略及两半枕木覆盖通过；折返、高差、密集布局回退通过。 |
| `FROG_WORLD` | 三开三个 V 尖端与普通道岔对比，每种轨型 9 个尖端采样通过；内置 6 个、各资源模型 8 个开放轨顶接缝采样补齐。 |
| `NODE_JOINTS` | 原生单元接头的 2 mm 缝补齐；尖轨固定尾端在 0／0.5／1 状态保持固定，接缝输入缓存复用。 |
| `BED_GUARD_JOINTS` | mainline、center_guard、outer_guard 三个 LOD 的道砟和护轨截面共 768 个 2 mm 接缝采样通过，40 mm 真实间隙保留。 |
| `V_OUTSIDE_FITTINGS` | 三种道床、普通道岔及三开的三个 V 尖端，两侧外部夹具和支架逐面保留，护轨底座保留；内部扣件表面积减少，非扣件支承面不变。 |
| `TURNOUT_FITTING_PARITY` | 三种道床五种岔枕布局逐面一致，扣件面数为 119370、120139、120139、120139、121876。 |
| `WING_SUPPORTS` | 普通和三开、普通及 V 形岔枕、三个 LOD 的剩余翼轨支架均有底座。 |

钢轨连续性、轮缘槽、三开世界合并、交叉渡线和动画拓扑等既有回归继续通过。共享 OBJ 索引属于配套资源包 0.1.2 的变化；本 Mod 仍按面生成运行时四边形，未完成该变化的实机性能对比。

用户已在客户端确认重复扣件、闭合尖轨、三开 V 岔心和节点／护轨／道砟缝隙修复正常。最后一次“V 尖端只裁内部、保留外部扣件”的修改通过自动检查，尚无更新后的实机反馈；未测量本轮 FPS。

此前矩形范围整套省略扣件、扭曲尖轨的贴合链及针对活动尖轨分段的尝试已替换或撤回，其检查结果不作为当前实现验收依据。

## 0.1.5 发布验证（2026-10-03）

- 版本元数据升至 0.1.5，网络协议保持 3；客户端和服务端仍要求使用同一构建。
- `gradlew regression --offline --no-daemon --console=plain`、`reobfJar` 和 `reobfSmokeJar` 通过。资源包探针中的样式选择、道岔绘制、资源重载和客户端／服务端交接均通过。
- 回归覆盖保存的内置 `default_3d` 样式被当前自定义资源包样式遮蔽的场景，以及 `_1`／`_2` 方向后缀命中规范化 LOD 的场景。
- LOD 配置回归覆盖默认 4 m／12 m、两个分界点相等、两个值为 0 和高精度档为 0 的情况。
- 本节记录的是 0.1.5 当前实现的构建和隔离运行证据；复杂存档中的专用道岔拓扑仍按下方几何边界单独验收。

## 0.1.4 发布验证（2026-09-30）

- 配套 Citizons Railway 0.1.1 或更新版本；`continuousGuard` 支持从 0.1.4 开始提供，网络协议仍为 3。
- `gradlew regression jar --offline --no-daemon --console=plain` 通过；包含资源模型、左右护轨端头、接续、坡度／倾角与岔区回退的静态回归。交付 JAR 为 `build/libs/mtr_railway_point_advanced-0.1.4.jar`。
- 本次未进入游戏验证。以下 0.1.3 及更早版本的运行记录为历史证据，不代表 0.1.4 的实机验收。

## 0.1.3 发布整理（2026-09-28）

发布版本由 0.1.2 升至 0.1.3，配套资源包为 Citizons Railway 0.1.0。安装说明、构建版本及运行探针产物路径已同步更新；网络协议保持 3。此前本文件中的 0.1.2 支撑／翼轨记录对应升版前的同一实现，日期和产物路径作为历史证据保留。

发布构建日志为 `build/release-013-build.log`：Java 17 离线 `build smokeJar` 全部通过，耗时 3 分 49 秒。生产 JAR 元数据为 0.1.3，未包含开发探针；交付文件为 `build/libs/mtr_railway_point_advanced-0.1.3.jar`。本次升版不改变已通过游戏截图验收的几何实现，未为纯版本变更重复游戏测试。

## 2026-09-28：补齐翼轨承座（0.1.2）

- 修复上一轮遗漏：Y 道岔支撑列表原先只有护轨，三开也过滤了翼轨。现在使用包含翼轨及编辑后端部形状的支撑列表；V 形和三开按各自轨道、枕木方向放置共用底板及腹板加强支架，替换该承座处的普通扣件。
- 新增翼轨回归在旧实现上明确失败（`build/wing-before.log`），修复后 Y 普通／V 形两种枕木的两根翼轨、三开的六根翼轨均有承座和支架，三个距离档位均通过（`build/wing-focused.log`）。
- `build/wing-build.log`：Java 17 离线完整 `build smokeJar` 通过，3 分 5 秒。`build/support-render-baseline/world-wing-final.sha256` 的 9 组最终世界非支撑几何与基线完全一致。
- 本次复跑 MTR + Optional Rail 隔离场景，`build/runtime-support-012/stdout.log` 的最终运行、支撑、资源重载和缓存检查通过；静止 64 帧内几何重建／上传均为 0。已查看 `pack-wing-base-close.png` 和 `pack-frog-base-close.png`；持久副本位于相邻 `railway_resource_checks/output/wing-supports-20260928/`。

产物仍为 `build/libs/mtr_railway_point_advanced-0.1.2.jar`。本次未修改钢轨或连杆几何、渲染入口、网络及行车逻辑，未 commit／push。

## 2026-09-28：岔枕平顶、尖轨滑床板与护轨共用承座（0.1.2）

- 本轮生产改动限定为枕木与扣件生成。多轨岔枕将模型中央下凹抬至承轨面，保留底面和倒角；普通轨枕保留原模型。逐顶点检查底面保留、共享顶点一致及顶面高度，修复最初截图中因底面被错误抬高造成的重叠黑纹。
- 尖轨活动段采用固定滑床板，基本轨外侧保留夹持；岔心相邻承轨座使用共用底板；护轨与相邻运行轨共用底板，并增加外侧腹板、加强肋和螺栓。底板从枕顶延伸至资源模型的真实轨底高度，近、中、远三个档位复用现有距离渲染。
- `build/support-verified-build.log`：Java 17 离线 `build smokeJar` 完整通过，耗时 3 分 9 秒。最终测试断言调整另由 `build/support-focused-final.log` 验证通过，覆盖普通枕木、平顶底面、V 形支撑、尖轨三个位置、坡道、三开、单枕移动／删除和距离档位。
- `build/runtime-support-mtr-only-012/stdout.log` 与 `build/runtime-support-012/stdout.log`：仅 MTR、MTR + Optional Rail 两种隔离环境均通过资源读取、轨段连续性、倾斜轴一致、岔区接管、接缝、静态缓存、固定支撑、资源重载及最终运行检查。两种环境分别观察 65／39 个静止帧，几何重建与上传均为 0；三个距离档位均实际绘制。这不是整合包 FPS 对比测试。
- 12 组原始非支撑几何与 HEAD `8b08dacc` 一致。最终世界渲染对比按真实资源加载中／远距离扣件模板，9 组 Y／V 形／三开及不同尖轨位置的全部非支撑面数量、顶点和 UV 完全一致（`build/support-render-baseline/world-runtime-head.sha256` 与 `world-runtime-final.sha256`）。旧的只加载近景模板的临时脚本将扣件错误归入钢轨合并流程，不能用它断言实际游戏钢轨发生变化。
- 已逐张查看零倾角滑床板、护轨承座、岔心底板和倾斜道岔截图。持久截图目录为相邻工作区的 `railway_resource_checks/output/turnout-supports-20260928/`，原始截图和日志保存在上述隔离运行目录。

交付 `build/libs/mtr_railway_point_advanced-0.1.2.jar` 与两次游戏验证使用的 JAR 内容一致。未安装到真实客户端或修改用户存档；本轮未 commit／push。极端交角及完整整合包实际线路不在本轮截图验收范围内。

## 2026-09-28：Citizons Railway 资源模型、连续轨面与距离细节（0.1.2）

- OBJ 分组读取保留钢轨、轨枕、扣件和道砟的模型材质及 UV；普通轨道和道岔使用同一截面。共享端点扫掠修复坡度／倾角渐变断口，并处理 MTR 双圆弧重复相位重启。资源包道砟最大厚度为 0.35 m，与轨层共用倾斜轴。
- 样式 ID 别名统一解析；端点枕木留量和局部间距调整同步移动扣件。外侧基本轨承座优先于邻近内轨去重，每个扣件按自身钢轨切线定向。`STOCK_FITTINGS` 覆盖镜像和交换分支，共 128 个承座，最大定位误差 `7.616489811865217E-8 m`。
- 距离档位为 4 m / 12 m，普通支承和道岔扣件均在真实渲染中触发三档。静止 33 帧内自定义几何重建和 PointGpu 上传均为 0；这不是对比 FPS 测试。
- `build/stock-fitting-build.log`：完整构建与主回归通过（3 分 30 秒）。`build/stock-fitting-photo-build.log`：补充俯视机位的探针构建通过（26 秒）。
- `build/runtime-pack-012/stdout.log` 与 `build/runtime-pack-mtr-only-012/stdout.log`：MTR + Optional Rail／仅 MTR 两种隔离环境的 `PACK_PROFILE`、`PACK_CONTINUITY`、`PACK_BANKING`、`PACK_TURNOUT`、`PACK_HANDOFF`、`PACK_SEAMS`、`PACK_PERFORMANCE`、`PACK_RELOAD`、`PACK_RUNTIME` 均通过。连续截面分别检查 4155／4170 点，最大接缝为 0；四种样式别名的隐藏边界一致。
- 零倾角道岔整体俯视、入口俯视、外侧扣件近景与坡度渐变截图，以及可移植验证摘要，随 `MTR_Citizons_Railway` 配套仓库保存。原始运行日志留在上述本机构建目录。

正式产物仍为 `build/libs/mtr_railway_point_advanced-0.1.2.jar`。本轮修改渲染、资源读取和验证，不修改网络握手、线路连接或服务端行车逻辑；隔离测试不等于完整整合包原存档的性能或真实列车运营验收。

## 2026-09-27：渲染交接端面与内侧灰块（0.1.2）

本轮只修改端面生成与裁切后的补面，不改交点检测、轨道图或尖轨动作。证据目录：`build/endcap-patches-20260927/`，包含修改前、第一项完成后、最终源码快照与本轮差异。

- **交接处错误端面**：取消 Y 固定轨窗口、平交／三开走行轨路径端点的封面；通用固定轨扫掠也不保留分段梁的横向端板。护轨及翼轨的真实自由端仍封口。新回归先复现旧行为（`handover-before.log`），修复后 16 处边界检查和 1,536 个基本轨外缘连续性采样通过（`handover.log`）。
- **内侧灰块**：旧代码从每个侧面推算整根轨条并重新补全工字形截面，15° 夹具明确复现补面伸出原轨头（X=0.0400359 m，允许半宽 0.034 m，`patches-before.log`）。现仅以轨顶面定位截面，并把补面限制在来源面的平面范围内；采样裁切单元的前后界面不再生成伪端面。原生 OBJ 的开放截面按实际顶点高度／侧面轮廓闭合，不再臆造 36 mm 厚轨头，也不把开放边链误当封闭轮廓。通用、完整原生夹具、实际 MTR 五面模型，15°／30°／60°／90° 共 1,266 个补面点通过（`patches.log`）。
- **完整验证**：`smokeJar build --offline` 全部通过，4 分 43 秒（`build-final.log`）。原基本轨连续性、翼轨连接、连接杆高度、护轨合并、V 交汇、动画及轮缘槽回归继续通过；生产 JAR 不含测试类、探针或测试模型 JSON。
- **隔离游戏**：读取 `MTR_test` 的轨道定义和外观数据副本，在全新探针世界绘制，共 33 条轨道、30 份外观。1,286 个平交槽点、6,224 个道岔槽点均无堵塞；1,536 个翼轨连接点与 116 个护轨端部点无缺失，`POINT_SAVED_LAYOUT: PASS`。Connector／BRsignal 组合亦通过 `POINT_WORLD_FINAL: PASS` 与 `POINT_BATCH_SAVE: PASS`。保存布局截图同时保留外露工字形端面供目检。

正式产物：`build/libs/mtr_railway_point_advanced-0.1.2.jar`。未安装到真实客户端、写入用户存档、commit 或 push。隔离绘制使用实际保存轨道及外观，地形和镜头由探针生成，不等于在原存档中开展真实列车运营测试。

## 2026-09-27：基本轨共线交接与交点工字形 V 拼接（0.1.2）

针对新红箭头截图继续修复。前一轮通过的宽范围覆盖检查未能排除这类毫米级台阶，前一轮记录不作为该截图已解决的证据。续作前源码快照为 `build/handover-v-crossings-20260927/before.zip`。

- 基本轨固定部分原先复用尖轨起点的 25 mm 路径分离阈值，在第二条路径已经偏离时仍由第一条路径代画，然后突然切换。新增独立的共线末端定位（距离容差 1 µm），让第二条路径的外侧基本轨从共同引入段末端开始连续绘制，第一条路径在同一处交接；Y 和三开均应用。尖轨起点、削尖、横移和动作公式不变，MTR 原生隐藏仍遵守既有接管窗口，不提前放回原生轨段。
- 共用交汇构建器对各钢轨完整工字形截面按 V 型角平分界拼接，与固定岔心的处理一致，去掉内部拼接处分配整截面端盖的做法。外露轮缘槽切面由真实截面剪出，不再把轨头边线向下拉成矩形板；斜切封面限制在原钢轨段实际范围内，防止喇叭口斜向截面延伸到别处。
- 新 `StockApproachRegression` 在轨头外缘内侧 1 mm 直接采样，覆盖 Y／三开、镜像／反向、短／18 m 共线引入段、通用／原生截面、预览／世界网格，共 1,536 点通过。原尖轨动作、削尖和翼轨连续折弯回归继续通过。独立专项日志 `stock.log`，53 秒通过。
- 新 `VSectionIntersectionRegression` 在修改前明确复现 15° 交点的矩形侧壁填入轨腰空隙（`v-before.log`）。修复后 15°／30°／60°／90°、方向反转、通用／原生截面、预览／最终世界网格的 57,625 个轨腰表面点通过；另有五种交角的轨头覆盖、轮缘槽、工字形端面和截图保存布局连接检查通过。独立专项日志 `v.log`，32 秒通过。

专项入口为 `HandoverVRegression`，使用本轮目录的 `gates.gradle` 和 `-Pgate=stock|v`；两项新检查均纳入完整主回归。本轮生产代码只变更 `TurnoutFrame`、`PointMesh`、`ThreeWayMesh`、`DiamondGeometry`。探针将远节点基本轨机位移近，以便核对小接缝和连接杆高度。

最终结果：

- `gradlew.bat smokeJar build --offline --no-daemon --console=plain` 通过，包含全部回归、编译、重混淆及打包，耗时 4 分 9 秒。日志 `build/handover-v-crossings-20260927/build-final.log`。
- 同一最终 JAR 在仅 MTR、Connector／Forgified Fabric API／BRsignal／Optional Rail／MTR 两种隔离环境均通过 `POINT_WORLD_FINAL: PASS` 和 `POINT_BATCH_SAVE: PASS`。本轮日志归档为 `runtime-mtr-only.log`、`runtime-connector.log`。
- 已核对近景 `point-remote-bar-left.png`、独立平交 `point-diamond-close.png`；近景和两动作位置截图归档至本轮目录 `screenshots`。仅 MTR 和 Connector 各自的完整截图仍保存在其隔离运行目录。
- `git diff --check` 通过，正式 JAR 不含开发探针或回归测试类。源码完成快照 `after.zip`，与日志一起保存在本轮目录。

正式文件为 `build/libs/mtr_railway_point_advanced-0.1.2.jar`。未 commit／push，未替换真实客户端 mods 或加载用户存档。上述验证涵盖复现夹具、保存布局几何和生成游戏场景，尚未在用户这张截图对应的原存档位置实景复验，不将其表述为全部第三方资源包或真实运营验收。

## 2026-09-27：续作四项外观修复（0.1.2）

本轮从引用会话的未完成工作区继续，续作前源码备份为 `build/four-fixes-20260927/before-resume.zip`。不回退已有未提交修改。相对该备份，生产代码只修改 `Mesh`、`PointMesh`、`ThreeWayMesh`、`FrogGeometry`、`GuardRails` 和 `PointSelectionScreen`；没有继续改动平交判定或通用钢轨裁切核心。

1. **外侧基本轨微缝**：相邻采样段共用端点和完整截面法向，包括原生模型顶点。专项检查覆盖 232 处 Y／三开、镜像、通用／原生截面接缝，最大边缘误差 `2.220446049250313E-16 m`。直线／曲线剪式渡线各 32 条轨道侧线、Y 型 16 条预览／世界／动作侧线（4,224 个覆盖点）通过原覆盖检查。日志：`stock.log`，独立进程通过，耗时 24 分 51 秒。
2. **尖轨后续轨到翼轨的连续折弯**：取消折点后多出的直向第三分支；保留到翼轨的连续折弯，并恢复固定心轨延伸到两侧后跟。恢复原有完整固定心轨截面断言。84 个折弯连续点、无第三分支、两侧后跟覆盖检查通过；用户截图保存布局的 768 个连接点与 3,112 个轮缘槽点通过。日志：`frog.log`，独立进程通过，耗时 1 分 34 秒。
3. **连接杆降低**：连接杆底面在枕木顶面上方 10 mm、顶面上方 50 mm，原生轨型使用模型中的实际枕木高度。新增测试先在旧实现复现原生模型间隙错误（`rod-before.log`），修复后 12 个 Y／三开、通用／原生、三个动作位置通过，并继续验证坡道动作。日志：`rod.log`，独立进程通过，耗时 2 分 8 秒。
4. **护轨 UI 合并和两端内收**：UI 允许世界几何并集已经连通、但不能整体重映射到单一路径的部分重叠曲线；保留各源区间，判断端点是否向外继续来消除内部喇叭口，恢复旧合并数据的外端内收。局部重叠、中心线横向相差 50 mm 的部分轨头重叠、反向、镜像、跨轨道 ID 首尾衔接、三段连通通过；9 组夹具共 18 个外端内收在最终网格中保留，无内部喇叭口或重复轨头。不同高度、横向交叉、分离区间及相反侧拒绝合并。6,656 点既有护轨并集和截图复现检查继续通过。日志：`guard.log`，独立进程通过，最终耗时 51 秒；初轮日志保留为 `guard-initial.log`。

上述日志位于 `build/four-fixes-20260927/`。专项入口为 `FourFixRegression`，通过该目录的 `gates.gradle` 和 `-Pgate=stock|frog|rod|guard` 分别运行。新增检查同时接入主回归；修复旧 `StockRailIntervalRegression` 只记录失败但不使进程失败的遗漏。

最终验收结果：

- `gradlew.bat smokeJar build --offline --no-daemon --console=plain`：最终源码编译、重混淆、打包和完整主回归通过，耗时 5 分 45 秒，日志 `build/four-fixes-20260927/build-final.log`。该次包含最后补充的部分轨头重叠用例。前一版完整构建通过的日志保留为 `build-initial.log`。
- 最终 JAR 分别在仅 MTR 环境和 Connector／Forgified Fabric API／BRsignal／Optional Rail／MTR 组合环境中通过 `POINT_WORLD_FINAL: PASS`；两处外观的服务端保存、回执、编辑器重开核对均通过 `POINT_BATCH_SAVE: PASS`。最终日志复制至 `build/four-fixes-20260927/runtime-mtr-only.log` 和 `runtime-connector.log`，原日志仍在对应 `build/runtime-*-012/stdout.log`。
- 已核对最终游戏截图 `point-close.png`、`point-asymmetric-wing.png`、`point-asymmetric-three-close.png`，以及连接杆位置截图。截图位于对应隔离运行目录的 `screenshots`。
- `git diff --check` 通过；正式 JAR 内确认不包含开发探针或回归测试类。源码完成快照为 `build/four-fixes-20260927/after-fixes.zip`。

正式产物：`build/libs/mtr_railway_point_advanced-0.1.2.jar`。专项和游戏测试覆盖列出的夹具、截图保存布局及隔离世界，不等于所有第三方资源包或原存档真实列车运营验收；本次未测试服务端重启后的重新载入。未 commit、push、安装到真实客户端或修改用户存档。

## 2026-09-26：审查发现的五项缺陷修复（0.1.2）

- 长护轨先合并完整区间，再只保留真实外端内弯；未编辑翼轨进入统一合并池，三开视图不再复制未合并翼轨。保存布局审计 `SAVED_GUARDS samples=158 missing=0`、`checked=1202 missing=0`。
- 端面材质改为 `rail_end.png`，按原生模型或内置工字截面生成并检查截面、材质和 UV；`SCREENSHOT_NATIVE_ENDS`、`CapFaceRegression` 通过。
- `GeometryTypeEnumerationRegression` 枚举 13 类现实布局：10 类通过分类与有限网格检查（其中 8 类实际生成交汇几何，窄轨并行和立交正确分类为无需平交几何）；梯形道岔组、单/双交分中心、四臂互通明确报告为需要专用开通拓扑。

- 多道岔编辑器现在接收外观回执，按道岔 ID、修订号及本次发送的参数匹配保存结果。成功后继续下一项；权限拒绝、距离限制、版本冲突和超时停止队列并保留草稿。无关广播不再消费当前请求。
- 交叉判定改为折线段求交，在交点插值检查高度和交角，消除 0.25～1 米采样步长相对 0.08 米容差导致的漏检。输入顺序、轨道反向及小幅平移不改变结果。
- 平交替换窗口使用真实外观设置；窗口、区域成员或 owner 变化时刷新网格。停用区域会清除旧归属。移出采样范围的轨道在计算新裁切集合前清理。
- 后处理轮缘槽裁切使用轨道的世界高度平面，包括坡度和外观高差。槽底以下保留轨底；原生截面及通用截面使用同样的高度判断。共面裁切加入浮点容差，使整体移动到不同世界高度时拓扑一致。
- 动画的各个姿态一起裁切，按来源面与裁切路径配对碎片；缺失碎片只补退化点，不丢弃有效面。静态面复用，转辙时仍插值缓存顶点。封口限定在实际切口内部，通用轨的外露末端使用完整工字截面。

新增 `ReviewFixRegression`、`AppearanceSaveRegression` 并接入主回归。覆盖连续两项保存、拒绝／冲突／超时、交点平移与方向反转、覆盖长度修改及重载、0.24 米轨段被多个通道裁切后的全部有效面、动画中间位置、负高度与坡道、轨底保留，以及带实际外部裁切轨道集合的 View 动画。原 `StockRailIntervalRegression` 现在确实设置两种尖轨位置。

世界网格复现：同一 Y 道岔在 Y=-40、0、64 各检查 644 个轮缘槽点，均无钢轨占用；修复前 Y=-40 为 44/644 点堵塞。此次修复针对已复现代码缺陷，尚不能据此宣布用户原存档全部复杂道岔、第三方轨型或真实列车运营完成验收。已有斜封口朝向的角点投票提示和三开近尖轨重叠候选仍需实景判断，不把这些 NOTE 当作已关闭缺陷。

本次验证结果：

- `gradlew.bat build smokeJar --offline --no-daemon --console=plain`：完整构建和主回归通过，耗时 10 分 19 秒。日志：`build/review-20260926/build-final.log`。
- 修正旧游戏探针的缩放点击坐标、枕木菜单字段及选择器视口边界后，`gradlew.bat smokeJar --offline --no-daemon --console=plain` 通过。正式 Mod 的源码未因探针坐标调整而改变。
- `tools/runtime_probe.ps1 -World -MtrOnly`：通过至 `POINT_WORLD_FINAL: PASS`。真实 Forge 网络下两处道岔依次保存，服务端 SavedData 中两项值一致，重新打开编辑器仍一致（`POINT_BATCH_SAVE: PASS`）。同时通过原生普通／存车线截面、三开动画、剪式渡线及 GPU 缓存检查。日志：`build/runtime-mtr-only-012/stdout.log`。
- `tools/runtime_probe.ps1 -World -Connector`：MTR、BRsignal、Optional Rail、Connector 与 Forgified Fabric API 组合通过至 `POINT_WORLD_FINAL: PASS`，批量保存亦通过。日志：`build/runtime-connector-012/stdout.log`。两种环境的直线／曲线剪式渡线分别通过 863／991 个世界网格轮缘槽采样点检查。
- `git diff --check` 通过。修改保留在工作区，未执行提交或推送。

游戏探针使用隔离的测试世界，不加载用户存档。服务端数据核对覆盖本次运行的 SavedData 与编辑器重开，尚未覆盖服务器重启后重新载入；测试轨道由探针提供，不等同于真实列车运营验收。正式产物为 `build/libs/mtr_railway_point_advanced-0.1.2.jar`；开发探针 JAR 不用于正式安装。

## 2026-09-17：最新实景反馈与暂停状态（0.1.2）

用户确认以下情况仍存在钢轨未切断的问题：Y 型单开道岔、部分复杂道岔、平交轨道。应形成辙叉断口或轮缘通道的位置仍可能保留连续钢轨。因此，“钢轨裁切及轮缘通道已在全部道岔／平交布局中修复”不成立，此项仍为未解决问题。

此前日志中的 PASS 仅适用于列出的程序生成夹具、采样点和隔离游戏场景；保留这些历史记录，但不得用其否定最新实景反馈。具体触发条件和根因尚未确定，本次不新增根因推断，也不将问题归因于用户轨型或铺轨方式。

按用户要求暂停进一步代码修改。本次仅更新文档，将既有实现、资源、测试和工具作为当前开发检查点提交并推送，版本仍为 0.1.2；提交信息使用英文。此次文档更新没有重新运行构建或游戏测试，下方历史验证结论均需结合上述未解决问题理解。

后续恢复修复时，应先保留并复现用户实际出问题的布局，再验证所有相关分支的钢轨断口、轮缘通道及原生模型替换边界；在实景确认前不将此项标记为已修复。

## 2026-09-17：双侧节点、非对称三开、各侧岔枕收尾与分区缓存（0.1.2）

- 检测不再排除总计超过四股轨道的节点；分别识别同节点两侧的 2+2、2+3、3+2、3+3，三开分支顺序按实际分离区域确定。较宽 Y 型夹角也纳入检查。
- 三开辙叉由三组独立模型改为六根走行轨、翼轨、护轨的共同截面与轮缘槽裁切。可动心轨预留完整运动范围，临近心轨按各自区域裁切，保留稳定动画面槽；合并后的护轨不再被世界渲染重复添加。
- 三开连续岔枕按两个相邻分支分别决定何时分离，外侧轨道的普通枕木包络分开后，停止把该侧岔枕延长到中间股。非对称测试的两侧连接末端为 20.30 / 14.54 米。终端按每股原生重复单元独立对齐，再检查两处钢轨承座下的实际枕木覆盖，补齐 V 臂投影留下的空档。
- 护轨／岔枕合并缓存按相互接触的几何区域拆分；修改或移出一处不再重建所有不相邻区域。取消第二套 128 米成员阈值，避免玩家穿越阈值时重复销毁缓存。GPU 更新前做视锥检查，跳过不可见模型的光照扫描；外观改变后仍刷新边界。

验证：`build smokeJar --offline --no-daemon` 包括全部几何回归和重混淆。新增 `FollowupRegression` 检查双侧 4/5/6 股节点、非对称三开固定及可动三个位置的 56,000 个轨头采样、稳定动画面数、五种岔枕模式的终端双承座覆盖，以及两侧不同的连续岔枕终点。Blender 预览保存于 `build/previews/three-asymmetric-*.png` 和 `.blend`。

最终 Connector / BRsignal 隔离客户端通过 `POINT_WORLD_FINAL: PASS`。真实 MTR 曲线组成的 3+2 节点识别为两个独立编辑对象，原生立体轨道可动三开三个位置面数一致；已检查 `point-dual-asymmetric-top.png`、`point-asymmetric-three-close.png` 和可动版本截图。日志为 `build/runtime-connector-012/stdout.log`。

该次运行中，两处独立区域初次构建共 24.669 ms，修改一处为 12.4223 ms，只重建一处且保留另一处网格；1000 次无变化缓存调用总计 0.5484 ms、重建 0 次。32 个静止模型、85,184 面的隔离 CPU 提交循环中位数 0.2281 ms、P95 0.2886 ms，顶点上传 0 次。

性能检查针对缓存重建与提交开销；首次加载复杂区域仍需生成网格，不能将这些数字当作完整整合包的 FPS 测量。未修改 MTR 路径、BRsignal 授权或信号逻辑；未安装到真实客户端、修改用户存档、commit 或 push。

## 2026-09-17：V 枕木、连杆与组合渲染重叠（仍为 0.1.2）

- 交叉渡线中央 V 型枕木读取本线及两条斜向渡线，逐股建立正交支臂，在公共斜接面连接；原先仅使用两条本线，平行本线时选择 V 型仍是直枕木。
- 独立平交口按公共轴投影一次枕木间距，取消等弧长平均后再投影造成的间距压缩。直角场景新增沿轨道的 0.6 米节距和重复顶面检查。
- 护轨保留同轨区间合并，另外对不同轨道上局部重合的护轨截面做几何并集，消除内部端部外撇。重合等距截面指定单一所有者，避免两边同时被裁掉；不同世界高度不合并。
- 世界渲染统一缓存枕木、扣件、翼轨及护轨，对共面重叠表面裁切并保留 UV。各道岔的 GPU 缓冲不再重复提交这些部件。缓存随视图、外观或分组变化更新，尖轨动画不触发重建。
- 连杆按水平切向与实际活动尖轨曲线求交，避免坡度引入俯视偏斜；第二分支尖轨起点由实际位置投影获得，不再直接套第一分支弧长。

验证：`AssemblyRegression` 覆盖 6,656 个局部重叠护轨采样、枕木并集覆盖范围／重复表面、斜向支臂、独立平交节距及坡道连杆五个活动位置；原有几何回归继续运行。游戏探针新增同机位 V 型／平行枕木对照和节点后约 31.8 米才分岔的 MTR 实际曲线，分别保存 `point-v-composite-top.png`、`point-parallel-composite-top.png`、`point-remote-bar-left.png`、`point-remote-bar-right.png`。

最终 `build smokeJar` 和 Connector／BRsignal 隔离游戏均通过，日志 `build/runtime-connector-012/stdout.log` 记录 `POINT_WORLD_FINAL: PASS`。同一组合连续 100 次缓存调用重建为零；静止 GPU 更新无顶点上传。新截图位于该运行目录的 `screenshots`，已检查 V 型／平行、远节点连杆及独立平交近景。以上并非整个整合包的 FPS 测量。

这里只验证列出的生成场景，尚未重现用户原存档里的每一组节点与外观参数；不能据此宣称所有复杂岔区的外观已经完成验收。未修改 MTR／BRsignal 行车逻辑，未安装到真实客户端，未 commit / push。

## 0.1.2：交叉渡线本线轮缘槽和共享边界护轨

中央辙叉区域将两条本线与两条渡线的轨头统一纳入截面合并、轮缘槽裁切，避免本线参与交汇时仍保留整条钢轨堵住槽。护轨先按完整区间合并、保留原端部形状，再裁到共享边界；不在人工分界处重新生成喇叭口。修正合并时轨道方向规范化后，仍使用原方向距离判断区间是否进入中央区域而漏画护轨的问题。

- 几何回归覆盖直线、弯曲、四节点错位的非对称交叉渡线，分别通过 1,068、1,013、1,434 个四条轨道的轮缘槽检查；非对称场景另有 185 个本线护轨连续性采样。接缝检查排除真实轮缘槽，仍检查其余钢轨承载面连续。
- 离线 `build smokeJar` 包括回归与重混淆。游戏探针新增对整个共享区域四条轨道的轮缘槽检查，而非只检查中央两条渡线的四个交点。
- 最终 Connector / BRsignal 隔离游戏通过 `POINT_WORLD_FINAL: PASS`；直线／曲线组合分别检查 863／991 个空轮缘槽采样点。已更新并检查 `build/runtime-connector-012/screenshots/point-crossing-close.png`。这些检查针对渲染网格，不代表真实轮轨动力学或完整整合包原存档验收。
- 保持 0.1.2，只修改渲染几何及验证；未 commit / push，未安装到真实客户端或修改用户存档。

## 0.1.2：进路提前转辙、GPU 缓存、岔区选择及三语界面

本次保持 0.1.2，只有本项目的显示观察、网格、界面和资源发生变化。未改动 BRsignal 工程或信号逻辑。

- 编译、重混淆、生产 JAR 与 smokeJar、全部几何回归通过。
- BRsignal 授权快照中的 `traversals()` 是子集，授权从节点开始时可能没有到达节点的上一段。观察器现在读取 `path().getTraversals()`，再按有效 `[startDistance, endDistance)` 筛选节点边界。这与 `ServerAspectManager` 使用同一批已发布授权，但不读取灯色来推断方向，也不写入授权。
- 测试用公开快照结构夹具复现“授权子集只含出轨”的边界：车未占用时可以得到方向并启动动画；授权终点正好止于节点时不会切下一股。实际 BR 环境还通过快照传输、占用优先、冲突保持、反向选择及三开方向检查。**尚未用真实运行列车验证从红灯到 permitted 的完整运营过程**。
- 默认动画时长 1 秒；双开连杆端点使用与活动尖轨相同的横移曲线，三开增加两组活动连杆。检查默认值和两个位置的连杆网格不同；既有手调时长不强制覆盖。
- 世界固定部分缓存为 GPU 顶点缓冲；活动顶点单独更新，块光每 20 tick 检查且只在变化时重传，视锥外跳过绘制。模型离开检测集时释放 GPU 缓冲，上传暂存区重复使用。交叉渡线分组只在几何／启用成员变化时重算。蓝图输入有 120–180 ms 合并更新。
- GPU 探针：静止模型 1,000 次更新不上传任何顶点；32 份完整普通立体道岔（每次 87,424 四边形）连续 35 次绘制提交，预热 5 次后测 30 次。带 Connector／BR 的一次记录中位数 0.7465 ms、p95 1.0493 ms；最终仅 MTR 记录中位数 0.7414 ms、p95 1.4783 ms；静态上传均为 0。这里测的是复用同一几何的 32 份缓冲的 **CPU 提交时间**，不包括模型首次生成、整合包其他 Mod 或 GPU 完成耗时，不能换算为真实岔区 FPS。
- 岔区选择图使用采样轨道中心线；交叉渡线的四个 Y 和中央平交口保留五个编号、独立编辑器。Tab / Enter 的选中 ID 验证通过；增加初始自动取景与选择项跟随，避免选中的编号离开视口。最终仅 MTR 探针进一步断言五个编号全部在初始视口内，并检查最终截图。鼠标编号／列表选择、缩放、平移、页签和“返回选择图”均为游戏内 UI。
- 三语 JSON 各 111 个非空、相同键；日文和英文页面实际切换并截图检查。参数提示解释单位、自动值和增量方向。语言文件直接维护，贴图生成脚本不再覆盖翻译。
- 已在 `build/runtime-connector-012` 和 `build/runtime-mtr-only-012` 的独立新建平坦世界通过 `POINT_WORLD_FINAL: PASS`；生产 JAR 中没有开发探针类。真实客户端 mods 和用户存档均未修改。

证据：对应目录 `stdout.log` 中的 `POINT_PERMISSION`、`POINT_GPU`、`POINT_DENSE_GPU`、`POINT_SELECTOR`、`POINT_LANG`；截图目录的 `point-selection-scissors.png`、`point-direct-tabs.png`、`point-ui-ja.png`、`point-ui-en.png`。完整整合包原存档的密集岔区 FPS、首次进入时的网格构建峰值，以及真实运营授权变化仍需实景验收。

## 0.1.2：V 形枕木、非对称翼轨及护轨合并

V 枕木原先求两股轨道在相同弧长处的法线交点，接近平行时交点远离轨道，并在 3 米阈值突然回退。现在先确定轨道之间的内部接缝，再求各自垂直支臂所在的位置；三开中间臂的两个接缝也采用内部定位，取消远交点回退。翼轨入轨段和工作段均沿实际曲线，折点由入轨曲线与对侧偏移曲线的交点确定；可动岔心接触点跟随实际翼轨折线。

`GuardRails` 把护轨表示为轨道上的区间。世界渲染对同一轨道（包括反向采样）、同侧、同截面、同高度和同横向位置的重叠区间取并集，只保留外端喇叭口。不同道岔仍分别保存外观参数，蓝图展示本处的独立参数；合并结果在可见组合或外观改变时缓存重建。不同侧／高度／截面不强行合并。

验证记录：

- 离线 `build smokeJar` 和几何回归通过。新增直线＋曲线、曲率换向、两股同向弯曲、弯曲三开、直线／弯曲交叉渡线枕木包络检查；工作翼轨横向误差小于 1 mm，入轨／工作段接头误差小于 0.01 mm（程序生成测试曲线）。
- 护轨回归覆盖反向轨道、部分重叠、内部喇叭口消除、连续且无重复的轨顶，以及不重叠／异侧／不同高度的独立保留。
- 游戏探针新增 MTR 实际 API 生成的一股直线、一股曲线场景；截图为 `point-asymmetric-blueprint.png` 和 `point-asymmetric-wing.png`。同时检查弯曲交叉渡线、原生普通／侧线、三开、BR 观察及保存同步。
- 渲染缓存探针验证重叠视图的护轨面数与单份一致，连续 100 次未变化调用合并重建次数为 0，日志标记 `POINT_GUARD_CACHE: PASS`。
- 最终构建在 `build/runtime-connector-012` 与 `build/runtime-mtr-only-012` 均完成 `POINT_ASYMMETRIC: PASS`、`POINT_GUARD_CACHE: PASS` 和 `POINT_WORLD_FINAL: PASS`。仅 MTR 环境 1,000 次原生动画更新约 11.30 ms，静态重建 0 次；这仍是局部 CPU 检查，不是完整整合包帧率测量。
- Blender 预览为 `build/previews/asymmetric-curved-bearer-fix.png`、`scissors-curved-bearer-fix.png`，同目录保留可编辑 `.blend`；脚本为 `tools/render_turnout_fixes.py`。

保持 0.1.2，正式产物仍为 `build/libs/mtr_railway_point_advanced-0.1.2.jar`。没有修改线路连接、寻路、PathData 或 BRsignal 授权／信号逻辑；未安装到真实客户端，未 commit / push。

## 0.1.2：平交辙叉及交叉渡线四端修正

普通平交原来按 0.24 米步长跳过整段钢轨，留下钝头缺口。现在由 `DiamondGeometry` 合并等宽原生截面，按实际交角裁出连续轮缘槽，形成固定 V／K 交叉区及内侧翼轨／护轨；保留原生侧线的圆角轨肩和逐顶点 UV。生成只发生在外观网格缓存重建时。

交叉渡线另有两处问题：中央区取道岔辙叉后跟投影的一半，导致外侧辙叉尖端被裁掉；曲线中先截断于中心线距离再按平面裁切，使横向偏移的轨头接缝缺一段。现在中央边界位于相邻道岔完整翼轨／护轨之后，轨条先超出公共平面再裁齐。

本轮验证：

- 离线 Gradle `build smokeJar` 通过，版本保持 0.1.2。
- 15°、30°、60°、90°、150° 平交各 2,600 个独立解析轨头／轮缘槽采样点通过；检查空缺、重复顶面及反向轨道一致性。直线和弯曲交叉渡线分别检查四个辙叉、护轨覆盖，以及全部偏移钢轨在两条接缝两侧的支承面。
- 槽深与封口使用轨条自身高度平面；整体移动到 Y=-40、64、180 后，全部网格顶点及面数与原模型平移一致，避免将绝对世界高度误作局部轨顶高度而挖穿轨底或遗漏封口。
- 原生 `default_3d`、`default_3d_siding` 各 1,400 个轨顶采样通过，UV 保留。侧线使用 OBJ 实际平顶宽度作为检查基准，其圆角肩部不是平顶支承面。
- 原生材质交接将 `default`、`default_3d` 与 `default_3d_siding` 视为同一内置轨型族，避免默认平面回调在道岔生成网格旁漏出旧材质；带 `_1`／`_2` 方向后缀的资源也按规范化样式命中同一套 LOD 表。
- 外观存档中遗留的 `default_3d`／`default_3d_siding` 只作为回退值；当当前道岔轨道已注册有效自定义样式时，自定义样式优先，避免旧的内置选择覆盖资源包材质。`RailPackProbe` 增加了该场景的回归。
- `build/runtime-connector-012/stdout.log` 和 `build/runtime-mtr-only-012/stdout.log` 均有 `POINT_DIAMOND: PASS`、两次 `POINT_SCISSORS: PASS` 及 `POINT_WORLD_FINAL: PASS`。原有网络、BR 方向观察、三开、编辑器和外观保存同步检查通过。
- 查看上述隔离目录截图 `point-diamond-close.png`、`point-crossing-close.png`、`point-crossing-siding.png`，以及 `build/previews/*-crossing-detail.png` 的 Blender 近景。可编辑场景为同名 `.blend`；生成脚本 `tools/render_crossings.py`。

Connector 单次运行中，原生普通／侧线平交初次网格生成分别约 29.5／57.1 ms（8,536／27,441 面，包含支承件），之后由现有缓存复用。原生道岔 1,000 次动画更新约 11.27 ms，静态重建 0 次。这些是局部 CPU 数据，不是整合包 FPS 或真实列车轮轨动力学验收。

产物：`build/libs/mtr_railway_point_advanced-0.1.2.jar`。仅修改渲染几何及开发验证，不修改 MTR／BRsignal 行车逻辑，未安装到真实客户端或改写用户存档，未 commit / push。

## 0.1.2：覆盖边界、翼轨、三开及交叉渡线

更新内容：默认覆盖倍率 0.9；Y 末端读取实际 MTR／Optional Rail 重复片段，将生成钢轨接到最后一个被隐藏片段的边缘，并将末端岔枕调整到其原枕木位置。原存档中的显式倍率保持原值，可手动改为 0.9 或恢复自动。

翼轨工作段从入轨与心轨偏移线的交点开始，保持轮缘槽间距到心轨后段，仅末尾短段外撇。三开增加第三条轨道、三个显示位置、四根尖轨和三个辙叉，共用裁切后的岔枕，不以三组完整 Y 枕木叠加。

交叉渡线通过四节点、两本线、两交叉支线的连接关系识别。四处 Y 仍保留各自外观 ID、编辑器和显示状态；中央交叉区统一生成本线／交叉轨及一组共用岔枕，两侧 Y 在分界面裁切。弯曲本线使用采样曲线及逐排切向，Optional Rail 超高选择所属轨道采样。中央区域仍有独立编辑器。组合边界由系统协调，长度倍率不会独立切断组内衔接；停用一处外观时退出组合优化。

网络协议改为 3，服务端接受三开 `t:` 外观 ID；两端应同时更新到 0.1.2。MTR 曲线、连通关系、PathData、BRsignal 授权、地图映射与信号逻辑没有写入或修改。

验证：

- 离线 `build smokeJar` 通过，正式产物 `build/libs/mtr_railway_point_advanced-0.1.2.jar`。
- 几何回归新增 0.9 默认值、末端枕木间距、翼轨工作段恒定槽宽、三开三个不同位置且网格拓扑一致、直线／弯曲四节点交叉渡线识别和边界裁切。固定心轨等宽汇合检查继续通过。
- 接缝探针比对实际 MTR 渲染回调，确认生成钢轨末端等于最后被隐藏片段的终点，末根岔枕到下一根原生枕木的距离不超过 1.25 个原生重复周期。
- 开发探针增加三开 UI 试动、独立服务端保存、BR 三方向选择；交叉渡线四处 Y 和中央编辑器共 5 处，修改一处间距不更改其余三处；直线与曲线场景动画网格均保持拓扑一致。
- Blender 检查 `build/previews/three-center.png`、`scissors.png`、`scissors-curved.png`；脚本为 `tools/render_complex.py`，同目录保留可编辑 `.blend`。

游戏运行证据在 `build/runtime-connector-012` 与 `build/runtime-mtr-only-012`，最终日志以 `POINT_WORLD_FINAL: PASS` 结束；其中截图包含 `point-three.png`、`point-three-ui.png`、`point-scissors.png` 和 `point-scissors-curved.png`。探针是注入客户端的测试轨道与显示快照，不能替代真实列车长期运营、多客户端和完整整合包验收。复杂不对称三开、极短渡线、极端超高／交角、多个组合互相重叠以及混合第三方轨型仍需实景检查。

测试中发现原探针在 tick 末检查最后一条显示快照，可能被同 tick 的正常 BR 快照覆盖，误报测试包未收到。开发探针现于接收入口记录到达；该观察 Mixin 仅打入 `point-runtime-probe`，正式 Mod 不包含它。

未改真实游戏目录或用户存档，未 commit / push。

## 固定岔心截面补充修正（仍为 0.1.1）

根据追加的近景参考，固定岔心改为两根原宽心轨沿实际曲线汇合；内缘相遇后才形成实心 V 形鼻端。不再提前以放大的渐缩截面替代两根心轨。采用中分面裁切重叠，保留原生轨头、轨腰、轨底截面及 UV；可动心轨沿用前一轮方案。

`build smokeJar --offline --no-daemon` 通过。新增 7 个纵向截面采样检查：汇合前始终为两条等宽轨头，汇合后为单个连续实心区间，并符合 V 形外缘。原有动画、V 岔枕和双侧 Y 回归通过。Connector / BRsignal 隔离世界重新运行至 `POINT_WORLD_FINAL: PASS`，并检查原生轨型近景截图。

几何近景为 `build/previews/fixed-heart-detail.png`（通用材质的 Blender 预览）；原生材质游戏截图为 `build/runtime-connector-011/screenshots/point-close.png`。JAR 已重建为 `build/libs/mtr_railway_point_advanced-0.1.1.jar`，未安装进真实游戏，未 commit 或 push。

## 2026-09-16：性能、原生模型、辙叉与 V 形岔枕更新（仍为 0.1.1）

本轮开始前按用户要求推送基线 `53f32e0` 到 origin/master。以下修改保持未提交、未推送，没有更改真实客户端 mods 或用户存档。

实现内容：

- 最近点空间树、采样复用、所属轨道及行车节点索引、静态网格缓存；动画只更新活动顶点，静止时复用法线／光照采样位置。蓝图一次提交线段批次，缓存边列表。
- 原生普通立体钢轨提取 5 个纵向面，侧线提取 22 个纵向面，保留各自逐顶点 UV、扣件和侧线支座。普通岔枕顶面改取原贴图干净的混凝土区域，消除拉伸烘焙阴影。
- 辙叉依据两条内轨中心线求交，重建连续翼轨、实体楔形心轨及两支后跟；可动心轨鼻端在两翼轨内缘之间转换，后跟固定。外侧基本轨内侧的护轨两端向轨道内侧张开。
- 新建外观默认采用 V 形岔枕，两臂按各自曲线法向生成，在公共斜接面裁切，保留整体及首尾偏角。旧存档保持旧模式，用户可切换 V 形或恢复自动。
- 辙叉细调页提供护轨纵移／加长／槽宽增量、翼轨纵移／加长／槽宽增量、心轨加长。修复轨型切换与退出蓝图，未保存预览可撤销。
- 四臂节点上两个朝向相反的 Y 分别识别和编辑，仍排除同侧三开。

验证结果：

- `gradlew.bat build smokeJar --offline --no-daemon` 通过。几何回归覆盖双侧 Y、排除三开、V 两臂分别正交、单枕编辑、7 个细调参数实际改变网格、可动鼻端与翼轨边缘距离小于 3 mm、5 个动画位置拓扑一致、上表面法向和有限顶点。
- `build/runtime-mtr-only-011/stdout.log` 与 `build/runtime-connector-011/stdout.log` 均有 `POINT_WORLD_FINAL: PASS`。真实 MTR API 生成的测试场景中，两侧 Y 分别被发现并可选择；轨型切换、撤销、退出、外观服务端保存／同步及新增护轨纵移字段通过。
- 原生两种模型面数／UV／支座区别、旧外观 JSON 缺省角度迁移通过；BR 快照传输及占用／冲突／反向选择继续通过。未向 BR 写入进路、授权、映射或信号状态。
- 检查游戏截图 `point-close.png`、`point-siding.png`、`point-angles.png`、`point-movable-left.png`、`point-movable-right.png`，位于上述运行目录的 `screenshots`。Blender 预览与可编辑示例在 `build/previews`。

性能数据（本机单次测试，不能等同于实际 FPS）：

| 场景 | 已提交基线 | 本轮实现 |
| --- | ---: | ---: |
| 5000 段轨道，10000 次最近点查询 | 358.160 ms | 6.984 ms |
| 通用 Y 网格生成 100 次 | 159.455 ms | 60.204 ms |
| 单个通用 Y 网格面数 | 9858 | 6196 |

以上由 `tools/benchmark_geometry.py` 对 HEAD 与工作目录分别编译测试，不切换工作区。Connector 场景中 1000 次原生可动模型更新总计 11.1998 ms、静态重建次数 0；20 次未变轨道刷新重新采样次数 0。侧线蓝图 CPU 提交循环中位数 3.4503 ms、P95 4.186 ms；MTR-only 分别为 3.608 ms、4.4054 ms。没有进行完整整合包原存档的大线路 FPS 测试，不能据此保证所有卡顿已消失。

正式产物仍是 `build/libs/mtr_railway_point_advanced-0.1.1.jar`，两端应使用同一份最新构建以保存新增字段。复杂第三方资源包、极小交角、极端超高与真实列车连续运营的验收限制仍适用。

## 0.1.1：修复 2026-09-16 14:25 服务端崩溃

报告为 `minecraft-exported-crash-info-2026-09-16T14-25-18/crash-2026-09-16_14.25.14-server.txt`。直接异常来自本道岔 Mod 的 `PointNetwork` Motion 编码：`String too big (was 101 characters, max 100)`，经 `flushMotion` 中断 server tick。

通过 MTR 4.0.3 `TwoPositionsBase.getHexIdRaw` 确认轨道 ID 为六段补齐至 16 位的坐标十六进制字符串及五个连字符，共 101 字符。0.1.0 的发送端和接收端都误设为 100。0.1.1 使用共用的 `MotionCodec` 将轨道 ID 限制设为 101，完整保留 ID；节点字符串限制维持原值。协议号升为 2，拒绝与使用旧解码限制的客户端混连。BRsignal 代码和外观存档格式没有修改。

验证通过：

- 离线 Gradle `build smokeJar`，包括几何回归及重混淆。
- 用 MTR API 生成包含负坐标的真实 101 字符 ID，明确复现旧的 `writeUtf(...,100)` 异常，再验证生产 `MotionCodec` 无损往返。
- 在隔离的 Connector / BRsignal / Optional Rail 世界，通过生产 Forge SimpleChannel 从服务端发送该非空包，并在客户端确认收到；不是只直接调用客户端显示方法。
- 原有方向选择、游戏渲染和外观保存同步测试继续通过。

证据：`build/runtime-connector-011/stdout.log` 中的 `POINT_CODEC: PASS`、`POINT_NETWORK: PASS`、`POINT_MOTION: PASS`、`POINT_WORLD: PASS`。没有加载或修改用户存档；尚未重跑整个用户整合包及原世界。

修复版文件：`build/libs/mtr_railway_point_advanced-0.1.1.jar`。客户端和服务端应同时替换旧版，不同时安装两个版本。

以下保留 0.1.0 初版测试记录；其空快照和直接注入客户端的方向测试没有覆盖非空网络包，这正是本次遗漏。

## 0.1.0 初版

日期：2026-09-16。目标为 Minecraft 1.20.1、Forge 47.4.18、MTR 4.0.3、Java 17。

## 已通过

- `gradlew.bat build smokeJar --offline --no-daemon`：Java 编译、Mixin 映射、重混淆、JAR 打包和几何回归。
- 几何检查：Y 道岔、无连接平交识别，立交与共用节点排除；尖轨运动不带动岔枕，单根岔枕编辑不影响其他岔枕；可动岔心有中间位置；网格有限数值、上表面方向、非法参数拒绝、输入采样不被改写。
- Blender 5.2 后台导入程序生成的 OBJ，生成并检查 `build/previews/geometry.png`；可编辑场景为 `build/previews/point-workshop.blend`。这是外观检查，不是工程尺寸认证。
- **仅 MTR + 本 Mod + 开发探针**：独立平坦世界生成 Y 和平交，原生普通／存车线截面识别，真实游戏渲染及蓝图界面，服务器接收外观编辑并同步 revision=1。日志：`build/runtime-mtr-only/stdout.log`。
- **MTR + Optional Rail 0.1.0 + 本 Mod + 探针**：独立世界检测与渲染通过。日志：`build/runtime-base/stdout.log`。
- **MTR + BRsignal 0.1.2 + Optional Rail 0.1.0 + 本 Mod + Connector beta.46 + Forgified Fabric API + 探针**：世界启动、两类交汇显示、蓝图和服务端外观保存同步通过。BR 真实空快照跨服务端／客户端传输通过；用测试显示数据验证占用优先、方向冲突保持、反向通过选岔。日志：`build/runtime-connector/stdout.log` 中的 `POINT_MOTION: PASS` 和 `POINT_WORLD: PASS`。

运行探针只在 `build/runtime-*` 新建测试世界。测试轨道是注入到测试客户端的采样场景，**不等同于真实列车运营场景**。方向测试只注入本道岔 Mod 的显示数据，不向 BRsignal 写入授权或进路。

## 环境问题

BRsignal 0.1.2 在不带 Connector 的纯 Forge 测试世界启动时，其现有 `MainWebserverMixin` 构造器注入被 Mixin 0.8.5 拒绝。加入当前客户端已有的 Connector / Forgified Fabric API 后测试世界可以正常启动。此项目没有改动 BRsignal 源码或发布 JAR，也没有通过取消它的 Mixin 来绕过问题。

日志还含 MTR 原有货物方块掉落表和声音缺失、BRsignal 大写纹理路径等错误；离线探针含 Realms 身份验证提示。它们未阻止上述通过项。不能由此证明整个整合包无错误。

## 尚未验收与已知限制

- 真实列车连续通过、多列车交会、折返、授权撤销全过程；独立专用服务器与两个真实客户端；持久化后重启再载入。
- 未逐个加载所有第三方轨道包；OBJ 截面推断和简单立方体 Blockbench 推断不是任意模型语义解析器。复杂网格、旋转部件、混合附件可能要手调或写描述文件。
- 一处交汇使用一套截面，混合轨距／材质的分支过渡未实现；二维原生轨道到生成三维轨道的边界未做渐变。
- 长重复模型的局部取消边界可能有缝隙／重叠，复杂道床纹理会简化；资源包附属面保留采用空间判断，并非语义识别。
- 超高以现有 Optional Rail 采样框架处理，极端扭曲、重叠交汇、很小交角不在本次场景覆盖内。未做大规模线路 FPS 基准。

因此交付定位为可构建、已进入游戏验证的实验版，不能宣称所有第三方模型与所有行车场景均已完成验收。

## 修改边界

本项目只读 MTR 曲线、样式、客户端路径以及 BRsignal 公布的快照。Mixin 只观察 Simulator tick、读取字段、替换轨道外观回调并提交生成网格。没有写入 MTR 图连接、PathData、寻路结果、BR 授权、轨道可视化映射或信号状态。外观参数保存在本 Mod 独立的 `mtrpoint_appearance` SavedData 中。

正式交付文件：`build/libs/mtr_railway_point_advanced-0.1.2.jar`。不要安装 `point-runtime-probe-0.1.2.jar` 到真实游戏。真实客户端 mods 目录和用户存档未修改。
## 0.1.3 光影兼容修复（2026-09-30）

- Point Advanced 的世界轨道 GPU 网格改用 Minecraft 标准 `RenderType.entityCutoutNoCull`，并从 `AFTER_TRANSLUCENT_BLOCKS` 调整到 `AFTER_ENTITIES` 提交，避免 Iris/Oculus 光影只接收阴影而丢失实体颜色输出。
- Optional Rail 采样适配器在反射接口不匹配或运行时异常时回退到 MTR 原生 `RailMath`，不再让倾斜钢轨和道砟整段消失。
- Point Advanced 版本保持 0.1.3；Optional Rail 版本升至 0.1.1；网络协议保持 3。
- Point Advanced 完整构建及几何回归通过；Optional Rail 构建及 707 项回归通过。未修改 `citizons_railway` 材质包。
## 可调轨道精度分界（工作区变更，2026-10-01）

- 游戏配置新增高→中、中→低两个距离分界点，默认仍为 4 m 与 12 m，范围为 0–4096 m。
- 两个值可以相等；相等时直接从高精度切换到低精度。两个值都为 0 时所有轨道、道砟、枕木和扣件均使用低精度档。
- 配置只保存在客户端并即时影响普通轨道、倾斜轨道和道岔 GPU 批次，不修改材质包描述、外观存档或网络协议。
- 新增 `RAIL_DETAIL` 回归覆盖默认、自定义、边界相等、双零以及零高精度档；Point Advanced 完整构建和原有几何回归继续通过。
