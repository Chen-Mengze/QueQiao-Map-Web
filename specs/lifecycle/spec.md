# lifecycle 域 — 装配与生命周期

> 域范围：`ModLifecycle` 装配、start/stop、静默保证与异常隔离。权威来源：AGENT.md §4（发送铁律 1/2/3/7）、docs/INTEGRATION.md §2（连接时序）。

### REQ-LIFE-001 enabled=false 除自检日志外零行为零流量

**SHALL**：`enabled: false`（默认）时，`ModLifecycle.start` 之后唯一可观测效果是一条自检日志；不装配采样器/瓦片队列/sink，不注册任何 tick 与区块钩子，零事件流量。

**场景**：
- 给定默认配置（enabled=false），当服务端启动完成并调用 start，那么日志输出「已安装但未启用」一条，其后任意多 tick 经假适配器观察为零事件、零渲染、零入队。
- 给定 enabled=false，当区块加载事件发生，那么没有任何瓦片入队（onChunkDirty 钩子根本未注册）。

**验证**：`ModLifecycleSmokeTest#disabledIsSilent`（冒烟，假 PlatformAdapter + 计数 sink）

### REQ-LIFE-002 鹊桥未 init 完全静默，isReady 探针 2s 节流

**SHALL**：鹊桥 `GlobalContext` 未 init（探针 `getConfig()==null`）时整个数据面完全静默：零采样、零渲染、零发送；`isReady()` 的探针结果缓存 2000ms（2s 节流），节流窗内不再触碰鹊桥类；探针抛异常视为未就绪且不向外传播。

**场景**：
- 给定 enabled=true 且鹊桥未就绪，当连续 tick N 次，那么零事件发送，且实际探针调用 ≤ 每 2s 一次（主线程与后台 flush 线程共享该节流，volatile 可见）。
- 给定鹊桥在运行中途完成 init，当下一次探针观察到 `getConfig()!=null`，那么事件流开始并恰好输出一条「鹊桥已就绪」日志。

**验证**：`ModLifecycleTest#silentUntilBridgeReady`、`QueQiaoSinkTest#probeThrottled2s`

### REQ-LIFE-003 forge 入口停服顺序：先 adapter 关闭再 TileQueue.drain；common 层契约=drain 中任一 send 失败立即停止排空、不抛异常

**SHALL**：停服顺序由各加载器入口保证（现实现 forge/QueqiaoMapWeb：先 `adapter.shutdown()` 摘监听 + 后台池排空，再 `ModLifecycle.stop()`，其内部调 `TileQueue.drain()`）——drain 期间主线程是唯一发送者。**common 层契约**：drain 中任一 send 失败立即停止排空，剩余条目静默丢弃，不向服务端抛出任何异常、绝不阻断停服。

**场景**：
- 给定队列非空且通道可用，当服务端停止，那么队列按 ≤batchSize 分批在主线程同步排空发送直至清空。
- 给定 drain 期间某次 send 返回 false，那么排空立即 break（不重采样空转），剩余条目丢弃，stop() 正常返回。
- 给定入口按 adapter.shutdown → ModLifecycle.stop 顺序调用，那么 drain 开始时不再有 runAsync 在途 flush。

**验证**：`ModLifecycleTest#stopDrainsAfterAdapterShutdown`、`TileQueueDrainTest#drainStopsOnSendFailure`

### REQ-LIFE-004 send() 返回 false 静默丢弃，所有钩子外层 catch Throwable

**SHALL**：`send()` 返回 false 即静默丢弃或退回队列（瓦片批按坐标退回，下 tick 重采样），绝不抛异常打断服务端；所有平台钩子外层（onServerTick、stop 的 drain、后台 flush）一律 catch Throwable 并按 FINE 级记日志。

**场景**：
- 给定 sink.isReady()=false，当瓦片 flush 发生，那么调色板标记不置位、整批退回队列、无异常外抛。
- 给定 onServerTick 内部任一环节抛异常（含 Error），当服务端继续 tick，那么异常被吞（FINE 日志），主循环无恙。

**验证**：`ModLifecycleTest#tickExceptionsSwallowed`、`QueQiaoSinkTest#sendFalseSwallowsThrowable`

### REQ-LIFE-005 地图库引擎：就绪+enabled 双门启动；停服顺序 streamer→backfill→drain

**SHALL**：`start()` 装配时仅当 `adapter.worldSaveRoot()` 非 null 且（backfill_enabled 或 region_stream_enabled 任一开启）才创建 MapStore，否则整链路为 null（不建任何引擎线程）。引擎启动发生在鹊桥就绪后的首个 tick：BackfillEngine 与 RegionStreamer 各启动一次（一次性门），各受自身 enabled 开关约束；启动异常吞 FINE 降级。维度清单变化（map_dimensions 发送处）须唤醒 streamer.requestRecheck()。`stop()` 顺序（入口已按 REQ-LIFE-003 先调 adapter.shutdown）：regionStreamer.stop() → backfillEngine.stop() → tileQueue.drain()——保证任何 map_region 不晚于 map_tile，且 stop() 返回时两个引擎线程已退出。

**场景**：
- 给定鹊桥未就绪 tick N 次，那么引擎字段恒 null、零线程、零 map_region 流量。
- 给定就绪且任一开关开，那么 store 已建且开启的引擎各起一次；两开关全关或 worldSaveRoot=null 时 store 为 null、引擎不启。
- 给定 streamer 节流发送未完时 stop，那么事件流中首个 map_tile 之后无 map_region；stop() 返回后两引擎均不在运行。

**验证**：`ModLifecycleSmokeTest#enginesGatedOnReadyAndFlags`、`ModLifecycleTest#stopOrderStreamerBackfillDrain`（后者 @Tag 亦承载 REQ-MS-008，见 specs/mapstore/spec.md）
