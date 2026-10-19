# DESIGN_MAPSTORE.md — 地图数据路径重设计（mca → 本地二维地图库 → 分区流式同步）

> 状态：已实现（2026-10-20）。语义已并入 ARCHITECTURE.md §5.5 / specs（mapstore 域等）/ docs/INTEGRATION.md §3.5；本文件保留作设计记录。落地偏差：NbtReader 支持 TAG_LongArray(12)；mca 头按 12288B 读取，首块自扇区 3。
> 本文件是三名实现者的唯一接口契约源：签名与语义以本文为准，不得擅改。

## 0. 需求（用户 2026-10-20 两条消息合并）

1. 本 mod 定位不变：**足够轻量的鹊桥附属扩展**。玩家实时位置、鹊桥自身命令能力照旧（player_move 不动，命令能力属鹊桥本体，本 mod 零干涉）。
2. 地图数据必须**单独一条路径**：直接同步存档的 mca 区域文件，转换为**二维俯视图数据**（类 Xaero），**先落本地存储**，再对外发送。
3. 长期运行的服务器安装本 mod 后：后端能**一口气输出全部已生成区域**（全量回填）。
4. 新开服务器：初始近乎空白，随运行**从初始逐步扩展**。
5. 全程保持轻量、低流量；同步采用 Xaero 式**异或（XOR）校验**做变更检测与断点续传。

## 1. 总架构（新增件全部在 common，零 MC 依赖；主线程零新增开销）

```
mca(3D, 32×32区块/区域) ──[mapstore 解码]──> 512×512 px RGB24 俯视图
        │                                        │
        └─(仅回填扫描,后台线程)                   └─> MapStore 本地库(.qms, 含 xor)
                                                      │
 实时: ChunkEvent/方块事件 → TileQueue(不变) ──采样RGB──> 写穿 Store + map_tile 增量(现有)
                                                      │
 全量/续传: RegionStreamer → 新事件 map_region(区域粒度, 节流, xor 差异跳过) → 鹊桥 → 外部消费端
```

三条流：
- **实时增量流**（现有 map_tile 链路，不动语义）：脏区块 → 主线程采样 RGB24 → 现打包发送 **+ 同步写穿 MapStore**（store 保持最新，xor 随之演进）。
- **回填流**（新）：后台线程读 mca → 解码顶层方块 → RGB → 写入 MapStore（只补 store 缺的区域 / mca 更新的部分）→ 不产 map_tile（避免与区域流重复）。
- **区域流**（新）：鹊桥就绪后把 MapStore 内容按区域打包成 map_region 事件限速流式输出；xor 与上次已发一致的区域跳过（重启/重连近零流量）。

## 2. 新包 net.yumezawa.queqiao_map_web.mapstore（纯 JDK，禁止 net.minecraft.* / GlobalContext）

### 2.1 RegionFileReader（mca 读取）
- static List<ChunkLocation> listChunks(Path regionFile)；static byte[] readChunkNbt(Path regionFile, ChunkLocation loc)（可抛 IOException，调用方吞）。
- 格式：头部 8192B（1024×(u32 offset 扇区, u32 size) + 1024×u32 mtime）；坐标→表下标 (x&31)|(z&31)<<5；offset=0 → 不存在；压缩类型字节 1=gzip、2=zlib、3=none，其它(4 lz4 等)记 warning 跳过。
- 单 chunk 解析失败只跳过该 chunk（服务器可能正在写该文件；铁律：一切异常不外抛）。

### 2.2 NbtReader（极简 NBT，纯 JDK）
- static Map<String,Object> readCompound(InputStream in)：tags 0-11（big-endian），List 元素类型取首元素 type（异质按首类型读、损坏即抛由调用方吞）。返回树用 Map<String,Object> / List<Object> / byte[] int[] long[] String Number。
- 兼容 1.20.1（根 compound 直接含 sections）与更高版本（root.sections 同层；1.20.2+ 顶层 palettes+section refs 也要支持：有 BlockStatePalette 走旧式，有 palettes 走新式）。

### 2.3 ChunkTopScanner（3D→二维）
- static String[] scanTopStates(Map<String,Object> chunkNbt, int minBuildHeight) → 长度 256（行优先 16×16，index=lz*16+lx），值=顶层非空气方块状态名字符串（palette entry 的 Name；含 Properties 不影响取色）；无地面列 = null。
- BlockStates 打包：bits = max(4, ceil(log2(paletteSize)))；现代对齐打包（跨两 long：(longs[total>>6] >>> bitOffset) | (longs[(total>>6)+1] << (64-bitOffset))，total=索引*bits）；若 entryCount*bits > longs.length*64 判定为旧式补齐打包，逐条从 i*bits/64 起按位取（老 .old 之外的历史格式兜底）。
- 空气判定：Name ∈ {minecraft:air, minecraft:cave_air, minecraft:void_air} 视为空继续向下找；其余（含水）即顶层——与 OCEAN_FLOOR 语义一致。
- sections 的 Y 值域外（<minBuildHeight）跳过。

