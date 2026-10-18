# AGENT.md — QueQiao Map Web 开发引导

> 本文件由 AGENT_GUIDE.md 演进而来，已按 **实际实现** 修正命名与 API（见 §7「与旧引导的差异」）。
> 动手改代码前先读完本文件；架构权威是 ARCHITECTURE.md。

## 1. 这是什么

把 Minecraft 服务端的「顶层方块地图 + 玩家位置」以事件形式经 **鹊桥（QueQiao）事件桥**
推给 Web 地图。多加载器（Fabric / Forge / NeoForge）多版本，一套主结构。
当前里程碑：M1 = Forge 1.20.1 骨架（已接入编译）。

## 2. 仓库结构（已按此搭建）

```
origin/                  主结构：无任何 net.minecraft.* 引用
  src/main/java/net/yumezawa/queqiao_map_web/
    MapWeb.java                    生命周期与装配（入口唯一调用者）
    config/MapExtConfig.java       queqiao-mapext.yml 极简解析（纯 JDK）
    platform/PlatformAdapter.java  ★ 平台适配接口
    event/                         4 个事件（extends 鹊桥 BaseEvent）
    model/                         PlayerPosModel / TilePayloadModel
    sampler/                       PositionSampler / TileQueue
    render/                        Palette / TopBlockRenderer
    sender/                        EventSink / QueQiaoSink / StandaloneSink
forge/
  origin/                          Forge 入口 + ForgePlatformAdapter + resources（该分支唯一碰 MC 类的地方）
  forge-1.20.1/gradle.properties   版本覆盖层（MC 版本/映射/Java 版本唯一真相）
fabric/
  origin/                          Fabric 入口 + FabricPlatformAdapter + fabric.mod.json（M2 接入编译）
  fabric-<ver>/gradle.properties   1.20.1 … 1.21.11（TODO 值接入编译前按官方矩阵核对）
neoforge/
  origin/                          NeoForge 入口 + NeoForgePlatformAdapter + neoforge.mods.toml 模板（M4）
  neoforge-1.21.1/  neoforge-26.1/ 版本覆盖层
libs/queqiao-tool-0.6.8.jar        compileOnly 鹊桥接口（运行期由鹊桥本体提供，绝不 shade）
```

三条演化规则：**加 MC 版本 = 只加一个版本目录（仅 gradle.properties）**；加加载器 = 一个平台分支目录 + 一个适配器；
鹊桥换 API = 换一个 EventSink。业务代码不因版本变化。

## 3. 构建与验证

```bat
set JAVA_HOME=C:\Program Files\BellSoft\LibericaJDK-17-Full   &REM Forge 1.20.1 用 JDK 17
gradlew :origin:build :forge:origin:build        &REM 产出 forge/origin/build/libs/queqiao_map_web-*.jar
gradlew :forge:origin:build -PmcTarget=1.20.1    &REM 指定版本覆盖层
```

- Gradle wrapper 8.8（跑 Gradle 本身用 JDK 17–21；1.21.x 分支 toolchain 自动切 LibericaJDK-21）。
- 改动 origin 后可用 javac 秒级自检（不需要 MC）：
  javac --release 17 -cp libs\queqiao-tool-0.6.8.jar -d out <origin 全部 .java>
  （Windows 路径含空格时用 argfile，argfile 内路径加引号并用正斜杠）。
- 接/换鹊桥 API 前用 javap 核对签名，不凭记忆：
  javap -p -cp libs\queqiao-tool-0.6.8.jar com.github.theword.queqiao.tool.GlobalContext
  javap -p -cp libs\queqiao-tool-0.6.8.jar com.github.theword.queqiao.tool.event.base.BaseEvent
  已验证（2026-10）：GlobalContext.sendEvent(BaseEvent) 存在；
  BaseEvent 三参构造器 (String eventName, String postType, String subType)；
  getConfig() 在鹊桥 init 前返回 null —— QueQiaoSink.isReady() 用它做就绪探针（2s 节流）。

## 4. 发送铁律（所有平台共同遵守）

1. `enabled: false`（默认）= 除一条自检日志外零行为零流量。
2. 只有鹊桥 `GlobalContext` 完成 init 才发送：入口一律在 **ServerStarted** 之后才 `MapWeb.start`；
   `QueQiaoSink.isReady()` 再探针。
3. `send()` 返回 false = 静默丢弃/退回队列，绝不抛异常打断服务端；所有钩子外层 catch Throwable。
4. `GlobalContext` 只允许出现在 `sender/QueQiaoSink.java` 一个文件里。
5. 不用 Mixin、不 fork 游戏。只从内存取区块（区块加载/方块改动事件时机），绝不读 region 文件。
6. 禁止每 tick 全量广播：移动中 ~2.5Hz、静止心跳 ~0.67Hz、位移阈值 1.5 格；
   瓦片 8x8 区块合并（≈20B/区块）+ zlib 调色板索引，绝不用小 PNG（16x16 PNG=938B）。
