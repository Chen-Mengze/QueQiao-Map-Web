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
