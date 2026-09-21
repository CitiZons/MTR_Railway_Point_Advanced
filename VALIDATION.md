# 验证记录

## 2026-09-21（续）：尖轨刨切改动撤回与四个未解决项（按现状记录）

按用户要求，本轮**只撤回**尖轨（switch blade）的刨切／变尖渲染改动，并把目前仍然存在的实景问题原样记录在案：**不再做其他修改，按现状记录**（用户原话："不再做修改，按现状记录"）。

撤回内容（仅此一项改动，其余修复全部保持原样）：

- `geometry/Mesh.java`：`blade(V3,V3,V3,V3,Profile,PointSettings)` 恢复为改动前的行为——先生成完整原生截面，在该截面仍被刨切时用 `clipAnimated` 从基本轨一侧按竖直刨切面裁切；本次为横向缩放新增的 `scaleAnimated(...)` 及其 `edge(...)` 辅助方法一并删除。同文件 `railCap`／`railCutCap` 的端面轮廓与材质修正（`CapOutline`／`endOutline`）不受影响。
- `geometry/TurnoutFrame.java`：`Blade` 记录恢复为 `(point,cut)`；`blade(...)` 恢复为按"自然横向间距 `separation < headWidth`"给出竖直刨切点、不再随开通位置判定的原逻辑；为此新增的 `separationDirection(...)`／`separated(...)` 删除。`start(...)` 与 `contact(...)` 未改动。
- `geometry/PointMesh.java`、`geometry/ThreeWayMesh.java`：`mesh.blade(...)`／`m.blade(...)` 调用恢复为传入 `bladeA.cut()`／`bladeB.cut()`。`PointMesh.secondStart`、`NATIVE_CELL_REACH` 等其它改动未动。
- 回归：原先固定"横向缩放"行为的断言 `PASS: a switch blade is planed by a lateral section scale about its running face, never cut, and a blade clear of its stock rail keeps its full section` 已替换为恢复后行为的等价断言 `PASS: a switch blade is planed by clipping its full native section from the stock-rail side, never tapered, and a blade clear of its stock rail keeps its full section`（完整原生截面 + 从基本轨一侧裁切，未刨切的尖轨保持完整截面；仍由独立组装的 `clipAnimated` 结果逐面比对）。

撤回原因：该改动让尖轨渲染**更差**（用户实景反馈），因此恢复为改动前的实现。

以下为**按现状记录的 OPEN、未解决问题**（均未修复）：

1. **钢轨与护轨的切断端面仍然没有材质。** 端面已改为取原生模型 zMin 轮廓与自身 surface（`Mesh.railCap`／`railCutCap` 与原生模型轮廓／材质），但游戏内看仍然没有材质；待查。
2. **合并护轨时**：中间那根护轨的终点有时会按预期 cancel out、有时有一个做不到；而合并完成后，**外侧两端**的终点反而被 cancel out（与预期相反）。
3. **护轨偶尔仍有未按平交口切断的情况。** 此前已定位并修复"风格组不匹配则永远不被切"这一机制（见下文 2026-09-21 护轨切口一节），用户仍能观察到个别漏切；缺少具体场景，无法继续。
4. **尖轨（switch blade）的刨切／变尖渲染改动已撤回**：该改动让尖轨渲染更差，恢复为改动前的实现（见上）。

验证：完成撤回后运行门禁 `.\gradlew.bat regression build smokeJar --no-daemon --console=plain`，**BUILD SUCCESSFUL（exit code 0，58 s）**，共 **51 条 `PASS` 断言**全部通过（含既有几何回归、Forge 编译与重混淆打包）。撤回后 `git diff` 中与尖轨刨切／变尖相关的 hunk 已全部消失，其余修复保持不变：原生截断面（`Mesh.railCap`／`railCutCap` + 原生模型轮廓／surface）、护轨切口门限（`DiamondGeometry` 的 `overlapsVertically`／`Cut`／`atLevel`／风格组回退）、护轨高亮索引（`PointSelectionScreen`）、分摊式装配发布（`PointRenderer.guards(List,int)` + `PENDING` 队列、`SurfaceUnion` 优化）、`suppress` 所有权门控（`PointClient`）、`bank`（位置，自身股道）缓存（`RailSampler`），以及 `PASS: both outer turnout rails follow their own branch curve over 527 samples`。未 commit、未 push。

