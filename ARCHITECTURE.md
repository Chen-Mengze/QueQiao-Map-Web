# QueQiao Map Web — 架构设计书

> 类名与 API 均按鹊桥（QueQiao）main 分支源码核对，非推断。

---

## 0. 适配目标

| 加载器   | 版本分支                                                               | 说明                                           |
|----------|------------------------------------------------------------------------|------------------------------------------------|
| Fabric   | 1.20.1 · 1.20.4 · 1.21.1 · 1.21.4 · 1.21.5 · 1.21.6 · 1.21.9 · 1.21.11 | 1.20.1 起                                      |
| Forge    | **仅 1.20.1**                                                          | 不做 1.21+（NeoForge 自 1.20.2 从 Forge 分出） |
| NeoForge | 1.21.1 · 26.1                                                          | 1.21.1 起                                      |

工作重心是 **模组端**。插件端（Paper/Spigot/Folia）生态已有现成方案，不在主链路内。
本仓库只做服务端 mod 与协议侧：**开放数据结构与 API**（5 事件契约 + `docs/INTEGRATION.md`）
供第三方消费端适配——含 owner 自有项目、AstrBot 插件与任何自研 Web 地图端。

## 1. 事实基础：不需要 Mixin，也不需要 fork

以下均读自鹊桥 `main` 分支源码， **非推断**。例中 `fabric|forge|neoforge/origin/...` 是**鹊桥自己仓库**的目录布局（本仓库为对齐标准 mod 已改用 `common/` + 扁平 `<loader>/`，见 §2）。

### 1.1 鹊桥自己就是靠 `GlobalContext.sendEvent` 发事件的

`fabric/origin/src/main/java/com/github/theword/queqiao/mixin/ServerPlayerEntityMixin.java`：

```java
PlayerDeathEvent event = new PlayerDeathEvent(getFabricPlayer(player), translateModel);
GlobalContext.

sendEvent(event);
```

`forge/origin/.../EventProcessor.java`：

```java
PlayerChatEvent forgeServerChatEvent = new PlayerChatEvent(player, "", rawMessage, message);
GlobalContext.

sendEvent(forgeServerChatEvent);
```

`neoforge/origin/.../EventProcessor.java` 同构，用 `getNeoForgePlayer()` + `GlobalContext.sendEvent(...)`。

**`GlobalContext` 是 `public static` 的公开入口**（包 `com.github.theword.queqiao.tool.GlobalContext`）。第三方 mod 直接调用即可，
**无需 Mixin 注入、无需反射、无需 fork**。

### 1.2 事件名是构造函数传入的字符串，没有白名单

`io/github/theword/queqiao/core/event/PlayerChatEvent.java`：

```java
public PlayerChatEvent(PlayerModel playerModel, String messageId, String rawMessage, String message) {
    super("PlayerChatEvent", "player_chat", playerModel, messageId, rawMessage);
    this.message = message;
}
```

基类 `BaseEvent(String eventName, String postType, String subType)`，Gson 反射序列化 POJO。
因此 **新增一个事件类型只需新建一个类，协议侧零注册**。

### 1.3 `PlayerModel` 已内置 x / y / z

鹊桥三个平台的 Tool 类都在填坐标：

| 平台     | 方法                                             | 坐标来源                                     |
|----------|--------------------------------------------------|----------------------------------------------|
| Fabric   | `FabricTool.getFabricPlayer(ServerPlayerEntity)` | `player.setX(fabricPlayer.getX())`           |
| Forge    | `ForgeTool.getForgePlayer(ServerPlayer)`         | `playerModel.setX(forgeServerPlayer.getX())` |
| NeoForge | `NeoForgeTool.getNeoForgePlayer(ServerPlayer)`   | `player.setX(neoForgePlayer.getX())`         |

**唯一缺的是 `dimension`** —— `PlayerModel` 没有该字段。这是本项目需要补的唯一数据项，也是我们自建 `PlayerPosModel` 的理由。

### 1.4 生命周期时机（决定我们何时能发）

