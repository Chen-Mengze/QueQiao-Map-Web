# AGENT.md — QueQiao Map Web 开发引导

> 动手改代码前先读完本文件；设计权威是 ARCHITECTURE.md。

## 1. 这是什么

把 Minecraft 服务端的「顶层方块地图 + 玩家位置」以事件形式经 **鹊桥（QueQiao）事件桥**
推给 Web 地图。多加载器（Fabric / Forge / NeoForge）多版本，一套主结构。
当前阶段：阶段二 = 接口契约与三方接入固化（TDD+SDD 阶段制里程碑见 §7；CI/CD 暂停，门禁转本地）。阶段一（核心数据链路：Forge 1.20.1 骨架 + mapstore/map_region）已完成 ✅（jar 产出、reobf 完成、审计修复落地、311b4cb）。

## 2. 仓库结构（已按此搭建）

```
common/                 主结构：无任何 net.minecraft.* 引用
  src/main/java/net/yumezawa/queqiao_map_web/
    ModLifecycle.java                    生命周期与装配（入口唯一调用者）
    config/MapExtConfig.java       queqiao_map_web.yml 极简解析（纯 JDK）
    platform/PlatformAdapter.java  ★ 平台适配接口
    event/                         5 个事件（extends 鹊桥 BaseEvent）
    model/                         PlayerPosModel / TilePayloadModel / RegionPayloadModel
    sampler/                       PositionSampler（仅玩家位置采样）
    tile/                          TileQueue / Palette / TopBlockRenderer（瓦片功能集）
    mapstore/                      MapStore(.qms) / BackfillEngine / RegionStreamer / RegionFileReader / NbtReader / ChunkTopScanner / BlockColors（二维地图库与回填/区域流）
    sender/                        EventSink / QueQiaoSink（唯一通道，鹊桥前置）
  src/test/java/               JUnit5 测试：每方法 @Tag("REQ-XXX-NNN")；冒烟类 *SmokeTest + @Tag("smoke")
forge/
  src/                             Forge 入口 + ForgePlatformAdapter + src/main/templates（该分支唯一碰 MC 类的地方）
  versions/1.20.1/gradle.properties 版本覆盖层（MC 版本/映射/Java 版本唯一真相）
fabric/
  src/                             Fabric 入口 + FabricPlatformAdapter + fabric.mod.json（阶段三接入编译）
  versions/<ver>/gradle.properties 1.20.1 … 1.21.11（TODO 值接入编译前按官方矩阵核对）
neoforge/
  src/                             NeoForge 入口 + NeoForgePlatformAdapter + src/main/templates（阶段三）
  versions/1.21.1/  versions/26.1/ 版本覆盖层
（无 libs/）                  compileOnly 鹊桥接口按坐标 com.github.theword.queqiao:queqiao-tool 从 GitHub Packages 联网拉取（凭据 GH_USERNAME/PACKAGE_READ_ONLY_TOKEN 或 gpr.user/gpr.key；运行期由鹊桥本体提供，绝不 shade）；
                              三加载器元数据均声明强制依赖 queqiao≥0.5.0、ordering=AFTER（缺桥=加载器拒绝加载，见 §4.10）
specs/                             行为规格（SDD 唯一契约源）：<域>/spec.md，「### REQ-<域>-NNN」条目（见 specs/README.md）
```

三条演化规则：**加 MC 版本 = 只加一个版本目录（仅 gradle.properties）**；加加载器 = 一个平台分支目录 + 一个适配器；
鹊桥换 API = 换一个 EventSink。业务代码不因版本变化。

## 3. 构建与验证

```bat
set JAVA_HOME=C:\Program Files\BellSoft\LibericaJDK-17-Full   &REM Forge 1.20.1 用 JDK 17
gradlew :common:build :forge:build                &REM 产出 forge/build/libs/queqiao_map_web-forge-*.jar
gradlew :forge:build -PmcTarget=1.20.1    &REM 指定版本覆盖层
```

- Gradle wrapper 8.8（跑 Gradle 本身用 JDK 17–21；1.21.x 分支 toolchain 自动切 LibericaJDK-21）。
- 解析依赖需 GitHub Packages 凭据（环境变量 `GH_USERNAME`+`PACKAGE_READ_ONLY_TOKEN`，或 `~/.gradle/gradle.properties` 的 `gpr.user`/`gpr.key`；CI 注入 `secrets.GITHUB_TOKEN`）：
  新机缺凭据时 queqiao-tool 解析报 401 Unauthorized；固定版本属 non-changing 依赖，本机 Gradle 缓存已命中过即可 `--offline` 离线编译。
- 元数据模板放 `<loader>/src/main/templates/`，processResources 用 Ant ReplaceTokens（`@token@`）渲染；
  **禁用 filesMatching/expand/eachFile**——那类 DSL 产生 MatchingCopyAction，IDEA 资源模型解析不了会告警。
