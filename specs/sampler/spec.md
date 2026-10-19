# sampler 域 — 玩家位置采样

> 域范围：`PositionSampler` 的差分、节流与 last-value 批量合并（player_move 数据面）。权威来源：AGENT.md §4.6（禁每 tick 全量广播）、docs/INTEGRATION.md §3.3/§6（节流参数）。

### REQ-SAMP-001 位移阈值 1.5 格以下不发送

**SHALL**：与上次上报位置的三维欧氏距离 ≤ moveThreshold（默认 **1.5** 格；实现比较距离平方 > threshold² 才算「移动」）且未到静止心跳时点，则本轮完全跳过该玩家（不发旧坐标、不更新状态）。

**场景**：
- 给定玩家已上报于 P0，当下一 tick 位移 1.0 格且距上次发送 < moveIdleIntervalMs，那么该玩家不在本轮输出。
- 给定首次见到的玩家，当采样，那么无视节流立即上报（首见全量）。

**验证**：`PositionSamplerTest#belowThresholdNotSent`

### REQ-SAMP-002 静止心跳 ~0.67Hz

**SHALL**：未移动玩家必须按 ≥moveIdleIntervalMs（默认 1500ms ≈ **0.67Hz**）周期性重发一次位置（心跳语义，消费端据此区分「在线静止」与「离线」）。

**场景**：
- 给定玩家静止不动，当以固定 nowMs 连续采样跨 4500ms，那么恰好 3 次上报（每 1500ms 一次）。

**验证**：`PositionSamplerTest#idleHeartbeatEvery1500ms`

### REQ-SAMP-003 移动限速 ~2.5Hz

**SHALL**：移动中（超阈值）玩家的上报间隔必须 ≥moveActiveIntervalMs（默认 400ms ≈ **2.5Hz**）；同一玩家每轮至多一条（last-value 合并），批量事件内去重。

**场景**：
- 给定玩家每 tick 位移 > 1.5 格，当连续采样 1600ms 窗口，那么该玩家至多 4 次上报。

**验证**：`PositionSamplerTest#movingRateLimited400ms`

### REQ-SAMP-004 玩家离线自动清理

**SHALL**：本轮快照未出现的玩家，其采样状态必须即刻清除（state.keySet().retainAll(seen)）；不发送任何「下线」事件（离线语义 = 不再出现，判定在消费端）；重连后按首见规则立即全量上报一次。

**场景**：
- 给定玩家 A 在上一批出现过而本批快照缺失，当本轮 sample 返回，那么 state 不再含 A；下一批 A 重新出现时无视节流上报。

**验证**：`PositionSamplerTest#offlinePlayerStateCleared`