## 2026-09-21（续）：选中护轨不再高亮（回归）

编辑器 `preparePlan` 里 `GuardRails.merge(pool,owners)` 回报的来源是**池内索引**，而 `selectedGuards` 存的是**拾取全局索引** `base+i`（两者本来有 `poolOwners` 做映射，却没被使用），于是 `guardRunSelected` 永远为假：选中的护轨不变黄，`mergeGuards()` 里 `joined` 也恒为 0，状态提示跟着错。现在把 `owners` 经 `poolOwners` 翻译成全局索引再存进 `mergedOwners`。门禁 `regression build smokeJar` 通过。

## 2026-09-21（续）：外侧轨连续性诊断与 bank 参考系修正（0.1.2）

用户报告"无论尖轨在什么位置，最外侧的两条轨道总像按同一条路径曲线渲染再被切断"。做了两件事：

**测量（只读，jshell 直接跑 `build/classes/java/main`，每 0.05 m 采样最终装配网格）**：在直线+曲线的不对称 Y、两组共用一条股道的双道岔、20°/17° 近接交分等夹具上，两条外侧轨各自都被单个 band 覆盖、采样点 0 缺失，标签（`Mesh.RailTag.road`）与实际采样股道一致；`FrogGeometry.mergeWings`（`FrogGeometry.java:147-165`）只在同一 `road().id` 上缝合，`PointMesh` 四条走行轨各自采样自身股道（`PointMesh.java:21,33-54,70`）。也就是说**在没有驼峰参考系（`RailSampler.SAMPLES` 为空、bank 原样返回）时复现不出来**，问题不在这些生成路径上。

**已修**：`RailSampler.bank` 选参考系时只按最近几何、不校验股道身份（`RailSampler.java:77-82`），而 `Mesh.Quad.rail()` 本来就知道顶点属于哪条股道。这是唯一能把 A 股道顶点的坐标搬进 B 股道驼峰参考系（`bank.frame`）的环节，与尖轨位置无关，效果正是"轨被拉到另一条曲线上并从自己的面片里脱出（看着像被切断）"。现在带轨迹标签的顶点只用它**自己那条股道**的参考系（`byRoad.get(q.rail().road().id)`），无标签的面片（枕木、辙叉、尖轨、护轨）仍按最近几何；缓存只保留给无标签顶点，避免同一坐标在不同股道下取到错误缓存值。

验证：`.\gradlew.bat regression build smokeJar --no-daemon --console=plain` 通过（1m 1s）。此修正只有在启用可选轨道驼峰（MTR 可选轨道/`mtr_optional_rail` 路径）时才会改变输出，回归无法覆盖该路径，需在游戏内确认；未 commit、未 push。

**固化为回归断言**：`PointRendererAssemblyRegression` 新增外侧轨归因检查——对不对称 Y（直线 + 曲线，共用节点）走最终装配，沿**每条外侧轨自身股道**每 0.05 m 采样（三条横向偏移，避开面片接缝），要求该点被钢轨面片覆盖；只在两股道中心线相距 > 2·centerOffset+0.3 m 的区间采样，避免另一股道的轨顶足迹干扰。当前通过：527 个采样点全部覆盖（`PASS: both outer turnout rails follow their own branch curve over 527 samples`）。负面对照：把采样点改用对面股道的横向方向，断言立即失败（`An outer rail is not drawn on its own branch curve at V3[x=60.0, y=0.0, z=20.25]`）；恢复后门禁重新全绿。这条断言与可选轨道路径无关，能固定住"外侧轨必须按自身曲线生成"这一几何归因。

## 2026-09-21（续）：卡顿与服务器负担优化（0.1.2）

先做只读审计（客户端渲染/编辑器、服务端/网络，逐条 file:line），再按"影响/风险"实施低风险项。

