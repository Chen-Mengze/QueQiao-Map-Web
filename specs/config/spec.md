# config 域 — queqiao_map_web.yml 极简配置

> 域范围：`config/QueQiao/queqiao_map_web.yml` 的生成、解析与默认值（`MapExtConfig`，纯 JDK，无加载器/MC 依赖）。权威来源：AGENT.md §4.1（默认关闭）、docs/INTEGRATION.md §6（默认值清单）。

### REQ-CFG-001 配置文件缺失生成默认（enabled=false）

**SHALL**：启动时目标文件不存在，必须写出默认文件 `config/QueQiao/queqiao_map_web.yml`（含全部默认键，`enabled: false`）并返回默认配置（本次即不启用）；写出失败（目录只读等）只告警一次，不抛异常，仍按默认值运行。

**场景**：
- 给定空的配置根目录，当 `MapExtConfig.load`，那么 `QueQiao/queqiao_map_web.yml` 被创建，内容含 `enabled: false`，返回对象 enabled=false。
- 给定文件不可写，当 load，那么一条 warning 日志，返回默认对象，无异常外抛。

**验证**：`MapExtConfigTest#missingFileWritesDefaults`

### REQ-CFG-002 queqiao_map_web.yml 极简解析（布尔/数值，注释与未知键忽略）

**SHALL**：解析只认 `key: value` 行；`#` 及其后内容（任意位置）与空行、无冒号行一律忽略；未知键静默忽略。识别键恰为：enabled、batch_size、flush_interval_ms、move_threshold、move_active_interval_ms、move_idle_interval_ms、max_chunk_renders_per_tick、pending_cap、palette_id、dimension_check_interval_ms。

**场景**：
- 给定文件含 `# 注释` 行、`enabled: true # 开`、`unknown_key: x`，当 load，那么 enabled=true 生效、unknown_key 被忽略、其余键保持默认。

**验证**：`MapExtConfigTest#minimalParseIgnoresCommentsAndUnknownKeys`

### REQ-CFG-003 非法值回退默认不抛异常

**SHALL**：数值解析失败（NumberFormatException）必须被捕获、warning 记日志，该键保持默认值；解析成功但低于下限的钳制到下限：batch_size、max_chunk_renders_per_tick ≥1；flush_interval_ms、move_active_interval_ms ≥100；move_idle_interval_ms ≥500；pending_cap ≥16；dimension_check_interval_ms ≥1000。布尔键无法解析时按 `Boolean.parseBoolean` 语义（非 "true" 即 false）。

**场景**：
- 给定 `pending_cap: abc`，当 load，那么 pendingCap 保持默认 1024，无异常。
- 给定 `batch_size: 0`，当 load，那么钳制为 1。

**验证**：`MapExtConfigTest#invalidValueFallsBackToDefault`

### REQ-CFG-004 地图库新键（回填/区域流默认与钳制）

**SHALL**：在 REQ-CFG-002 列举之外，识别键再增：`backfill_enabled`（默认 **true**）、`backfill_max_chunks_per_sec`（默认 **512**，钳制 ≥16）、`region_stream_enabled`（默认 **true**）、`region_stream_bytes_per_sec`（默认 **65536**，钳制 ≥4096）、`region_stream_max_regions_per_event`（默认 **4**，钳制 1..16）。缺文件写出的 DEFAULT_YML 模板必须包含这五个键（注释风格同既有键）；数值解析失败保持默认（REQ-CFG-003 语义同样适用）。注意：两个 enabled 键默认虽为 true，仍受总开关 `enabled`（REQ-CFG-001 / REQ-LIFE-001）门控——总开关不开一切为零。

**场景**：
- 给定 `backfill_max_chunks_per_sec: 1`，那么钳制为 16；给定 `region_stream_max_regions_per_event: 99`，那么钳制为 16。
- 给定 `region_stream_bytes_per_sec: abc`，那么保持默认 65536，无异常。

**验证**：`MapExtConfigTest#newMapStoreKeysParsed`、`MapExtConfigTest#newMapStoreKeysClamped`

### REQ-CFG-005 晚接入自愈两键（palette_republish_interval_ms / region_replay_on_start）

**SHALL**：在 REQ-CFG-002/004 列举之外，识别键再增：`palette_republish_interval_ms`（默认 **10000**，毫秒——已发布调色板的周期重发间隔（REQ-PAL-003 发送节奏源）；`0`=关闭重发；其余值钳制 ≥1000）与 `region_replay_on_start`（默认 **false**；true=鹊桥就绪首 tick、区域流启动前把本地库全部 `.qms` 的 lastSentXor 归零并全量重放区域，REQ-MS-009）。缺文件写出的 DEFAULT_YML 模板必须包含这两个键（注释风格同既有键）；数值解析失败保持默认（REQ-CFG-003 语义同样适用）；布尔键按 `Boolean.parseBoolean` 语义（非 "true" 即 false）。两键语义的实现本体分属 palette/mapstore/lifecycle 域，config 域只约束解析与默认。

**场景**：
- 给定缺文件，那么默认 paletteRepublishIntervalMs=10000、regionReplayOnStart=false，且 DEFAULT_YML 含两键。
- 给定 `palette_republish_interval_ms: 100`，那么钳制为 1000；给定 `: 0`，那么 0（关闭）；给定 `: abc`，那么保持默认 10000。
- 给定 `region_replay_on_start: true`，那么 true 生效；`nope` 按 parseBoolean 语义为 false。

**验证**：`MapExtConfigTest#lateJoinSelfHealKeysParsed`