- `gradle.properties` 按 ISO-8859-1 读取：中文值必须 `\\uXXXX` 转义（`mod_authors` 即此法），否则注入模板时双重编码。
- 改动 common 后可用 javac 秒级自检（不需要 MC）。鹊桥接口 jar 用 Gradle 缓存实物（先跑过一次 `gradlew :common:compileJava`，
  jar 位于 %USERPROFILE%\.gradle\caches\modules-2\files-2.1\com.github.theword.queqiao\queqiao-tool\0.6.8\<sha1>\queqiao-tool-0.6.8.jar）：
  javac --release 17 -cp <该 jar> -d out <common 全部 .java>
  （Windows 路径含空格时用 argfile，argfile 内路径加引号并用正斜杠）。
- 接/换鹊桥 API 前用 javap 核对签名，不凭记忆（同一 Gradle 缓存 jar）：
  javap -p -cp <queqiao-tool jar> com.github.theword.queqiao.tool.GlobalContext
  javap -p -cp <queqiao-tool jar> com.github.theword.queqiao.tool.event.base.BaseEvent
  已验证（2026-10）：GlobalContext.sendEvent(BaseEvent) 存在；
  BaseEvent 三参构造器 (String eventName, String postType, String subType)；
  getConfig() 在鹊桥 init 前返回 null —— QueQiaoSink.isReady() 用它做就绪探针（2s 节流）。

### 测试与规格

```bat
gradlew :common:test      &REM JUnit5 单元 + 冒烟测试（common/src/test）
```

- 测试放 `common/src/test/java/`（镜像包路径）；每个方法 `@Tag("REQ-XXX-NNN")` 绑定 `specs/` 中的 REQ；冒烟类命名 `*SmokeTest` + `@Tag("smoke")`。
- `specs/` 为行为唯一真相：行为以规格为准，测试按 REQ id 绑定规格（SDD+TDD 流程见 CONTRIBUTING.md §3；CI spec-coverage 双向校验 REQ↔@Tag）。
- javac 秒级自检（上方）保留为改动 common 后的快速编译检查，不能替代 `:common:test`。

## 4. 发送铁律（所有平台共同遵守）

1. `enabled: false`（默认）= 除一条自检日志外零行为零流量。
2. 只有鹊桥 `GlobalContext` 完成 init 才发送：入口一律在 **ServerStarted** 之后才 `ModLifecycle.start`；
   `QueQiaoSink.isReady()` 再探针。鹊桥是**唯一通道**（本 mod 是鹊桥的附属扩展）：未就绪前完全静默，
   零流量，不存在任何降级通道或直连 WebSocket。
3. `send()` 返回 false = 静默丢弃/退回队列，绝不抛异常打断服务端；所有钩子外层 catch Throwable。
4. `GlobalContext` 只允许出现在 `sender/QueQiaoSink.java` 一个文件里。
5. 不用 Mixin、不 fork 游戏。主线程采样只从内存取区块（区块加载/方块改动事件时机），任何情况下不得在主线程读盘、绝不读 region 文件。
   **边界**：仅 mapstore 回填线程（`BackfillEngine` 自有后台守护线程）允许【后台只读】存档的 mca 区域文件（单文件/chunk 异常吞、mtime 增量跳过）；
   任何情况下不得写 `region/`——本 mod 自有数据只写 store 目录（`<worldSaveRoot>/queqiao_map_web/store/`，蓝图 docs/DESIGN_MAPSTORE.md，语义见 ARCHITECTURE.md §5.5）。
6. 禁止每 tick 全量广播：移动中 ~2.5Hz、静止心跳 ~0.67Hz、位移阈值 1.5 格；
   瓦片 8x8 区块合并（≈20B/区块）+ zlib 调色板索引，绝不用小 PNG（16x16 PNG=938B）。
7. 线程纪律：主线程只做入队 + `adapter.renderChunk`（每 tick 硬上限 maxChunkRendersPerTick=64）；
   量化/压缩/发送只在 `adapter.runAsync` 后台线程；后台线程绝不触碰 Level/Chunk 对象
   （采样已在主线程 capture 成 RGB24 字节数组）。
   **停服豁免**：`TileQueue.drain()`（仅 ServerStopping 路径，一次性）在主线程同步采样+压缩+发送，
   不设每 tick 上限（总量受 pending_cap 封顶）；任一 send 失败立即停止排空、剩余静默丢弃——绝不阻断停服。
   停服顺序：先 `adapter.shutdown()`（摘监听+后台池排空）再 drain，保证 drain 时唯一发送者是主线程。
