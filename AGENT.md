# AGENT.md — QueQiao Map Web 开发引导

> 动手改代码前先读完本文件；设计权威是 ARCHITECTURE.md。

## 1. 这是什么

把 Minecraft 服务端的「顶层方块地图 + 玩家位置」以事件形式经 **鹊桥（QueQiao）事件桥**
推给 Web 地图。多加载器（Fabric / Forge / NeoForge）多版本，一套主结构。
当前里程碑：M1 = Forge 1.20.1 骨架 ✅（编译通过、jar 产出、reobf 完成、审计修复落地）。

## 2. 仓库结构（已按此搭建）

```
common/                 主结构：无任何 net.minecraft.* 引用
  src/main/java/net/yumezawa/queqiao_map_web/
    ModLifecycle.java                    生命周期与装配（入口唯一调用者）
    config/MapExtConfig.java       queqiao-mapext.yml 极简解析（纯 JDK）
    platform/PlatformAdapter.java  ★ 平台适配接口
    event/                         4 个事件（extends 鹊桥 BaseEvent）
    model/                         PlayerPosModel / TilePayloadModel
    sampler/                       PositionSampler（仅玩家位置采样）
    tile/                          TileQueue / Palette / TopBlockRenderer（瓦片功能集）
    sender/                        EventSink / QueQiaoSink（唯一通道，鹊桥前置）
forge/
  src/                             Forge 入口 + ForgePlatformAdapter + src/main/templates（该分支唯一碰 MC 类的地方）
  versions/1.20.1/gradle.properties 版本覆盖层（MC 版本/映射/Java 版本唯一真相）
fabric/
  src/                             Fabric 入口 + FabricPlatformAdapter + fabric.mod.json（M2 接入编译）
  versions/<ver>/gradle.properties 1.20.1 … 1.21.11（TODO 值接入编译前按官方矩阵核对）
neoforge/
  src/                             NeoForge 入口 + NeoForgePlatformAdapter + src/main/templates（M4）
  versions/1.21.1/  versions/26.1/ 版本覆盖层
libs/queqiao-tool-0.6.8.jar        compileOnly 鹊桥接口（运行期由鹊桥本体提供，绝不 shade）
```

三条演化规则：**加 MC 版本 = 只加一个版本目录（仅 gradle.properties）**；加加载器 = 一个平台分支目录 + 一个适配器；
鹊桥换 API = 换一个 EventSink。业务代码不因版本变化。

## 3. 构建与验证

```bat
set JAVA_HOME=C:\Program Files\BellSoft\LibericaJDK-17-Full   &REM Forge 1.20.1 用 JDK 17
gradlew :common:build :forge:build                &REM 产出 forge/build/libs/queqiao_map_web-*.jar
gradlew :forge:build -PmcTarget=1.20.1    &REM 指定版本覆盖层
```

- Gradle wrapper 8.8（跑 Gradle 本身用 JDK 17–21；1.21.x 分支 toolchain 自动切 LibericaJDK-21）。
- 元数据模板放 `<loader>/src/main/templates/`，processResources 用 Ant ReplaceTokens（`@token@`）渲染；
  **禁用 filesMatching/expand/eachFile**——那类 DSL 产生 MatchingCopyAction，IDEA 资源模型解析不了会告警。
- `gradle.properties` 按 ISO-8859-1 读取：中文值必须 `\\uXXXX` 转义（`mod_authors` 即此法），否则注入模板时双重编码。
- 改动 common 后可用 javac 秒级自检（不需要 MC）：
  javac --release 17 -cp libs\queqiao-tool-0.6.8.jar -d out <common 全部 .java>
  （Windows 路径含空格时用 argfile，argfile 内路径加引号并用正斜杠）。
- 接/换鹊桥 API 前用 javap 核对签名，不凭记忆：
  javap -p -cp libs\queqiao-tool-0.6.8.jar com.github.theword.queqiao.tool.GlobalContext
  javap -p -cp libs\queqiao-tool-0.6.8.jar com.github.theword.queqiao.tool.event.base.BaseEvent
  已验证（2026-10）：GlobalContext.sendEvent(BaseEvent) 存在；
  BaseEvent 三参构造器 (String eventName, String postType, String subType)；
  getConfig() 在鹊桥 init 前返回 null —— QueQiaoSink.isReady() 用它做就绪探针（2s 节流）。

