# mapstore 域 — 本地二维地图库

> 域范围：`mapstore` 包七个纯 JDK 类（RegionFileReader / NbtReader / ChunkTopScanner / BlockColors / MapStore / BackfillEngine / RegionStreamer——后者行为由 REQ-MS-007 约束）：mca 直读、NBT 解码、块顶面扫描、方块取色、`.qms` 区域库与增量回填。权威来源：docs/DESIGN_MAPSTORE.md §2（接口契约）、AGENT.md §8（common 零 MC/第三方依赖、java.util.logging）。本域代码只在后台线程被调用，主线程语义无关。

### REQ-MS-001 mca 区域文件读取与容错

**SHALL**：`RegionFileReader.listChunks(Path)` 与 `readChunkNbt(Path, ChunkLocation)` 必须直接解析 Anvil region 文件：头部 = 位置表 1024×(u32 扇区偏移, u32 扇区数)（8192B）+ 修改时间表 1024×u32（4096B），共 **12288B**（蓝图 §2.1 括注的 8192B 指位置表，实际头部按 Anvil 实况 12288B 解析）；位置索引 `index=(x&31)|((z&31)<<5)`，偏移或大小为 0 = 该列不存在；块记录在 `扇区偏移×4096`：u32 长度（含压缩字节）+ u8 压缩类型（1=gzip、2=zlib、3=不压缩，其余抛 IOException）+ 压缩负载。ChunkLocation 携带绝对块坐标（由文件名 `r.<rx>.<rz>.mca` 折算）。`listChunks` 一切 IO 异常吞掉记 FINE 返回部分/空列表；`readChunkNbt` 声明 `throws IOException` 由调用方吞掉。

**场景**：
- 给定 @TempDir 合成 r.0.0.mca（2 个 zlib 压缩块 + 若干偏移 0 槽位 + 1 个非法压缩类型块），那么 listChunks 恰返回 2 个存在块且坐标折算正确；readChunkNbt 对合法块解压成功、对非法压缩类型抛 IOException。
- 给定头部截断/文件不存在的 region，那么 listChunks 返回空列表且不抛异常（记 FINE）。

**验证**：`RegionFileReaderTest#listAndReadSyntheticMcaRoundtrip`、`RegionFileReaderTest#truncatedHeaderYieldsEmptyList`、`RegionFileReaderTest#readChunkBeyondEofThrows`

### REQ-MS-002 NBT 双格式解码（big-endian，TAG 0–12）

**SHALL**：`NbtReader.readCompound(InputStream)` 必须按大端解析 NBT，根必须是 TAG_Compound；返回树映射：Compound→`Map<String,Object>`、List→`List<Object>`（元素类型取列表头声明类型）、ByteArray→`byte[]`、IntArray→`int[]`、LongArray→`long[]`、String→`String`、数值→对应包装类型。支持 TAG 0–12，**含 TAG_LongArray(12)**（1.16+ BlockStates 必需；蓝图 §2.2 写 0-11 为笔误）。嵌套深度 ≤512、数组长度 ≤2^24，超限或流损坏抛 IOException 由调用方吞掉。

**场景**：
- 给定手写字节夹具（嵌套 Compound + 各标量 + byte[]/int[]/long[] + List<Compound> + 根名空串），那么 readCompound 返回的树逐字段等值。
- 给定根类型非 Compound 或数据中途截断，那么抛 IOException（不外溢到调用链之外，调用方吞记 FINE）。

**验证**：`NbtReaderTest#readAllTagTypesRoundtrip`、`NbtReaderTest#malformedRootThrows`、`NbtReaderTest#byteArrayTag12DecodesForBlockStates`

### REQ-MS-003 ChunkTopScanner 位打包（现代对齐 + 旧式补齐兜底）

**SHALL**：`ChunkTopScanner.scanTopStates(Map, int minBuildHeight)` 必须输出长度 256 的行主序数组（`index=lz*16+lx`），每列为该列最高非空气方块状态名；空气 = {minecraft:air, minecraft:cave_air, minecraft:void_air}，水柱顶即水，无地面列 = null；`Y<minBuildHeight` 的方块跳过。section 兼容三种编码：1.20.1 的 `BlockStatePalette`+`BlockStates`；1.20.2 内联 `block_states{palette,data}`；1.20.2 根 `palettes` + section `palette_ref` 引用。bits = `max(4, ceil(log2(paletteSize)))`；现代对齐打包取值 `(longs[total>>6]>>>bitOffset)|(longs[(total>>6)+1]<<(64-bitOffset))`（total=索引×bits）；若 `entryCount*bits > longs.length*64` 判定旧式补齐打包兜底（两者读取公式一致，差异在写入端补零），越界索引按空气处理。

**场景**：
- 给定合成 1.20.1 夹具（section Y=0、palette=[air,stone]、4-bit 对齐打包：下层 stone 上层 air），那么 256 列全为 minecraft:stone；上方再叠一层 grass 后顶面变 grass_block。
- 给定 1.20.2 夹具（根 palettes + block_states.palette_ref + data）与旧式补齐打包夹具（长数组多于对齐所需），那么两种打包解出同一顶面。
- 给定整列空气与 Y<-64 的石块（minBuildHeight=-64），那么该列 = null。

