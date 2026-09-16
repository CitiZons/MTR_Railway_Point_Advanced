# 验证记录

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

正式交付文件：`build/libs/mtr_railway_point_advanced-0.1.0.jar`。不要安装 `point-runtime-probe-0.1.0.jar` 到真实游戏。真实客户端 mods 目录和用户存档未修改。