### 2.4 BlockColors（名→RGB 单一色源）
- static int colorOf(String stateName) → 0xRRGGBB；内置表取自 vanilla MapColor 实值（至少覆盖：草/泥土/石/圆石/沙/砾石/水/冰/雪/原木×系列/树叶×系列/花岗岩闪长岩安山岩全系/深色石板系列/陶瓦全系/混凝土全系/玻璃/基岩/下界岩/石英/黑曜石/末地石/紫珀/菌类/红石相关/矿石系列/泥土系（含草道、耕地、泥土径）/雪块/海晶石等 ≥120 条）；未收录 → 0x808080 灰。
- 决策：回填色源 = 本表；实时链路仍用 MC getMapColor（1.20.1）。同块可能微差 → 至多引发一次区域 xor 变更重发，消费端按 sha1/xor 幂等，无正确性问题。1.21+ 分支（MapColor 已删）届时实时也切本表，天然收敛。

### 2.5 MapStore（本地二维库）
- 位置：worldRoot + /queqiao_map_web/store/ + dimFolder + /r.rx.rz.qms；dimFolder：overworld=overworld，其余=ns_path（同 MC dimension 目录约定，但本 mod 自有子目录，绝不写入 region/）。worldRoot 由 PlatformAdapter.worldSaveRoot() 给出（新契约方法，见 §4）。
- 文件（统一大端）：magic QMS1 | u16 side=512 | u8 formatVer=1 | u32 xor32 | u32 lastSentXor | u64 mtimeMillis | u32 payloadLen | zlib(RGB24 512×512×3=786432B 行优先)。
- xor32 = 对 payload 原始字节按 4 字节大端组做 XOR（Xaero 式异或校验）。
- API（全部线程安全：内部按维度目录 + 文件粒度锁）：
  - byte[] readRegion(String dim, int rx, int rz) → RGB24 或 null；Header header(String dim,int rx,int rz)。
  - 写：void putChunk(String dim,int cx,int cz,byte[] rgb24)（写穿 16×16 px 进所属区域文件：无则新建全 0 网格；重算 xor32；更新 mtime）；void putRegion(String dim,int rx,int rz,byte[] rgb24)（回填整区域写）。
  - List<RegionInfo> listRegions(String dim)（rx,rz,xor32,lastSentXor,mtime）；List<String> listDimensions()（扫 store 目录，registry key 逆变换：overworld→minecraft:overworld，ns_path→ns:path）。
  - void markSent(String dim,int rx,int rz,int xor32)（区域流发送成功后）。
  - boolean isRegionFileNewer(String dim,int rx,int rz, Path mcaFile) → mtime 比对（回填增量判定：store 已有且 mca 不更新则跳过）。
- 全 0（未采）像素：RGB 0=未设置，消费端 palette[0]=透明，同现有约定。

### 2.6 BackfillEngine（回填扫描，独立守护线程）
- 每会话一次（鹊桥就绪后开始；start 于 ModLifecycle，stop 于 stop()）：对 listDimensions() 每个维度：mca 目录 = worldRoot + /region（overworld）或 + /dimension/ns_path/region；扫 r.*.mca → 每文件 listChunks → scanTopStates → BlockColors → 整区域 RGB 一次性 putRegion（含该区域内未生成 chunk 的全 0 列）。
- 跳过条件：store 已有该区域且 !isRegionFileNewer。节流：config.backfillMaxChunksPerSec（默认 512，令牌桶按 chunk 计）。
- 铁律：全程在自有线程；只读 mca 文件；不 touch 内存世界对象；单文件/chunk 异常吞（FINE）；enabled=false 或 store 关闭则线程不创建。

## 3. 协议新增（只加不改，REQ-PROTO-004 框架内）

### 3.1 MapRegionEvent（新 sub_type，map_region）
- super("MapRegionEvent","notice","map_region")；字段：String dimension（registry key）、List<RegionPayloadModel> regions。
- RegionPayloadModel：int rx; int rz; String format; int xor; String sha1; String b64;——rx/rz = MC 区域坐标（区域覆盖块 [rx*32,(rx+1)*32)）；像素载荷 = palette 量化后的 pal8 512×512 → zlib（wire 与 zlib-pal8 同款语义；format:"zlib-pal8" 复用 token；sha1=压缩字节 hex；xor=§2.5 的 u32）。
- 顺序铁律不变：palette 必须先于任何 map_region / map_tile。paletteSent 标记从 TileQueue 私有抽出：TileQueue 增 public boolean publishPalette(EventSink sink)（把现锁内发 MapPaletteEvent+freeze 的段抽出复用）与 boolean palettePublished()；flush 内部改调它。