| 平台     | 入口类                              | 事件                                                     | 关键调用                                                                                                              |
|----------|-------------------------------------|----------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| Fabric   | `QueQiao implements ModInitializer` | `ServerLifecycleEvents.SERVER_STARTED`                   | `GlobalContext.init(true, ver, ServerTypeConstant.FABRIC, new HandleApiImpl(), new HandleCommandReturnMessageImpl())` |
| Forge    | `@Mod(BaseConstant.MOD_ID) QueQiao` | `net.minecraftforge.event.server.ServerStartedEvent`     | `GlobalContext.init(..., ServerTypeConstant.FORGE, ...)`                                                              |
| NeoForge | `@Mod(BaseConstant.MOD_ID) QueQiao` | `net.neoforged.neoforge.event.server.ServerStartedEvent` | `GlobalContext.init(..., ServerTypeConstant.NEOFORGE, ...)`                                                           |

关闭时均调 `GlobalContext.shutdown()`。

> **时序硬约束**：必须晚于 `GlobalContext.init` 才能 `sendEvent`。三个平台统一在 `ServerStarted` 之后再注册我们的采集器。

### 1.5 版本差异速查（决定是否要写条件编译）

| 项           | Fabric 1.20.1                                     | Forge 1.20.1                              | NeoForge 1.21.1                           |
|--------------|---------------------------------------------------|-------------------------------------------|-------------------------------------------|
| Java         | 17                                                | 17                                        | 21                                        |
| 映射         | yarn `1.20.1+build.10`                            | official                                  | parchment `2024.11.17`                    |
| 玩家类       | `net.minecraft.server.network.ServerPlayerEntity` | `net.minecraft.server.level.ServerPlayer` | `net.minecraft.server.level.ServerPlayer` |
| 维度取值     | `world.getRegistryKey().getValue()`               | `level.dimension()`                       | `level.dimension()`                       |
| tick 挂点    | `ServerTickEvents.END_SERVER_TICK`                | `TickEvent.ServerTickEvent`               | `TickEvent.ServerTickEvent`               |
| 事件总线注册 | `ServerLifecycleEvents`                           | `MinecraftForge.EVENT_BUS`                | `NeoForge.EVENT_BUS`                      |

> Forge 1.20.1 后还有 `forge-1.21` 分支（`>= forge-1.21` 时 `Advancement` API 变化、`Component.Serializer.toJson` 需
> `registryAccess()`），但我们不做该分支，故无需条件编译。

---

## 2. 工程结构（主结构 + 三平台多版本分支）

采用 **混合多分支**：根目录一份公共 `common/`，下面按加载器分三个平台分支，
每个平台分支内再按 MC 版本分目录。与鹊桥自身的组织方式一致。