客户端：
- `PointClient.tick` 原先**每 tick**调用 `nativeMovements()`，即遍历每辆车、每辆车再遍历**整条 path**（`PointClient.java:130-134`），并且 `index()` 每 tick 重建映射；现在每 4 tick 一次（栏木跟随本身慢于 0.2 s）。
- `Detector.find` 每秒在客户端全量跑一次，原先每个 8 m 单元用 `x+":"+z` 字符串作 key，每对线段再拼一次 pair 字符串（每根轨道上千段 ⇒ 每秒大量短命字符串）；现在单元 key 用 long（`(long)x<<32 ^ z`，无分配且单射），并去掉那条 pair 去重集合——同一对线段即使跨两个单元被访问两次，交点也按四舍五入坐标在结果里被去重，语义不变。
- `RailSampler.sample` 即使命中缓存也先 `writePositions` + 两次反射取节点；未装 Optional Rail 时缓存判定只依赖 `Rail` 实例（两个节点句柄恒为 null），现在先命中缓存直接返回；装了插件仍走原路径。全量重建每秒对每根邻近轨道调用一次。
- 编辑器 `PointSelectionScreen.changeDraft` 原先每个按键、每个拖拽事件都完整跑 `preparePlan()`（重建所有视图网格 + `GuardRails.merge` 的 O(n²) 重采样 + 重新哈希整份蓝图线框）；现在只标脏，由下一帧合并重建一次；`previewNow()`（按钮/点击/添加枕木等离散动作）仍在同一事件内同步重建，接口契约不变。
- 顺带修掉 `mergeGuards()` 里 `if(status!=null)return;` 恒真（`status` 初值是空串）导致的"护轨已合并为一根／已按各自股道延长"永远不显示。

服务端与网络：
- `BrObserver` 每 150 ms 读一次 BR 快照，原先每访问一个字段都 `getClass().getMethod(...)` 重新解析（每次扫描每条 traversal 至少一次）；现在按（类、方法名、参数个数）缓存 `Method`，`RouteRequestManager` 也解析一次。
- `PointNetwork.flushMotion` 原先每 4 tick 把整份快照（最多 8192 条，每条 node + 两个约 101 字符轨道 id ≈ 322 B ⇒ 约 2.6 MB）广播给**整个维度**，无论维度里有没有人、有没有车在动；现在按玩家 256 格距离过滤，每名玩家只收到与自己相关的 movement，没有也发一张空表（客户端因此不会把"沉默"当成断流而切到等待态）。
- 进服／切换维度原先每个外观条目发一个 `State` 包（上限 4096 个，各自 Gson 序列化，客户端每包扫描全部视图 ⇒ O(N·V)）；现在合成一个 `Batch` 包，客户端先写入全部设置再扫描一次视图；频道版本 3 → 4。

验证：`.\gradlew.bat regression build smokeJar --no-daemon --console=plain` 全部通过。性能改动的正确性由既有回归约束（`Detector` 的 Y/三开/平交识别、`RailSampler` 采样与边界、护轨选择与合并、`PointRendererAssemblyRegression` 的覆盖断言）。**未做帧时实测**；审计中标为待实测的项（`RailRenderMixin` 每格 `suppress`、`PointGpu` 光照批次整批重传、`GuardRails.merge` 的 O(n²) 重采样、编辑器按视图增量重建）本次未实施。未 commit、未 push。

## 2026-09-21（续）：护轨全量可选与合并状态提示（0.1.2）

护轨可选性：编辑器原先只枚举 `GuardRails.forJunction`，平交（`DIAMOND`）返回空、三开（`THREE`）的护轨又刻意不进合并池，因此只有 Y 型两股内侧的护轨（蓝色）能选中，交点与三开的护轨虽被 `DiamondGeometry`／`ThreeWayMesh` 画出来却选不到；多来源护轨的两半无法同时选中，合并也就"看起来不生效"。现在统一走 `GuardRails.selectable(j,s,raw,group,boundary)`：平交用 `GuardRails.crossing`（编辑感知），三开用 `ThreeWayMesh.checkRuns`（交点护轨 + 每个道岔对的 `frog.guard`，与 `DiamondGeometry.three` 烘焙同源），Y 型仍用 `forJunction`；`GuardRails.edited(runs,s)` 成为唯一入口（先 `removeBladeSteel` 再 `applyEdits`），尖轨旁的基本轨（`isBladeAdjacent`）继续排除。三开的护轨改为在 `DiamondGeometry.three` 内经 `GuardRails.merge` 烘焙，与世界池化路径一致。

