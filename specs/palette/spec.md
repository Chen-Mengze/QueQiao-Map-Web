# palette 域 — 共享调色板

> 域范围：`Palette` 的颜色注册、256 上限、冻结语义与 map_palette 发送时序。权威来源：AGENT.md §5（协议表）、docs/INTEGRATION.md §3.2/§4.3（下标 0=未设置、冻结）。

### REQ-PAL-001 调色板 ≤256 色、下标 0=未设置

**SHALL**：colors 数量必须 ≤256（`Palette.MAX`）；下标 0 固定为 `#000000`，语义是**未设置**（瓦片缺失区块占位；RGB=0 的颜色输入恒返回下标 0）；未冻结且未满时新颜色追加注册（下标 1..255），已满或已冻结时回退到最近色（RGB 欧氏距离，候选不含下标 0）。

**场景**：
- 给定调色板已注册至 256 色（末下标 255），当再请求新色，那么返回既有颜色中最近的下标，colors 不再增长。
- 给定任意未注册 RGB，当 indexOf，那么返回值 ∈ [1,255]（未冻结未满时）且 colors[idx] = 该色的 `#rrggbb` 小写十六进制。

**验证**：`PaletteTest#capacity256UnsetZeroNearestFallback`

### REQ-PAL-002 发出后冻结（先于任何瓦片、发一次后不再变）

**SHALL**：首次瓦片刷写必须**先于任何 MapTileEvent** 发送 MapPaletteEvent（在 TileQueue 锁内完成，鹊桥 send 是本地入队快操作）；发送成功即 `paletteSent=true` + `Palette.freeze()`——本会话内调色板内容不再变化，后续新颜色一律就近取整；发送失败则整批退回队列、paletteSent 不置位。每会话只发一次；服务端重启/调色板升级由新 Palette 实例 + 新 palette_id（修订位递增）重新发送。

**场景**：
- 给定首次 flush 且通道就绪，那么事件观察序列为 MapPaletteEvent 先于首个 MapTileEvent，且此后不再出现第二个 MapPaletteEvent。
- 给定冻结后出现新方块色，那么该像素量化为既有最近色下标，colors() 快照与发送时完全一致。

**验证**：`TileQueueTest#palettePrecedesAnyTileOnce`、`PaletteTest#frozenAfterSendNearestFallback`