```
QueQiao Map Web/
├── common/                              ← 主结构：与 MC 无关的公共业务实现（多加载器标准目录名）
│   └── src/main/java/net/yumezawa/queqiao_map_web
│       ├── ModLifecycle.java                             生命周期与装配
│       ├── config/MapExtConfig.java                开关 / 节流 / 批量参数（含 yml 极简解析）
│       ├── platform/PlatformAdapter.java           ★ 平台适配接口（此目录不含 MC 类）
│       ├── event/                                  五个新增事件 DTO（继承鹊桥 BaseEvent）
│       │   ├── PlayerMoveEvent.java
│       │   ├── MapTileEvent.java
│       │   ├── MapPaletteEvent.java
│       │   ├── MapRegionEvent.java
│       │   └── MapDimensionsEvent.java
│       ├── model/
│       │   ├── PlayerPosModel.java                 含 dimension（补 PlayerModel 缺口）
│       │   ├── TilePayloadModel.java               cx/cz/size/format/sha1/b64
│       │   └── RegionPayloadModel.java             rx/rz/format/xor/sha1/b64
│       ├── sampler/PositionSampler.java            玩家位置采样：差分 + 节流 + last-value 合并
│       ├── tile/                                   瓦片功能集：刷写队列 + 调色板 + 编码
│       │   ├── TileQueue.java                      脏区块去重 + 批量刷写
│       │   ├── Palette.java                        共享 256 色，只发一次
│       │   └── TopBlockRenderer.java               zlib + sha1 + base64 打包
│       ├── mapstore/                              二维地图库与回填/区域流（纯 JDK）
│       │   ├── MapStore.java                       .qms 区域库（xor32/lastSentXor 断点续传）
│       │   ├── BackfillEngine.java                 后台守护线程只读 mca 回填（令牌桶）
│       │   ├── RegionStreamer.java                 区域流 map_region（按压缩字节节流）
│       │   └── RegionFileReader / NbtReader / ChunkTopScanner / BlockColors   mca 解码链
│       └── sender/
│           ├── EventSink.java                      ★ 发送器抽象
│           └── QueQiaoSink.java                    唯一实现：GlobalContext.sendEvent（鹊桥前置）
│
├── fabric/                              ← Fabric 分支：1.20.1 起（阶段三；src/ 是唯一接触 MC 类的地方）
│   ├── src/main/java/net/yumezawa/queqiao_map_web/fabric/
│   │   ├── QueqiaoMapWeb.java                       入口：ModInitializer
│   │   └── platform/FabricPlatformAdapter.java
│   ├── src/main/resources/fabric.mod.json
│   └── versions/<ver>/gradle.properties             1.20.1 … 1.21.11，每版本一目录
│
├── forge/                               ← Forge 分支：仅 1.20.1
│   ├── build.gradle                                 ForgeGradle 6 构建（工程 ':forge'）
│   ├── src/main/java/net/yumezawa/queqiao_map_web/forge/
│   │   ├── QueqiaoMapWeb.java                       入口：@Mod + MinecraftForge.EVENT_BUS
│   │   └── platform/ForgePlatformAdapter.java
│   ├── src/main/templates/META-INF/mods.toml        （与 pack.mcmeta，ReplaceTokens 渲染）
│   └── versions/1.20.1/gradle.properties
│
└── neoforge/                            ← NeoForge 分支：1.21.1 起（阶段三）
    ├── src/main/java/net/yumezawa/queqiao_map_web/neoforge/
    │   ├── QueqiaoMapWeb.java                       入口：@Mod + NeoForge.EVENT_BUS
    │   └── platform/NeoForgePlatformAdapter.java
    ├── src/main/templates/META-INF/neoforge.mods.toml
    ├── versions/1.21.1/gradle.properties
    └── versions/26.1/gradle.properties              ⚠️ neo_version 待按官方矩阵填
```

### 2.1 为什么这样分层

`common/`（主结构）里 **不出现任何 `net.minecraft.*` 类**；所有游戏侧访问收敛到
`PlatformAdapter` 接口，由 `fabric/`、`forge/`、`neoforge/` 各实现一份。

- **加 MC 版本** = 加一个 `versions/<ver>/` 目录（仅 gradle.properties）（版本差异大时才在平台 src 里加条件块）
- **加加载器** = 加一个平台分支 + 一个适配器实现类
- **鹊桥重构 `sendEvent`** = 换一个 `EventSink` 实现

这是对"每个版本一份 fork"的结构性回答： **版本只影响配置，不影响业务代码**。

### 2.2 版本目录里放什么

版本目录原则上 **只有 `gradle.properties`**（MC 版本、映射、loader、Java 版本）。
源码共用平台 `common/`，由构建工具按条件编译块生成各版本源码。

只有当某个版本的 API 差异无法用条件块表达时，才在该版本目录下放覆盖文件。

### 2.3 Java 版本分界

| 范围                                 | Java |
|--------------------------------------|------|
| Fabric 1.20.1 / 1.20.4、Forge 1.20.1 | 17   |
| Fabric 1.21.1+、NeoForge 1.21.1+     | 21   |

## 3. 平台适配接口

```java
public interface PlatformAdapter {
    final class PlayerSnapshot {
        public String uuid, name;
        public double x, y, z;
        public float yaw;
        /** registry key，如 minecraft:the_nether。绝不用世界文件夹名 */
        public String dimension;
    }

    final class ChunkPos {
        public final int x, z;
    }

    void onTick(Consumer<List<PlayerSnapshot>> consumer);

    void onChunkDirty(ChunkDirtyListener cb);

    byte[] renderChunk(String dimension, ChunkPos pos);

    List<String> listDimensions();

    /** 存档根（可返回 null = 不支持：地图库关闭，仅实时流），见 §5.5 */
    Path worldSaveRoot();

    void runAsync(Runnable task);

    String platformName();
}
```