合并语义：`mergeGuards()` 不再用 `mergeable` 拒绝，而是为每个选中护轨写入 `GuardEdit(start,end,flareStart,flareEnd,group)`（各股取全部选中护轨端点在自身道路上的并集，`end<=start+.05` 才算拒绝），并报告 `mtrpoint.guard_merged`（并成一根）或 `mtrpoint.guard_merge_partial`（各股分别延长）；`mtrpoint.guard_merge_rejected` 保留给退化区间。几何合并 `GuardRails.merge` 本身未放宽：仍要求同侧、同断面／同竖向偏移、横向位置在 1e-5 或双方同组时的 `mergeTolerance` 内，接缝 ≤1e-7（自动）或 `MANUAL_GAP=0.25`（同组），并只保留并集最外两端的外撇（`merge()` 现在从拥有该端点的 run 取 flare，修掉了原先恒取一侧的无效三元式）。

实景反馈的三处收尾（本次）：平交点选绑定、合并带跳过钢轨切口、内部接缝留下端面。

点选绑定：编辑器原先把"合并后的护轨"与"选中的护轨"用**几何邻近**对应（在合并轨道上取样选中护轨的中心线，距离在 `mergeTolerance` 内就算选中），而平交处两条方向不同的护轨在交点附近本来就落在这个容差里（20° 交叉时同侧两条护轨的中心线相距约 0.12 m），于是一次点击点亮相交的多条护轨。现在 `GuardRails.merge(input,sources)` 额外回报每条并合轨由哪些输入 run 得到，编辑器按 `mergedOwners` 做**来源匹配**（`guardRunSelected(int)`），只有真正被合并进去的选中项才算选中；`merge` 的合并规则本身未动。

护轨切口：`DiamondGeometry.bakeLayer` 里 guard 断面在两侧道路相差 >5 mm 时会 `continue` 掉**整条**裁剪，而 `Detector` 允许平交的两条道路相差 80 mm，于是"护轨钢穿过本该切开的走行轨"在实景里普遍存在。现在 5 mm 判定只留在"等距离接缝归一个所有者"那一支（两根在平面内重合但不同高度的钢轨本来就不是同一根），相交/楔形减料支改用 `overlapsVertically`：只要两根钢轨的**截面在竖直方向可能重叠**（基准线高差小于一个轨高）就必须被切断，只有高出一整个截面的钢轨才让下方的护轨完整保留。

内部端面：`DiamondGeometry.path(GuardRails.Run,…)` 与 `convert()` 原先无条件给每个 run 的两端设 `first/last`、`capStart/capEnd`，因此 `exposeEnds` 已经判定为"内部"的端面仍被烘焙出来；现在两者都跟随 `flareStart/flareEnd`（只有露出的端头才有端面），legacy 路径 `appendChecks` 也补上了 `exposeEnds`，与世界池化路径一致。另修 `coveredEnd` 的注释与 `path` 的取样口径说明（切向比较取绝对值，节点顺序相反的两条道路仍算同一根钢轨）。

验证：`.\gradlew.bat regression build smokeJar --no-daemon --console=plain` 全部通过。新增断言 `PASS: a crossing cuts its check rails at every height the two sections still overlap, and never cuts one that runs clear underneath`：平交夹具在 Δy=0 与 Δy=75 mm 下护轨的水平投影面积都必须小于 Δy=400 mm（截面不可能重叠，护轨完整）的面积；负面对照把旧的 5 mm 门限放回循环顶部后该断言立即失败（`6.118223749619834 vs 6.118223749619834 m2`，即 75 mm 那组完全没被切），恢复后重新全绿。`checkGuardMerge` 另加两条：`merge(...,sources)` 的来源集合必须是 `{0,1}`（并成一根）或 `{0},{1}`（各自独立），以及"同一根钢轨从节点顺序相反的两条道路取样时不留下内部开口"。未运行游戏内探针，未 commit、未 push。

验证（护轨可选性）：新增断言 `PASS: every crossing and fan check rail is selectable, and a manual station reaches its baked steel`（平交 20°／90° 与三开都要求 `selectable` 非空、不含尖轨旁基本轨，且索引 0 的 `GuardEdit` 必须改变 `DiamondGeometry.combine`／`PointMesh.build` 的烘焙结果）。本节取代下一节中"否则拒绝"的护轨合并语义；`guard_merge_rejected` 仍保留在三语语言文件中。

