# tile 域 — 瓦片队列与编码

> 域范围：`TileQueue` 脏区块去重、双触发刷写、队列封顶、线程纪律，及 `TopBlockRenderer` 的 zlib-pal8/sha1/base64 打包（map_tile 数据面）。权威来源：AGENT.md §4.6/§4.7（合并、线程纪律、drain）、ARCHITECTURE.md §5（带宽）、docs/INTEGRATION.md §3.4/§4（瓦片契约）。

### REQ-TILE-001 8×8 区块合并瓦片（size=8）

**SHALL**：刷写批次按 `dimension + (cx>>3, cz>>3)` 对齐区域分组；同一 8×8 区域内同批脏区块数 ≥ REGION_MERGE_MIN（**4**，`TileQueue` 私有常量）时打包为一块 **size=8** 瓦片（锚点 = 区域左上角区块坐标，取 8 对齐值 `((cx>>3)*8, (cz>>3)*8)`），否则逐块 size=1。缺失区块的像素填下标 0。

**场景**：
- 给定同一 8×8 区域 6 个脏区块进入同一批，那么产出 1 块 size=8 瓦片（128×128 像素索引，58 个缺失像素为 0）。
- 给定同一区域仅 3 个脏区块，那么产出 3 块 size=1 瓦片。

**验证**：`TileQueueTest#regionMerge8WhenAtLeast4Dirty`

### REQ-TILE-002 zlib-pal8 编码 + sha1 校验可还原

**SHALL**：瓦片编码必须满足：行优先 `side×side` 调色板下标字节数组（side=size×16，index=pz×side+px，左上原点）→ zlib（Deflater）→ payload {format:"**zlib-pal8**", sha1: 压缩字节的小写 hex SHA-1, b64: 同一压缩字节的 Base64}；按 docs/INTEGRATION.md §4.2 三步（Base64→inflate→下标查色）必须还原出原像素网格，且 sha1 与 b64 指向同一字节序列。

**场景**：
- 给定已知 RGB 样本调 pack，那么 Base64 解码后 inflate 得 side² 字节，逐像素下标可回查 palette；SHA-1(decompressed 压缩字节) hex == payload.sha1。
- 给定 format 常量被引用处，那么值恒为字面量 `zlib-pal8`（编码变化必须用新 format 字符串，见 REQ-PROTO-004）。

**验证**：`TopBlockRendererTest#zlibPal8Sha1Roundtrip`

### REQ-TILE-003 瓦片按位置去重（pending putIfAbsent），sha1 为内容寻址指纹；重发抑制发生在消费端

**SHALL**：TileQueue pending 以 `dimension@cx,cz` 键做**位置去重**（putIfAbsent）：同一区块在队列内只计一份，退回重入同样受该键与 pending_cap 约束。sha1 是压缩字节的内容指纹（内容寻址），服务端不维护内容级 sha1 缓存、不存在服务端重发抑制逻辑——脏区块重采后内容不变时会原样重发同 sha1。**重发抑制发生在消费端**：同一 (dimension,cx,cz,size) 收到相同 sha1，消费端必须跳过重绘（docs/INTEGRATION.md §4.4）。

**场景**：
- 给定同一区块坐标连续入队两次，当 onTick 刷写，那么该坐标只被渲染/打包一次。
- 给定区块被标脏但顶层方块未变，当重采打包，那么新 payload 的 sha1 与上次一致（消费端据此跳过）。

**验证**：`TileQueueTest#pendingDedupByPositionKey`、`TopBlockRendererTest#sameContentSameSha1`

### REQ-TILE-004 pending_cap 队列封顶、溢出静默丢弃

**SHALL**：pending 条目数达到 pendingCap（默认 **1024**）时，新入队的脏区块必须被静默丢弃（仅第一次触发输出一条「超过 pending_cap」warning），队列长度绝不超过 pendingCap；发送失败退回（requeue）亦逐条受 pendingCap 约束，塞不下的丢弃。

**场景**：
- 给定队列已满（1024 条），当继续入队 100 个脏区块，那么全部被拒、队列仍为 1024、warning 只出现一次。

**验证**：`TileQueueTest#pendingCapOverflowSilentlyDropped`

### REQ-TILE-005 每 tick 主线程渲染硬上限 maxChunkRendersPerTick=64（停服 drain 豁免）

**SHALL**：`onTick` 每 tick 从队列取 min(batchSize, maxChunkRendersPerTick)（默认均为 **64**）个条目做主线程 `renderChunk`，单 tick 渲染数绝不超过 64；主线程只做入队 + renderChunk，量化/压缩/发送只发生在 `adapter.runAsync` 后台线程，后台线程绝不触碰 Level/Chunk 对象（采样已在主线程 capture 成 RGB24）。唯一豁免：`drain()`（仅 ServerStopping 路径，一次性）在主线程同步采样+打包+发送，不设每 tick 上限（单批仍 ≤batchSize，总量受 pendingCap 封顶）。

**场景**：
- 给定 pending=500 且 batchSize=64，当单次 onTick，那么本次 renderChunk 调用 ≤64 次，余量留队列。
- 给定 drain 被调用且通道可用，那么循环分批直至队列清空，不因每-tick-上限中途退出。

**验证**：`TileQueueTest#renderCappedPerTick`、`TileQueueDrainTest#drainExemptFromTickCap`