8. dimension 一律 registry key（如 `minecraft:the_nether`），绝不用世界文件夹名。
9. 协议字段只加不改：`sub_type` 与既有字段语义永不变更，消费端必须忽略未知字段（`docs/INTEGRATION.md` §7）。
10. **强制前置（加载器硬依赖）**：三加载器元数据一律声明 `queqiao≥0.5.0` 为强制依赖——forge `mods.toml` `mandatory=true`、neoforge `neoforge.mods.toml` `type="required"`、fabric `fabric.mod.json` `depends`，均 `ordering=AFTER`。缺鹊桥时加载器直接拒绝加载并抛异常，**没有「静默不启动」中间态**：入口类绝不写 `Class.forName` 缺席探针、缺席分支日志或降级装配（NeoForge/Fabric 入口无此逻辑，Forge 入口 2026-10-19 已清除）；「鹊桥尚未 init」的运行期静默由第 2 条的 isReady 探针独自承担（版本范围与嵌套 jar 实况见 ARCHITECTURE.md §7）。

## 5. 协议（5 个 sub_type，全部 extends BaseEvent，零注册）

| 事件类 | sub_type | 载荷要点 |
|---|---|---|
| PlayerMoveEvent | player_move | players[]: uuid/nickname/x/y/z/yaw/dimension（批量，非逐人一条） |
| MapPaletteEvent | map_palette | id + colors[]（≤256，下标0=未设置；先于任何瓦片；发一次后冻结；已发布后按 palette_republish_interval_ms 周期重发同一快照，REQ-PAL-003 晚接入自愈） |
| MapTileEvent | map_tile | dimension + tiles[]: cx/cz(瓦片左上区块坐标)/size(1或8)/format=zlib-pal8/sha1/b64 |
| MapRegionEvent | map_region | dimension + regions[]: rx/rz(MC 区域坐标，每区域 32×32 区块=512×512 像素)/format=zlib-pal8/xor(Xaero 式库内容校验，断点续传)/sha1/b64 |
| MapDimensionsEvent | map_dimensions | dimensions[]（连接后一次 + 清单变化重发） |

`server_name/server_version/server_type/timestamp` 由鹊桥自动填充，DTO 里绝不手写。
JSON 字段名 = 单单词 camelCase 字段（Gson 反射直出，不受命名策略影响）。
消费端契约（JSON 样例、锚点/size 语义、palette 冻结、sha1/xor 去重与断点续传、zlib-pal8 解码步骤、§9 端到端教程）见 `docs/INTEGRATION.md`。

## 6. 平台 API 矩阵（已按实况 javap/源码核对）

| 事项 | Forge 1.20.1 (official) | NeoForge 1.21.1 | Fabric 1.20.1 (yarn) |
|---|---|---|---|
| 入口 | @Mod net.minecraftforge.fml.common | @Mod net.neoforged.fml.common | ModInitializer |
| 游戏总线 | MinecraftForge.EVENT_BUS | NeoForge.EVENT_BUS | fabric 事件对象 |
| 启动 | ServerStartedEvent/ServerStoppingEvent (net.minecraftforge.event.server) | 同名 (net.neoforged.neoforge.event.server) | ServerLifecycleEvents.SERVER_STARTED/STOPPING (…lifecycle.v1) |
| tick | TickEvent.ServerTickEvent phase==END | ServerTickEvent.Post（无 phase） | ServerTickEvents.END_SERVER_TICK |
| 区块 | ChunkEvent.Load | 同名 (…event.level) | ServerChunkEvents.CHUNK_LOAD |
| 方块破坏 | BlockEvent.BreakEvent | 同名 | PlayerBlockBreakEvents.AFTER |
| 方块放置 | EntityPlaceEvent（仅带 BlockEntity） | 同名 | 无直接事件（TODO 近似） |
| Heightmap | Heightmap.**Types**.OCEAN_FLOOR（javap 47.4.28 核实：1.20.1 official 即复数 Types） | Heightmap.**Types**.OCEAN_FLOOR | Heightmap.Type.OCEAN_FLOOR（yarn 为单数） |
| dimension | level.dimension().location() | 同 Forge | world.getRegistryKey().getValue() |
| 朝向 | getYRot() | getYRot() | getYaw() |
| RL 构造 | ResourceLocation.tryParse(s)（非法返回 null；单参构造器 47.4.x 已 deprecated-for-removal） | ResourceLocation.parse(s) | new Identifier(s) |
| ResourceKey | ResourceKey.create(Registries.DIMENSION, rl) | 同左 | RegistryKey.of(Registry.DIMENSION_KEY, id) |
| 配置目录 | FMLPaths.CONFIGDIR | 同名 | FabricLoader.getInstance().getConfigDir() |
| 事件注册 | `addListener(方法引用)` 无需 @SubscribeEvent（eventbus 6.2.33 javap 实证：addListener 链无注解校验，注解检查只在 register(Object)；jar 内无 InvalidSubscriptionException） | M4 再核（bus 库版本不同，校验可能更严） | 不适用（回调注册） |