## 2026-09-21：护轨合并拒绝与切区契约（0.1.2；护轨"拒绝"语义已被上一节取代）

护轨合并（`GuardRails.merge`）原先只按同一条路、同高度和近似断面合并，两个选中的护轨即使相距数米（Y 型道岔两侧外护轨相距 2.334 米）也会被并成一根，并在结果里删掉被覆盖的一段。现在合并前要求两条护轨确实是同一根钢轨：同一条路时横向位置必须一致（1e-5），跨路时必须把较短的一条取样到另一条路上，切向点积 ≥0.999 且距离不超过 `mergeTolerance`；手动合并的区间只允许留 `MANUAL_GAP=0.25` 的空隙（原先用包含关系判断，长轨跟随短轨时会漏判）。不满足时报 `mtrpoint.guard_merge_rejected`（三语）并拒绝，不再改动任何护轨。编辑器预览原先完全看不到护轨，现在合并后的护轨以青色画进预览，并沿用世界装配的剪刀区裁切；护轨高亮也用合并后的区间判断。

交点切面（`RailCuts`／`DiamondGeometry`）经只读复核查证后**未改**。把三层真实截面交点（`RailSection.conflicts`）与实发切区逐段对比：Y 型夹具的轨底交点区间 `[9.4407,9.8998]` 包含轨腰 `[9.6362,9.7093]` 与轨头 `[9.5587,9.7940]`，即"单一区间"取自最宽层，并不额外多切；实际下发的是 `[8.22,11.00]`，比真实交点宽是设计使然（辙叉钢占据 toe..heel）。端面 `Mesh.railCutCap` 的 12 点轮廓恰好是内置工字钢三层矩形之和，既有 `checkCutCap` 已用 `footWidth*.025+.022*(railHeight-.025-.036)+headWidth*.036` 的面积契约与顶点横向档位约束它。另外实测菱形视图内带 `RailTag` 的走行轨面为 **0**，`RailCuts.forJunction(DIAMOND)` 的 4 条切区在本视图里空转，平交钢轨完全由 `DiamondGeometry` 生成；因此"把 `RailCuts` 改成按层挖孔"不会改变菱形外观，只会让区间变窄（与实景"钢轨未切断"方向相反），并会破坏 8 条硬契约（`AssemblyRegression` 的端面共面、合并孔内部为空、不得留内部端帽、端面总面积、`cut=toe/heel`，`DiamondRegression` 的 `frog_wall` 三层 band）。该改法已被否决。

真正的漏切来自切区匹配：`RailCuts` 原先要求候选钢轨的 `profile` 完全相等、横向 offset 相差 <1e-5 且 `verticalOffset` 完全相等，才认领一段钢轨。同一根共用钢轨两侧的两个道岔视图各自保存外观，任一视图改了轨距／轨头宽度／轨顶高度后，两条钢轨中心线相差几厘米、断面不同，或只有高度不同，于是另一视图的辙叉断口永远落不到这段钢轨上，断口被连续钢轨跨过。现在 `RailCuts.sameRail` 只按"钢轨位于道路的哪一侧"认领（两根走行轨相距一个轨距，靠得更近的一侧才是同一根），端面始终用该段自己的断面与 offset 生成，`assemble` 不再要求断面或高度相等（裁剪平面是竖直的，高度只是同一根钢轨的外观偏移；无 tag 分支本来就忽略高度）。回归新增两组正反对照（轨头宽度覆盖、竖向偏移各一组）：带外观覆盖的邻段钢轨必须被切掉，对侧走行轨必须完好；把 `sameRail` 换回精确比较或把高度判据加回去时，该断言分别立即失败（`A neighbouring appearance override left steel across the frog`）。

原先无人引用的 `RailSection` 现在承担切区契约：`Regression.checkCrossingCutCoverage` 用 `RailSection.conflicts` 逐层求出交叉轨对的真实交点区间，要求每一段都落在实发切区内（Y 型夹具 + 20°／90° 平交，共 54 段真实交点区间）。同节点两分支同侧钢轨在节点附近的擦碰属于同一根外轨，不参与该契约。