> 注意：`renderChunk(String dimension, ChunkPos)` 用 dimension 字符串作键，内部由平台实现自行 `getLevel(dimension)`，避免
> common 持有 `Level` 对象。

---

## 4. 协议契约

全部继承鹊桥 `BaseEvent`，因此 `event_name / post_type / timestamp` 及鹊桥自动填充的
`server_name / server_version / server_type` 都由框架给出，后端无需额外对齐。

### 4.1 PlayerMoveEvent

```json
{
  "event_name": "PlayerMoveEvent",
  "post_type": "notice",
  "sub_type": "player_move",
  "server_name": "...",
  "server_version": "1.20.1",
  "server_type": "fabric",
  "timestamp": 1782000000,
  "players": [
    {
      "uuid": "...",
      "nickname": "Alice",
      "x": 120.4,
      "y": 64.0,
      "z": -302.7,
      "yaw": 90.0,
      "dimension": "minecraft:the_nether"
    }
  ]
}
```

**批量发送，非逐人逐条。** `dimension` 一律 registry key。

### 4.2 MapTileEvent

```json
{
  "event_name": "MapTileEvent",
  "post_type": "notice",
  "sub_type": "map_tile",
  "dimension": "minecraft:overworld",
  "tiles": [
    {
      "cx": 14,
      "cz": -9,
      "size": 8,
      "format": "zlib-pal8",
      "sha1": "...",
      "b64": "..."
    }
  ]
}
```

### 4.3 MapPaletteEvent（连接后发一次）

```json
{
  "event_name": "MapPaletteEvent",
  "post_type": "notice",
  "sub_type": "map_palette",
  "id": "vanilla-1.20.1-a",
  "colors": [
    "#7fb238",
    "..."
  ]
}
```

### 4.4 MapDimensionsEvent（连接后发一次 + 变化时）

```json
{
  "event_name": "MapDimensionsEvent",
  "post_type": "notice",
  "sub_type": "map_dimensions",
  "dimensions": [
    "minecraft:overworld",
    "minecraft:the_nether",
    "minecraft:the_end",
    "twilightforest:twilight_forest"
  ]
}
```

### 4.5 MapRegionEvent（区域流：全量/续传）

```json
{
  "event_name": "MapRegionEvent",
  "post_type": "notice",
  "sub_type": "map_region",
  "dimension": "minecraft:overworld",
  "regions": [
    {
      "rx": 0,
      "rz": -1,
      "format": "zlib-pal8",
      "xor": 1234567890,
      "sha1": "...",
      "b64": "..."
    }
  ]
}
```

`rx / rz` = MC 区域坐标（每区域覆盖区块 `[rx*32,(rx+1)*32) × [rz*32,(rz+1)*32)`，即 32×32 区块 = 512×512 像素，1 像素 = 1 方块列）。像素载荷 = 调色板量化后的 pal8 512×512 → zlib，复用 `zlib-pal8` token。`xor` = MapStore 对该区域**未压缩 RGB24** 载荷的 Xaero 式异或校验（变更检测与断点续传，见 §5.5）；`sha1` = 压缩载荷字节的 hex 摘要。区域载荷 = **整块替换**该范围，与 `map_tile` 增量叠加幂等；palette 必须先于任何 `map_region`（与 `map_tile` 同序铁律）。

---

## 5. 带宽：实测结论

用模拟地形（成片连续 + 2% 散点噪声）实测压缩，折算 **每区块字节数**：

| 批量尺寸         |   zlib | WebP(无损) |     PNG |
|------------------|-------:|-----------:|--------:|
| 1×1（16px）      |    100 |        166 | **938** |
| 2×2（32px）      |     80 |         78 |     291 |
| 4×4（64px）      |     36 |         39 |      90 |
| **8×8（128px）** | **20** |     **20** |      34 |
| 32×32（512px）   |   14.5 |       12.5 |    15.6 |

**两条硬结论**：

1. **不要用小尺寸 PNG。** 16×16 单块 PNG 要 938 B，是 zlib 索引的 9 倍——PNG 的 IHDR+PLTE+IDAT+IEND 头部在小图上完全支配体积。
2. **批量是最大杠杆。** 逐块 100 B/块；8×8 批量降至 **20 B/块**，差 5 倍。