## 4. 发送铁律（所有平台共同遵守）

1. `enabled: false`（默认）= 除一条自检日志外零行为零流量。
2. 只有鹊桥 `GlobalContext` 完成 init 才发送：入口一律在 **ServerStarted** 之后才 `ModLifecycle.start`；
   `QueQiaoSink.isReady()` 再探针。鹊桥是**唯一通道**（本 mod 是鹊桥的附属扩展）：未就绪前完全静默，
   零流量，不存在任何降级通道或直连 WebSocket。
3. `send()` 返回 false = 静默丢弃/退回队列，绝不抛异常打断服务端；所有钩子外层 catch Throwable。
4. `GlobalContext` 只允许出现在 `sender/QueQiaoSink.java` 一个文件里。
5. 不用 Mixin、不 fork 游戏。只从内存取区块（区块加载/方块改动事件时机），绝不读 region 文件。
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

## 5. 协议（4 个 sub_type，全部 extends BaseEvent，零注册）

| 事件类 | sub_type | 载荷要点 |
|---|---|---|
| PlayerMoveEvent | player_move | players[]: uuid/nickname/x/y/z/yaw/dimension（批量，非逐人一条） |
| MapPaletteEvent | map_palette | id + colors[]（≤256，下标0=未设置；先于任何瓦片；发一次后冻结） |
| MapTileEvent | map_tile | dimension + tiles[]: cx/cz(瓦片左上区块坐标)/size(1或8)/format=zlib-pal8/sha1/b64 |
| MapDimensionsEvent | map_dimensions | dimensions[]（连接后一次 + 清单变化重发） |

`server_name/server_version/server_type/timestamp` 由鹊桥自动填充，DTO 里绝不手写。
JSON 字段名 = 单单词 camelCase 字段（Gson 反射直出，不受命名策略影响）。
消费端契约（JSON 样例、锚点/size 语义、palette 冻结、sha1 去重、zlib-pal8 解码步骤）见 `docs/INTEGRATION.md`。

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

## 7. 里程碑

- M1 Forge 1.20.1 骨架 + 编译通过 ✅（2026-10-19 两轮：jar 产出 + reobf；一轮——Heightmap 存储值 -1 修正、
  适配器 register/unregister 生命周期、瓦片队列加锁、鹊桥缺席探针、重启维度基线清空；
  二轮——drain 发送失败出口防停服死循环、停服序「静默→排空」、4 个事件钩子 catch Throwable、
  isReady 探针 volatile、forge 侧 compileOnly 防御行；另实证驳回「addListener 必须 @SubscribeEvent」误报，见 §6）
- M2 Fabric 1.20.1 ← 下一站（听用户调度）：写 fabric/build.gradle（fabric-loom），放开 settings.gradle include，
  核对 fabric 各版本覆盖层里的 TODO 版本号（fabric/ 已有骨架源码未接入编译）
- M3 Forge 实战联调（runServer + 鹊桥 + 消费端按 INTEGRATION.md 收事件）。随办清单：
  GlobalContext.sendEvent 并发语义与「send=本地入队快操作」假设验证（队列锁内发调色板依赖它）；
  普通方块放置二级信号（EntityPlaceEvent 仅覆盖 BlockEntity，漏采靠 sha1 去重+区块事件兜底）；
  ServerStarted 前已加载区块的节流补扫（不补则重启后旧区域空白到玩家回访）；
  REGION_MERGE_MIN=4 带宽取舍写入 INTEGRATION；mods.toml 可选字段（displayTest/logoFile）
- M4 NeoForge 1.21.1 / 26.1（ModDevGradle；填 neo_version TODO）
- M5 开放接入层：4 事件契约冻结 + docs/INTEGRATION.md（接入说明与解析解释；第三方含 owner 自有项目按文档适配）
- M6 开整备：LICENSE / README / CI

## 8. 代码风格

Java 17 语法为上限（仅 1.21.x 分支可用 21）；common 包零 MC import、零第三方（只用 JDK + 鹊桥接口）；
日志用 java.util.logging（各分支 Logger 名 = queqiao_map_web.<platform>）；注释中文、标识符英文。
类命名采用功能命名（完整英文单词；主入口类名同步 modid = QueqiaoMapWeb），为 PR 必检项，规范见 CONTRIBUTING.md §1。