另核查交叉渡线分组视图：`PointRenderer` 原先只对 `group==null` 的视图收集切区，看起来会让成员道岔的走行轨在共享区内漏切。实测把分组视图的切区也收集进来后，最终装配在断口内的钢层数与不收集时**完全一致**（单开／交叉渡线为 1 层，交叉渡线共享区内为 2–3 层，来源是共享区中心平交钢的合法重叠），而 `ScissorsLayout.clip` 已把分组视图位于共享区内的全部面裁掉，成员钢轨在该区域本就不存在，所以该条件是刻意设计，未改动；已在 `PointRenderer` 留下注释说明。另外，用"切区里程标签与残留面重叠"来判定漏切会**误报**：交叉渡线裁剪面的标签区间比其实际几何更宽（`PointRenderer` 内已有注释说明），必须改用与坐标系无关的几何采样。

仍未改动、需游戏内复核后再动的部分：`DiamondGeometry` 的 span 以最宽层宽度 `sectionWidth=max(footWidth,headWidth)` 生成，接缝刀与切面墙平面由此推出；要让浅交角下的层间封板也逐层收窄，需改成每条 span 携带各层宽度并用 `RailSection.conflicts` 提供真实交点站点，同时重新标定上述 8 条硬契约。此项保持 2026-09-17 记录的未解决状态。

验证：`.\gradlew.bat regression build smokeJar --no-daemon --console=plain` 全部通过（含几何回归、Forge 编译与重混淆打包），新增 `PASS: crossing cuts cover every per-layer section conflict (54 real conflict intervals; diamond views keep 0 tagged running-rail faces...)`、`PASS: a crossing cut reaches a neighbour rail moved by appearance overrides and spares the opposite rail` 与 `PASS: guard merge joins one physical rail only, keeps only the outer mouths and never bridges rails metres apart`（原为"…and refuses a Y pair metres apart"；编辑器的"拒绝"已改为提示，见上一节）。关键修复均做了反向对照：把匹配换回旧判据、去掉任一条断言时对应用例立即失败。未运行游戏内探针，未 commit、未 push。

## 2026-09-20：统一复杂岔区编辑器（里程碑 1，0.1.2）

`PointSelectionScreen` 从“导航用选择图”改为统一复杂岔区编辑器：整组当前外观始终可见，点击道岔只在该视图中原地选中，不再跳转到二级 `BlueprintScreen`。选中后右侧出现与原蓝图一致的逐道岔外观控制（四个分页、轨型切换、可动岔心／启用、枕木模式与参考路径、恢复自动、撤销、保存），改动作用到该道岔并刷新整组预览；原有全局枕木选择、拖动、保存与撤销保持可用。多道岔布局底部新增编号开关，编号可见时为“隐藏编号”，隐藏后为“显示编号”，三语（zh_cn／ja_jp／en_us）同步，默认可见。隐藏编号会同时隐藏编号、黑色底框、选中外框和底部编号导航块，使轨道、护轨与枕木保持无遮挡；预览线段在 CPU 侧严格裁切到视口，避免 UI 缩放时越界。护轨可直接选择并调整首尾长度，Ctrl 多选后可合并绘制；枕木命中只使用枕木实体，不再把钢轨下方扣件计入碰撞区。`TurnoutPanel` 抽出原 `BlueprintScreen` 的控件构建、数值编辑、枕木规则、菜单与保存报文校验，两个入口共用同一套设置规则；`BlueprintScreen` 类、数据格式与 `mtrpoint.select_back` 返回路径保留。几何仍在外观变化时一次性重建，未新增逐帧计算。

执行 `.\gradlew.bat regression build smokeJar --no-daemon` 通过：既有全部几何回归（含交叉渡线、双 Y／三开节点、护轨并集、工字钢分层切面与装配分区缓存）与 Forge 编译均成功。随后执行 `.\tools\runtime_probe.ps1 -World -BaseOnly`，完整装配预览、原地选择、编号切换、枕木编辑、服务端保存及三语资源全部通过，最终输出 `POINT_WORLD_FINAL: PASS`。

## 2026-09-18：尖轨、护轨、复杂岔区与选择图修复（0.1.2）