`TileQueue` 采用 **双触发刷写**：

- 队列攒够 `batchSize`（默认 64 = 8×8）→ 立即刷
- 距上次刷写超过 `flushIntervalMs`（默认 1000）→ 强制刷（保地图跟上探索）

**稳态成本**：区块只在首次生成或被玩家改动时发一次，探索前沿之外几乎为零。

| 场景              | 新增区块/秒 |     逐块 | 8×8 批量 |
|-------------------|------------:|---------:|---------:|
| 1 人步行          |         5.4 | 0.5 kB/s | 0.1 kB/s |
| 10 人分散         |          54 | 5.3 kB/s | 1.1 kB/s |
| 50 人高速（鞘翅） |         938 |  92 kB/s |  19 kB/s |
| 区域流 map_region（一次性全量回填） | — | — | 单区域 15–60 KB（zlib 后）@ 默认 64 KB/s |

**map_region（区域流）**：单区域 512×512 pal8 raw 256 KB → 自然地形 zlib 后典型 **15–60 KB**；默认 **64 KB/s** 节流（`region_stream_bytes_per_sec: 65536`）——2000 区域一次性全量 ≈ **10–20 分钟**，此后仅 xor 变化区域（见 §5.5）。

存储侧：8000×8000 格（100 万区块）约 89 MB，32000×32000 格约 1.4 GB。

玩家位置是小头：约 200 B/条，节流后几十字节级。 **常态上行大概率在个位数 kB/s。**

---
## 5.5 地图库与区域流（mapstore / map_region）

> 设计蓝图见 [docs/DESIGN_MAPSTORE.md](docs/DESIGN_MAPSTORE.md)（保留作设计记录）；落地语义以本节与 `specs/mapstore` 为准。
> 新增包 `common/…/mapstore/` 纯 JDK、零 `net.minecraft.*`；主线程零新增开销（采样铁律不变，AGENT.md §4.5）。

### 三流架构

存档 mca（3D，32×32 区块/区域）经 mapstore 解码为 **512×512 px RGB24 俯视图**，先落**本地二维地图库 MapStore**（`.qms` 文件），再对外发送。数据共三条流：

| 流 | 链路 | 语义 |
|---|---|---|
| 实时增量流（现有） | 脏区块 → 主线程采样 RGB24 → `TileQueue` 发 `map_tile` | 语义不动；新增**写穿 MapStore**（`putChunk`），store 保持最新、xor 随之演进 |
| 回填流（新） | `BackfillEngine` 自有后台线程【只读】mca → 解码顶层方块 → RGB → `putRegion` 整区域入库 | 只补 store 缺失/更新的区域（mtime 比对）；不产 map_tile，避免与区域流重复；按 chunk 令牌桶节流 |
| 区域流（新） | `RegionStreamer` 把 MapStore 内容按区域打包成 `map_region` 事件限速流式输出 | 鹊桥就绪且 palette 已发后启动；`xor32 == lastSentXor` 的区域跳过 → 重启/重连近零流量 |

鹊桥没有反向请求通道，「用时发送」由 xor 续传近似：消费端已一致的区域，服务端直接跳过不发。

### 本地地图库（MapStore，.qms 格式）

位置：`<worldSaveRoot()>/queqiao_map_web/store/<dimFolder>/r.<rx>.<rz>.qms`——dimFolder 沿用 MC 维度目录约定（overworld 直名，其余 `ns_path`），但属**本 mod 自有目录，绝不写入存档 `region/`**。`worldSaveRoot()` 为 PlatformAdapter 新契约方法，返回 null = 不支持（store 关闭，仅实时流）。

文件布局（统一大端）：

```
magic QMS1 | u16 side=512 | u8 formatVer=1 | u32 xor32 | u32 lastSentXor
| u64 mtimeMillis | u32 payloadLen | zlib(RGB24 512×512×3=786432B 行优先)
```