> 命名基线：主入口类 QueqiaoMapWeb（三个加载器同名、包隔离，类名同步 modid）；装配核心 ModLifecycle；mod id 统一 queqiao_map_web（与包名、gradle.properties、mods.toml 一致）；Fabric 元数据是 fabric.mod.json；全工程 **无 Mixin**；鹊桥 API 使用前必须 javap 核对（见 §3）。

## 7. 里程碑（TDD+SDD 阶段制）

工作方式（详见 CONTRIBUTING.md §2、specs/README.md）：任何新行为 = ① 先在 `specs/` 立 REQ（SDD：行为规格是唯一契约源）
→ ② 按 REQ「验证：」行先写红灯测试（方法 `@Tag("REQ-XX-NNN")`，TDD）→ ③ 实现再重构转绿 → ④ 本地四项门禁：
`gradlew :common:test` + 架构守卫 + jar 冒烟 + spec↔@Tag 双向映射扫描（javac 秒级自检为快路径，见 §3）。
CI/CD 暂停中：workflow 移入 `.github/workflows.disabled/`，GitHub Actions 不再触发；恢复 = `git mv` 回 `.github/workflows/`。

- **阶段一 ✅ 核心数据链路**（2026-10-19 ~ 2026-10-20）
  - SDD：protocol/sender/tile/config/lifecycle/architecture/artifact/mapstore 八域 REQ 立制（含 REQ-MS-001…008）；语义见 ARCHITECTURE.md §5.5 与 docs/DESIGN_MAPSTORE.md
  - TDD：common 62 测试全绿（javac+junit 独立复验）；冒烟类带 `@Tag("smoke")`
  - 交付：Forge 1.20.1 编译链（jar + reobf + 两轮审计修复）；mapstore + map_region（mca → .qms 地图库 → 区域流，xor32/lastSentXor 断点续传，311b4cb）
  - 历史明细（Heightmap -1、drain 失败出口、addListener 误报驳回、依赖基线 queqiao≥0.5.0 `929453f` 与「勿引入缺席探针」规则 §4.10）：见 git log 与 §6 矩阵
- **阶段二 ← 当前目标：接口契约与三方接入固化**
  - 契约固化（SDD）：五事件 DTO 字段基线只增不改不删（ProtocolBaselineTest / REQ-PROTO-004）；格式令牌语义（`zlib-pal8`、sha1、xor）冻结入 specs；破坏性协议变更必须走新 sub_type / 新格式
  - 接入固化（TDD 端到端验收）：runServer + 鹊桥 + 消费端按 `docs/INTEGRATION.md` §9.2 对 5 个 sub_type 全量解析验收；跨重启 xor 续传、多连接幂等（服务端 sha1/xor 去重）、调色板 `(server_name, id)` 落盘 + 周期重发自愈（REQ-PAL-003）、region_replay_on_start 全量重放验收（REQ-MS-009）；§9.3 时序表+排错清单即验收脚本
  - 门禁：本地四项全绿 + REQ↔@Tag 双向覆盖空 diff（workflow spec-coverage 步骤的本地镜像）
- **阶段三 多平台扩展**：Fabric 1.20.1（fabric-loom 接入编译、放开 settings include、核对版本覆盖层 TODO）与 NeoForge 1.21.1 / 26.1（填 neo_version TODO）；平台特有行为先进 specs 立 REQ；适配器契约场景（5 事件、维度 registry key、worldSaveRoot 语义）每平台跑通同一组 REQ；复核 §6 矩阵 eventbus `addListener` 校验点
- **阶段四 开源与 CI/CD 恢复**：LICENSE / README 终稿；`.github/workflows.disabled/` 移回并在新 `v*` 标签重发 Release（v0.0.1 的 Release 与 tag 已撤，留档 SHA a462a73 可重建）
- 随办清单（原联调备注，归属阶段二前后）：GlobalContext.sendEvent 并发语义与「send=本地入队快操作」假设验证（队列锁内发调色板依赖它）；普通方块放置二级信号（EntityPlaceEvent 仅覆盖 BlockEntity，漏采靠 sha1 去重+区块事件兜底）；ServerStarted 前已加载区块的节流补扫；REGION_MERGE_MIN=4 带宽取舍写入 INTEGRATION；mods.toml 可选字段（displayTest/logoFile）

## 8. 代码风格

Java 17 语法为上限（仅 1.21.x 分支可用 21）；common 包零 MC import、零第三方（只用 JDK + 鹊桥接口）；
日志用 java.util.logging（各分支 Logger 名 = queqiao_map_web.<platform>）；注释中文、标识符英文。
类命名采用功能命名（完整英文单词；主入口类名同步 modid = QueqiaoMapWeb），为 PR 必检项，规范见 CONTRIBUTING.md §1。