相邻道岔原先各自提交一份固定走行轨。即使某一道岔正确省略了辙叉区间，另一模型的完整钢轨仍可能覆盖同一位置并把断口补回。现在每段固定走行轨在生成时记录所属曲线、里程区间和横向位置；世界固定部件缓存汇总同一接触区域内所有辙叉区间，先合并重叠切区，再按钢轨中心线一次裁切和封口。不同轨道 ID 描述同一物理钢轨时也能命中，不再依赖每帧逐面读取世界位置。外侧分支从节点中心沿自身曲线连续生成，不再以一条短直线硬接到尖轨起点；尖轨、连杆和可动岔心仍由各自模型更新，动画拓扑不变。交叉渡线继续使用既有共享中央区，三开继续使用六轨共同截面，不重复套用单开整段断口。

护轨端部是否属于内部接头，改为比较两条尚未外撇的工作中心线距离与切向。只要近似同向的护轨实体在短区间内接触，就取消相接端的外撇并交给截面并集；只有合并后最外侧两端保留外撇。不同高度、不同截面和仅仅相交而非重叠的护轨仍保持独立。

尖轨横向位置改为以基本轨工作边为基准。网格先生成完整原生轨型，再仅对仍与基本轨接触的头部短段使用基本轨一侧的竖直刨切面，后续轨身保留完整轨头、轨腰和轨底，不再对整个截面做对称缩放；Y 型和三开道岔共用这套计算。刨切段及交叉渡线外层边界使用固定三角面槽，保证弯曲布局在左右开通状态下仍具有一致动画拓扑。相邻菱形交叉也改为一次汇总全部钢轨和轮缘槽后生成固定组件，使每条轮缘槽都能裁切其他交叉带来的重叠轨面。自定义轨型护轨与翼轨的暴露端由轨型截面生成封口，并显式使用钢轨表面 UV，避免端面透明而看起来空心。

岔区选择图从只显示线路中心线改为汇总附近每个道岔模型的全部枕木。枕木所有表面和投影边共同组成命中区域，因此两端都可选中；拖动后可撤销或直接发送保存。单道岔枕木页增加参考路径、手动添加、删除、切断／连接、完整形状／自动裁边和仅重置枕木；复杂交叉渡线中央视图可在全部四条路径间选择。服务端确认后刷新草稿，关闭界面时未保存改动会恢复。

新增回归覆盖：仅重叠 0.2 米且随后分离的护轨必须保持完整轨顶；两个道岔共用固定轨时不得互相填回辙叉断口；两个重叠切区只能生成一个连续缺口且不得留下内部端盖；外侧分支必须从节点中心开始；相邻菱形交叉的 46 个原先被填回的轮缘槽采样点必须保持为空；单开和三开外侧基本轨在尖轨起点连续；尖轨必须只在短接触段单侧刨切并在后段恢复完整截面；自定义开放轨型的护轨与翼轨必须具有带 UV 的端盖；参考路径、手动新增、删除、V 枕木切断和仅重置枕木均有独立断言。

执行 `.\gradlew.bat regression build smokeJar --no-daemon` 后全部几何回归、Forge 编译和重混淆打包通过。随后用当前构建执行 `.\tools\runtime_probe.ps1 -World -BaseOnly`，隔离客户端完成 `POINT_WORLD_FINAL: PASS`：直线和弯曲交叉渡线动画拓扑稳定，复杂交叉区五个编辑对象和 863 / 991 个世界组合轮缘槽采样通过，枕木远端选择、拖动、撤销、单道岔新增／删除／切断／完整形状／重置及服务端保存均通过。固定装配在 100 次不变绘制中重建 0 次，1000 次静止 GPU 更新上传 0 次；32 个完整道岔的隔离 CPU 提交中位数 0.8232 ms、P95 1.0542 ms。视觉复核了 `point-close.png`、`point-three.png`、`point-scissors.png`、`point-crossing-close.png`、`point-crossing-siding.png`、`point-selection.png`、`point-ui-ja.png`、`point-v-composite-top.png` 和 `point-asymmetric-three-close.png`。产物为 `build/libs/mtr_railway_point_advanced-0.1.2.jar`。本次未 commit、未 push，也未改动行车、寻路或信号逻辑。

## 2026-09-17：实景反馈与当时的暂停状态（0.1.2）