**验证**：`ChunkTopScannerTest#modernAlignedSectionsTopState`、`ChunkTopScannerTest#modern1202PalettesFormat`、`ChunkTopScannerTest#paddedPackingFallbackAndAirNull`、`ChunkTopScannerTest#minBuildHeightSkip`

### REQ-MS-004 BlockColors 内置表与灰兜底

**SHALL**：`BlockColors.colorOf(String)` 必须返回 0xRRGGBB；内置表 ≥**120** 条「minecraft:<方块名> → RGB」（实际内置 672 条，色值为 vanilla 1.20.1 MapColor.col 实值：javap 直读 forge-1.20.1-47.4.28_mapped_official 字节码，方块归属取 Blocks 注册字节码首色，染色家族按 DyeColor.mapColor，个别未采到字节码的按既有 MapColor 常量近似并在 Javadoc 标注）；不带命名空间按 minecraft: 补齐；带 `[Properties]` 后缀先剥离；未收录（含 mod 方块）→ **0x808080**。

**场景**：
- 给定 `minecraft:grass_block`，那么返回 0x7fb238（1.20.1 MapColor.GRASS 实值，非蓝图片段 0x79c05a）；`minecraft:stone` → 0x707070。
- 给定 `quchao:unknown_block` 与 null，那么均返回 0x808080；内置表条目数 ≥120。

**验证**：`BlockColorsTest#knownVanillaValuesAndFallback`

### REQ-MS-005 qms 文件格式与 xor32

**SHALL**：`MapStore` 落盘 `<worldSaveRoot>/queqiao_map_web/store/<dimFolder>/r.<rx>.<rz>.qms`；dimFolder：overworld（含 minecraft:overworld）→ `overworld`，其余 → `命名空间_路径`（裸名补 minecraft:）。文件大端：magic `QMS1` | u16 side=512 | u8 formatVer=1 | u32 xor32 | u32 lastSentXor | u64 mtimeMillis | u32 payloadLen | zlib(RGB24 512×512×3=786432B)。**xor32 = 对解压后负载按 4 字节大端分组 XOR**；每次 putRegion/putChunk 重算 xor32、更新 mtime、lastSentXor 归 0（新内容尚未发送）；写入经临时文件 + ATOMIC_MOVE；markSent 原地改写 u32 lastSentXor。`listDimensions()` 逆映射目录名回注册键。读写按文件粒度进程内互斥；一切 IO 失败吞掉记 FINE（读返回 null，写静默）。

**场景**：
- 给定 786432B 全 RGB 调 putRegion，那么 readRegion 逐字节还原；header 的 xor32 == 对负载独立按 4B BE 分组 XOR 重算值；改 1 字节重放后 xor32 变化。
- 给定 putChunk 写 768B 到 (cx,cz)=(-1,17)，那么落盘 r.-1.0.qms、对应 16×16 像素 patch 正确、其余保持 0。
- 给定 markSent(xor) 后重读 header，那么 lastSentXor==xor 且 xor32/mtime 不变；listRegions/listDimensions 枚举与注册键逆映射正确。

**验证**：`MapStoreTest#putRegionReadRoundtripAndXorRecompute`、`MapStoreTest#putChunkPatchAndDimMapping`、`MapStoreTest#markSentRewritesLastSentXorOnly`

### REQ-MS-006 回填：增量跳过与令牌桶节流

**SHALL**：`BackfillEngine` 每会话一个后台守护线程（start/stop 幂等，stop = 置位 + interrupt + ≤2s join）；每轮对 `MapStore.listDimensions()` 的维度扫 mca 目录（overworld → `<worldSaveRoot>/region`，其余 → `<worldSaveRoot>/dimension/<ns>_<path>/region`）中 `r.*.mca`；store 已有该区域且 `!isRegionFileNewer` → 整文件跳过（增量）。逐块 listChunks → readChunkNbt → NbtReader → scanTopStates → BlockColors 逐列 1:1 上色拼 512×512 RGB24（缺块/无地面列保持 0）一次 putRegion。节流走构造器令牌桶（`int maxChunksPerSec`，容量=速率，初始满桶，≤0 兜底为 1；不 import MapExtConfig，clamp 归 config 层）。一切异常吞掉记 FINE。

**场景**：
- 给定含 2 块的合成 r.0.0.mca，当 runScanCycle，那么 store 出现 r.0.0.qms 且 (8,8) 列颜色 = 对应方块色；把 mca 的 lastModified 拨旧后改内容再 runScanCycle，那么区域未被重写（mtime 不变 = 跳过生效）；把 mca 时间拨新，那么被重写。
- 给定 maxChunksPerSec=1、region 含 2 块，当一次同步扫描周期，那么耗时 ≥0.8s（第二块等待令牌）。