- **xor32**：对 payload **原始字节**按 4 字节大端组做 XOR——Xaero 式异或校验，标识「库内容版本」；与 wire 侧 `sha1`（压缩字节摘要）分属两层，两个都要发。
- **lastSentXor**：最近一次成功外发的区域 xor（发送成功后 `markSent` 更新）。`(server, dimension, rx, rz) + xor` 变化才重发 = **断点续传**；`mtimeMillis` 与 mca mtime 比对做回填增量判定。
- 全 0 像素 = 未采样（palette 下标 0 = 透明），与 map_tile 既有约定一致。API 全部线程安全（维度目录 + 文件粒度锁）。

### 消费端语义与预算

- **全量**：连接后 mod 自动按区域流式补发全部已生成区域（`map_region`），无需消费端请求；新开服务器近乎空白，随运行从初始逐步扩展。
- **幂等**：按 `(server, dimension, rx, rz) + xor/sha1` 去重；区域到达 = **整块替换**该 32×32 区块范围；`map_tile`（size 1/8）增量 = 局部覆盖——二者叠加幂等，到达顺序无关。
- **预算**：单区域 512×512 pal8 raw 256 KB → 自然地形 zlib 后典型 **15–60 KB**；默认 **64 KB/s** 节流，2000 区域一次性全量 ≈ **10–20 分钟**，此后仅变化区域。消费端契约细节见 docs/INTEGRATION.md §3.5。

---

## 6. 兼容性设计（扩展，而非改写）

诉求： **不影响原鹊桥插件（不支持地图显示的那些）。**

原插件 `MinecraftPlatformAdapter.handle_mc_message()` 用 **字典**分发，已知 `custom_event` 为 "Ignored"。我们的事件是新
`sub_type`，字典里没有 → 落到忽略分支。但这不够保险。

### 三层保护

**第 1 层 · mod 端默认关闭（最关键）**

```yaml
# config/QueQiao/queqiao_map_web.yml
enabled: false        # 不开则一条新事件都不发
```

不开启时，扩展 mod 除一次自检日志外 **零行为、零流量**。

**第 2 层 · 全新 sub_type 命名空间**

`player_move` / `map_tile` / `map_region` / `map_palette` / `map_dimensions` 与既有取值零冲突，不复用、不改写任何既有事件。

**第 3 层 · 能力协商（规划项）**

开启后先低频发 `map_hello`，后端回 `map_subscribe` 才开始推流。老后端不回 → 永远只有无害 hello。

> 当前 `QueQiaoExtension` SPI 只支持"贡献下行 API"、拿不到 `WebsocketManager`，故当前先用配置开关落地；后续可经注册式扩展点**注册**一个
> api（属"注册"而非"改写"）。

### 消费端（第三方，含 AstrBot 插件）：只做加法

```python
async def handle_mc_message(self, raw):
    handler = self.event_handlers.get(raw.get("sub_type"))
    if handler: await handler(raw)          # 原逻辑，一行不改
    await self.map_handler.try_handle(raw)  # 新增旁路
```

`try_handle` 依次检查 `enabled`（默认 False）→ sub_type 白名单 → 全程 `try/except` 兜底， **异常只记 debug，绝不抛回 WS
接收循环**（否则打断整条连接）。地图/位置数据 **不进 MessageHandler**，不触发 LLM、不转发 QQ。

第三方消费的完整契约（JSON 样例、字段语义、zlib-pal8 解码步骤、xor 断点续传、§9 端到端教程）见 `docs/INTEGRATION.md`。

---

## 7. 依赖处理（实践坑）

工具库坐标 `com.github.theword.queqiao:queqiao-tool:0.6.8`，发布在 **GitHub Packages** —— 即使 public 仓库也要求认证。

**解法（现行）**：联网拉取。`repositories` 加 GitHub Packages `https://maven.pkg.github.com/17TheWord/QueQiaoTool`
（与鹊桥官方构建同源），`compileOnly`/`testImplementation` 坐标 `com.github.theword.queqiao:queqiao-tool:0.6.8`
（版本单一真相在根 `gradle.properties` 的 `queqiao_tool_version`）。**公开包也要求任意有效令牌**：环境变量
`GH_USERNAME` + `PACKAGE_READ_ONLY_TOKEN`（只勾 `read:packages` 的 PAT），或 `~/.gradle/gradle.properties` 的
`gpr.user`/`gpr.key`；CI 注入 `secrets.GITHUB_TOKEN`（workflow 已配）。仓库不留 `libs/` 本地副本。
运行时真实类由鹊桥本体 jar 的嵌套 `META-INF/jars/queqiao-tool-*.jar`（`include modImplementation(...)` 打包、
**不 relocate**）提供，各加载器的 mod 类加载器互通；绝不 shade。