用户确认以下情况仍存在钢轨未切断的问题：Y 型单开道岔、部分复杂道岔、平交轨道。应形成辙叉断口或轮缘通道的位置仍可能保留连续钢轨。因此，“钢轨裁切及轮缘通道已在全部道岔／平交布局中修复”不成立，此项仍为未解决问题。

此前日志中的 PASS 仅适用于列出的程序生成夹具、采样点和隔离游戏场景；保留这些历史记录，但不得用其否定最新实景反馈。具体触发条件和根因尚未确定，本次不新增根因推断，也不将问题归因于用户轨型或铺轨方式。

按用户要求暂停进一步代码修改。本次仅更新文档，将既有实现、资源、测试和工具作为当前开发检查点提交并推送，版本仍为 0.1.2；提交信息使用英文。此次文档更新没有重新运行构建或游戏测试，下方历史验证结论均需结合上述未解决问题理解。

该段记录的是 2026-09-17 检查点；后续处理见上方 2026-09-18 记录。原存档实景确认仍用于补充程序回归不能覆盖的第三方轨型与节点组合。

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

正式交付文件：`build/libs/mtr_railway_point_advanced-0.1.0.jar`。不要安装 `point-runtime-probe-0.1.0.jar` 到真实游戏。真实客户端 mods 目录和用户存档未修改。

## 2026-09-18 编辑器退出卡顿、快速行驶卡顿与合并护轨漏切

三处修复，几何结果均与修复前逐面相同。

1. 退出编辑器不再一次性重烘整个区域：`PointRenderer` 每帧只组装一个组件（`prepare(...,1)`，`PointClient.tick` 在 `pending()` 时继续排空），未受影响的组件保留缓存网格，正在重烘的组件继续用 `PUBLISHED` 里的旧网格绘制，不会闪空。`SurfaceUnion` 消除了逐候选面分配 `List.of`/`Stream` 并复用网格单元键，主导阶段 `SurfaceUnion.build` 由 1040 ms 降到 830 ms（直线剪式夹具，55k 输入面），输出不变。
   - 断言：`PASS: the amortised publish of a crossing component equals the from-scratch assembly quad for quad`、`PASS: two guard edits rebuild one component per frame, keep the untouched component and drain to the from-scratch assembly`。
   - 反证：把预算改为整轮排空 → `One amortised prepare call rebuilt 2 of 2 edited components instead of 1`；发布时优先取旧网格 → `Amortised publish after a guard edit is not the from-scratch assembly of the same views`。
2. 每帧每格的 `PointClient.suppress`：先按轨道是否被任何视图拥有做门控，再做样式 ID 归一化，并按样式缓存归一化结果；`RailSampler.bank` 对带标签顶点恢复 (坐标, 自身股道) 缓存，每顶点仍用自己股道的超高框架。
   - 断言：`PASS: the suppression gate answers exactly like the pre-gate scan for every rail, style and position, and an unowned rail never normalises a style`、`PASS: a tagged rail vertex is banked once per (position, road) pair and every vertex still lands in its own road's frame`。
   - 反证：去掉所有权门控 → `A rail no view owns still normalised the style id before the ownership gate`；关闭缓存 → `Tagged banking ran 12757 times for 2136 own-road and 6349 shared vertices`。
3. 合并护轨漏切：新增 20° 平交＋共用股道 Y 道岔夹具（跨节点双源护轨，`multiSource>0`），用「平交钢轨轨头带覆盖护轨轨头范围」的窗口采样比较合并（pooled）烘焙与合并前（legacy）烘焙保留的钢料。
   - 断言：`PASS: a merged cross-junction check run keeps exactly the steel the legacy bake keeps where the crossing rail covers it`。
   - 反证：禁止护轨被 running rail 切断 → `The pooled bake of the merged check run kept more steel than the legacy bake where the crossing rail covers it`。
   - 实测：本夹具下合并与未合并保留量完全一致（Δy=0 时 460/1094，Δy=50 mm 时 193/1094），因此未能复现用户原存档的漏切；已定位但仍未修复的疑点：样式（截面／高度）与任何平交分组都不匹配的护轨会落到 `DiamondGeometry.guards(remaining)`，该分支的 span 列表里没有任何平交钢轨，注定不会被切断；`combine()` 里跳过 `GuardRails.exposeEnds` 在本套回归里没有任何可见变化。