7. 线程纪律：主线程只做入队 + `adapter.renderChunk`（每 tick 硬上限 maxChunkRendersPerTick=64）；
   量化/压缩/发送只在 `adapter.runAsync` 后台线程；后台线程绝不触碰 Level/Chunk 对象
   （采样已在主线程 capture 成 RGB24 字节数组）。
8. dimension 一律 registry key（如 `minecraft:the_nether`），绝不用世界文件夹名。

## 5. 协议（4 个 sub_type，全部 extends BaseEvent，零注册）

| 事件类 | sub_type | 载荷要点 |
|---|---|---|
| PlayerMoveEvent | player_move | players[]: uuid/nickname/x/y/z/yaw/dimension（批量，非逐人一条） |
| MapPaletteEvent | map_palette | id + colors[]（≤256，下标0=未设置；先于任何瓦片；发一次后冻结） |
| MapTileEvent | map_tile | dimension + tiles[]: cx/cz(瓦片左上区块坐标)/size(1或8)/format=zlib-pal8/sha1/b64 |
| MapDimensionsEvent | map_dimensions | dimensions[]（连接后一次 + 清单变化重发） |

`server_name/server_version/server_type/timestamp` 由鹊桥自动填充，DTO 里绝不手写。
JSON 字段名 = 单单词 camelCase 字段（Gson 反射直出，不受命名策略影响）。

## 6. 平台 API 矩阵（已按 2025-10 实况修正）

| 事项 | Forge 1.20.1 (official) | NeoForge 1.21.1 | Fabric 1.20.1 (yarn) |
|---|---|---|---|
| 入口 | @Mod net.minecraftforge.fml.common | @Mod net.neoforged.fml.common | ModInitializer |
| 游戏总线 | MinecraftForge.EVENT_BUS | NeoForge.EVENT_BUS | fabric 事件对象 |
| 启动 | ServerStartedEvent/ServerStoppingEvent (net.minecraftforge.event.server) | 同名 (net.neoforged.neoforge.event.server) | ServerLifecycleEvents.SERVER_STARTED/STOPPING (…lifecycle.v1) |
| tick | TickEvent.ServerTickEvent phase==END | ServerTickEvent.Post（无 phase） | ServerTickEvents.END_SERVER_TICK |
| 区块 | ChunkEvent.Load | 同名 (…event.level) | ServerChunkEvents.CHUNK_LOAD |
| 方块破坏 | BlockEvent.BreakEvent | 同名 | PlayerBlockBreakEvents.AFTER |
| 方块放置 | EntityPlaceEvent（仅带 BlockEntity） | 同名 | 无直接事件（TODO 近似） |
| Heightmap | Heightmap.Type.OCEAN_FLOOR | Heightmap.**Types**.OCEAN_FLOOR | Heightmap.Type.OCEAN_FLOOR |
| dimension | level.dimension().location() | 同 Forge | world.getRegistryKey().getValue() |
| 朝向 | getYRot() | getYRot() | getYaw() |
| RL 构造 | new ResourceLocation(s) | ResourceLocation.parse(s) | new Identifier(s) |
| ResourceKey | ResourceKey.create(Registries.DIMENSION, rl) | 同左 | RegistryKey.of(Registry.DIMENSION_KEY, id) |
| 配置目录 | FMLPaths.CONFIGDIR | 同名 | FabricLoader.getInstance().getConfigDir() |

## 7. 与旧 AGENT_GUIDE.md 的差异（已修正，勿回退）

- 入口类名：MapWeb / MapWebForge / MapWebFabric / MapWebNeoForge（不是 MapExt*；MapExtConfig 只是配置类名）。
- mod id 统一 queqiao_map_web（不是 queqiaomap），与 gradle.properties、Java 包名、mods.toml 一致。
- Fabric 元数据是 **fabric.mod.json**（fabric.mod.json5 不被 loader 接受；ARCHITECTURE.md 中的 json5 写法是笔误级历史）。
- 全工程 **无 Mixin**：mixins.json / mixingradle / refmap 已全部移除。
- 旧引导里「未验证」的鹊桥 API 均已 javap 验证（见 §3）。

## 8. 里程碑

- M1 Forge 1.20.1 骨架 + 编译通过 ← 当前位置
- M2 Fabric 1.20.1：写 fabric/origin/build.gradle（fabric-loom），放开 settings.gradle include，
  核对 fabric 各版本覆盖层里的 TODO 版本号
- M3 Forge 实战联调（runServer + 鹊桥 + AstrBot 收事件）
- M4 NeoForge 1.21.1 / 26.1（ModDevGradle；填 neo_version TODO）
- M5 AstrBot 侧 MapDataHandler（Python，不在本仓库）
- M6 开整备：LICENSE / README / CI

## 9. 代码风格

Java 17 语法为上限（仅 1.21.x 分支可用 21）；origin 包零 MC import、零第三方（只用 JDK + 鹊桥接口）；
日志用 java.util.logging（各分支 Logger 名 = queqiao_map_web.<platform>）；注释中文、标识符英文。