### 3.2 RegionStreamer（区域流，后台自有守护线程）
- 门：sink.isReady() 且 tileQueue.palettePublished()（未发则先调 tileQueue.publishPalette(sink)）。循环：listRegions → 跳过 xor32==lastSentXor → 打包（RGB→pal8 量化用 Palette.indexOf + zlib + sha1，产出 RegionPayloadModel；每事件 ≤ regionStreamMaxRegionsPerEvent 默认 4）→ send(MapRegionEvent) → 成功则 markSent。
- 节流：config.regionStreamBytesPerSec 默认 65536（按压缩字节计，令牌桶）。全部发完 → 空闲等待（30s 复查新区域，来自回填/写穿产生的 xor 变化）。
- 停服：stop() 顺序 streamer 停 → backfill 停 →（入口已 adapter.shutdown）→ tileQueue.drain()。

### 3.3 区域流像素量化注意
- RegionStreamer 在后台线程量化：Palette 本身 synchronized，indexOf 冻结后就近——与 TileQueue flush 并发安全；不得在量化前读 xor（量化后字节与 store xor 无关：xor 是 store 原始 RGB 校验，sha1 是 wire 压缩字节校验，两个都要发）。

## 4. PlatformAdapter 契约新增（三个适配器都实现）

- Path worldSaveRoot()——Forge 1.20.1：server.getWorldPath(LevelResource.ROOT)（用 javap 核对 forge-1.20.1-47.4.x 实签后落地）；Fabric：适配器持 server 引用，同用 MinecraftServer#getWorldPath（yarn 映射名 worldPath，落地前 javap/源码核对）；NeoForge 同 Forge。返回 null = 不支持（store 关闭、回填关闭、仅实时流）。
- 维度→mca 目录由 common 依 §2.6 约定拼路径，适配器不再另供 regionDir。

## 5. 配置新增（MapExtConfig；REQ-CFG 追加新键与钳制）

```yaml
backfill_enabled: true            # mca→store 回填开关
backfill_max_chunks_per_sec: 512  # 钳制 ≥16
region_stream_enabled: true       # map_region 流开关
region_stream_bytes_per_sec: 65536# 钳制 ≥4096
region_stream_max_regions_per_event: 4 # 钳制 ≥1 ≤16
```
默认 yml 模板、解析 switch、钳制、测试同步。

## 6. 消费端语义（写进 INTEGRATION 新节）

- 全量：连上后 mod 自动按区域流式补发全部已生成区域（map_region），无需请求（鹊桥当前无反向请求通道，「用时发送」由 xor 续传近似：已一致的跳过）。
- 幂等：区域按 (server,dimension,rx,rz) + xor/sha1 去重；区域覆盖=整块替换该 32×32 区块范围；map_tile(size1/8) 增量语义不变，二者叠加幂等（区域流=整块替换，tile=局部覆盖）。
- 预算参考：单区域 512×512 pal8 raw 256KB → 自然地形 zlib 后 ≈15–60KB；默认 64KB/s → 2000 区域 ≈10–20 分钟一次性；此后仅变化区域。

## 7. 规则与文档变更

- AGENT.md §4.5 改写：主线程采样仍内存唯一（禁读 region 文件）；仅 mapstore 回填线程允许后台只读 mca；任何情况下不得在主线程读盘。
- ARCHITECTURE.md：新增「地图库与区域流」节（并入本设计语义）；§5 带宽补 map_region 表。
- specs：新域 mapstore（REQ-MS-001 mca读取与容错、002 NBT双格式解码、003 TopScanner打包位序、004 BlockColors兜底、005 qms 格式+xor、006 回填跳过/节流、007 区域流 xor 跳过+节流、008 停服顺序 streamer→backfill→adapter→drain）；protocol 追加 REQ-PROTO-005（map_region 契约+基线快照增键 rx/rz/xor/regions/dimension）；config 追加 REQ-CFG-004（新键解析钳制）；lifecycle 追加 REQ-LIFE-005（回填/区域流受 enabled+鹊桥就绪双门）；tile 追加 REQ-TILE-006（写穿 store，store=null 时跳过）。旧 REQ 一律不动，新语义开新条。specs/README.md 域清单登记 mapstore 域。
- README 特性表加一行 map_region。

## 8. 验证手段（Gradle 缓存缺 queqiao-tool 凭据，勿碰 Gradle 网络墙）

- javac 快检（AGENT §3）：jar 实物流 C:\Users\Yumezawa\AppData\Local\Temp\qqmap-web-spec\libs\queqiao-tool-0.6.8.jar：
  javac --release 17 -cp <jar> -d out <common 全部 .java>（路径含空格用 argfile、正斜杠加引号）。
- 单测：JUnit5 同 temp libs 若带 junit 则跑；否则保证 javac 干净 + 提交后 lead 汇总跑 :common:test（需凭据，lead 处理）。
- 三方分工（写域互斥）：T1 = mapstore 包全部新文件+其测试+specs/mapstore；T2 = TileQueue/ModLifecycle/MapExtConfig/PlatformAdapter/三适配器/event/model/RegionStreamer+对应测试与 spec 追加；T3 = 全部文档（AGENT/ARCHITECTURE/README/INTEGRATION/DESIGN 落地注记）。