**加载器硬依赖**：三个加载器元数据（`mods.toml` / `neoforge.mods.toml` / `fabric.mod.json`）均已声明 `queqiao`
≥0.5.0 为强制依赖（fabric `depends`、forge `mandatory=true`、neoforge `type="required"`，均 `ordering=AFTER`）：
缺少鹊桥时加载器直接拒绝加载并抛异常，本 mod 不再有「静默不启动」的中间态。

> ⚠️ **实测核对**：鹊桥 0.5.0–0.5.2 内嵌 tool `0.6.8`，0.5.3 内嵌 `0.6.10`（jar 内 `META-INF/jars/` 实物验证，
> 包名均 `com.github.theword.queqiao.tool.*` 不 relocate）；编译基线锁 `0.6.8`（已验证 `BaseEvent(String, String,
> String)` 构造与 `GlobalContext.sendEvent` 签名，38 个契约测试全绿）。新 `queqiao-core` 换包名为
> `io.github.theword.queqiao.core.*` 且移除 `GlobalContext`——届时迁移另立 REQ，勿顺手改。

---

## 8. 里程碑（TDD+SDD 阶段制）

任何新行为先在 `specs/` 立 REQ（SDD），再按 REQ「验证：」行先写 `@Tag` 红灯测试（TDD），实现转绿后重构，再过门禁。
CI/CD 暂停中（workflow 移入 `.github/workflows.disabled/`），门禁本地跑同样四项：`:common:test` + 架构守卫 + jar 冒烟 + spec-coverage 双向映射。

| 阶段 | 内容 | 验收（SDD ⇄ TDD） |
|------|------------------------------------------|--------------------------------------------------|
| 阶段一 ✅ | 核心数据链路：Forge 1.20.1 编译链 + 瓦片链路 + mapstore/map_region（mca→.qms→区域流、xor 续传） | specs 八域 REQ 立制；62/62 @Tag 测试绿；jar + reobf（311b4cb） |
| 阶段二 ← 当前 | 接口契约与三方接入固化：五事件字段基线冻结（只增）、格式令牌语义冻结、INTEGRATION §1–§9 即对外契约 | 本地四项门禁全绿 + REQ↔@Tag 双向覆盖空 diff；第三方（含 owner 自有项目）按 §9.2 端到端解析全 5 个 sub_type、xor 跨重启续传、调色板落盘 |
| 阶段三 | 多平台扩展：Fabric 1.20.1 接入编译、NeoForge 1.21.1 / 26.1 适配层 | 平台特有行为先进 specs 立 REQ；适配器契约场景（5 事件/维度 registry key/worldSaveRoot）每平台跑通同一组 REQ |
| 阶段四 | 开源与 CI/CD 恢复：LICENSE、README 终稿、workflow 复位、新标签重发 Release | 本地门禁全绿 → CI 恢复后全绿；新 Release 取代已撤回的 v0.0.1 |

---

## 9. 风险

| 风险                        | 缓解                                                     |
|-----------------------------|----------------------------------------------------------|
| 鹊桥升级改 `sendEvent` 签名 | `EventSink` 抽象；签名漂移只改 `QueQiaoSink` 一个文件，业务零改动 |
| tool 包版本/包名漂移        | 锁 jar 后反编译核对；探针未就绪则完全静默（零流量）      |
| 渲染线程开销                | 后台线程池 + 每 tick 渲染区块数硬上限                    |
| 老插件收到未知事件          | 见第 6 节三层保护                                        |
| 读 region 与服务器写盘冲突  | 主线程绝不读盘（采样只从内存取）；回填仅 mapstore 自有后台线程**只读** mca、mtime 增量跳过、异常吞；绝不写 `region/`（store 自有目录，见 §5.5） |