**验证**：`BackfillEngineTest#backfillPaintsRegionAndSkipsUnchanged`、`BackfillEngineTest#throttleSlowsPerChunk`、`BackfillEngineTest#startStopIsSafe`

### REQ-MS-007 区域流 xor 跳过 + 节流

**SHALL**：区域流（RegionStreamer）巡检 `MapStore.listRegions(dim)`：`xor32 == lastSentXor` 的区域必须跳过不发送且 `lastSentXor` 保持原值；变化区域发送成功后调用 `MapStore.markSent(dim,rx,rz,xor32)`（载荷 zlib-pal8、附压缩字节 sha1，且调色板事件先于区域事件）。节流语义按蓝图 §3.2：令牌桶按**压缩字节数**限速，配置键 `region_stream_bytes_per_sec`（默认 65536）；单事件内区域个数 ≤ `region_stream_max_regions_per_event`（默认 4）；发完后 30s 内复查一轮（worker 周期）。mapstore 侧保证 xor32 写端必重算、markSent 只动 lastSentXor 不动 xor32/mtime（REQ-MS-005）。

**场景**：
- 给定 store 中 3 个区域、其中 2 个已发送（xor32==lastSentXor），当一轮巡检，那么仅 1 个变化区域被发送且其 lastSentXor 被更新；再巡检一轮，那么零发送；随后改动任一区域使 xor 变化，那么该区域被重发。
- 给定 region_stream_max_regions_per_event=1，当巡检存在可发送区域，那么单事件内只含 1 个区域，其余留待后续轮次。
- 给定 region_stream_bytes_per_sec=4096，当发送两个区域事件，那么总耗时 ≥ 压缩字节总量 ÷ 4096 B/s（留计时余量），令牌桶不得超发。

**验证**：`RegionStreamerTest#skipUnchangedXorRegionsAndThrottle`

### REQ-MS-008 停服顺序：streamer → backfill → drain（adapter.shutdown 已在入口先行）

**SHALL**：停服收尾：加载器入口层（ServerStopping 钩子）先调 `adapter.shutdown()`（摘监听 + 后台池排空，REQ-LIFE-003），随后 `ModLifecycle.stop()` 内部顺序必须为：① 停 RegionStreamer（停止广播，interrupt + ≤5s join）→ ② `BackfillEngine.stop()`（停后台解析，置位 + interrupt + ≤2s join）→ ③ `TileQueue.drain()`（主线程同步清队列最后发送窗口）。drain 中任一 send 失败立即停止排空、由发送路径吞掉记 FINE，不得外抛、不得阻断停服。BackfillEngine 停止不得吞掉 interrupt 标志不回置（AGENT.md 线程纪律）。

**场景**：
- 给定一次停服序列，那么 adapter.shutdown 先于 ModLifecycle.stop()，stop() 内部调用顺序恰为 streamer→backfill→drain（测试用桩记录调用序列）。
- 给定 backfill 线程正在 sleep 周期中被 stop，那么 ≤2s 内退出且不再有 putRegion 落盘。

**验证**：`ModLifecycleTest#stopOrderStreamerBackfillDrain`

### REQ-MS-009 区域重放：lastSentXor 全量复位（region_replay_on_start）

**SHALL**：`MapStore.resetLastSentXor()` 必须遍历 `listDimensions()` 的每个维度、`listRegions(dim)` 的每个区域，经 `markSent(dim, rx, rz, 0)` 原地把各 `.qms` 文件头的 u32 `lastSentXor` 写 0（只动该字段：xor32/mtimeMillis/压缩负载逐字节不变），返回复位的区域数量；幂等可重复调用，一切 IO 异常沿用 markSent 吞 FINE 语义不外抛。lifecycle（`ModLifecycle.startMapStoreEngines`，lead 接线）当配置键 `region_replay_on_start=true`（REQ-CFG-005，默认 false）时，必须在创建 RegionStreamer 之前每会话调用一次，并以 info 日志记录复位数量；此后 REQ-MS-007 去重门视全部区域为未发送并按令牌桶限速重放。边界：`xor32==0` 的区域复位后仍被去重门跳过（与 REQ-MS-005 刚写入 lastSentXor=0 的语义一致，全 0 区域本就无内容）。

**场景**：
- 给定两维度若干区域全部 putRegion 并逐个 markSent，当 resetLastSentXor，那么返回值==区域总数；重读各头 lastSentXor==0 且 xor32/mtime 不变、readRegion 逐字节不变；再调一次数量与头状态不变（幂等）。
- 给定 3 个区域已全部 markSent（xor≠0），当 resetLastSentXor 后跑一轮 streamOnce，那么 3 个区域全部被重发且发送后 listRegions 三者 xor==lastSentXor；再跑一轮零发送。

**验证**：`MapStoreTest#resetLastSentXorClearsMarkersOnly`、`RegionStreamerTest#replayAfterResetResendsAllRegions`、`ModLifecycleTest#replayOnStartResetsBeforeStreamer`
