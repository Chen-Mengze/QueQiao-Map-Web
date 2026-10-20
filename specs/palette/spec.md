# palette 域 — 共享调色板

> 域范围：`Palette` 的颜色注册、256 上限、冻结语义与 map_palette 发送时序。权威来源：AGENT.md §5（协议表）、docs/INTEGRATION.md §3.2/§4.3（下标 0=未设置、冻结）。

### REQ-PAL-001 调色板 ≤256 色、下标 0=未设置

**SHALL**：colors 数量必须 ≤256（`Palette.MAX`）；下标 0 固定为 `#000000`，语义是**未设置**（瓦片缺失区块占位；RGB=0 的颜色输入恒返回下标 0）；未冻结且未满时新颜色追加注册（下标 1..255），已满或已冻结时回退到最近色（RGB 欧氏距离，候选不含下标 0）。

**场景**：
- 给定调色板已注册至 256 色（末下标 255），当再请求新色，那么返回既有颜色中最近的下标，colors 不再增长。
- 给定任意未注册 RGB，当 indexOf，那么返回值 ∈ [1,255]（未冻结未满时）且 colors[idx] = 该色的 `#rrggbb` 小写十六进制。

**验证**：`PaletteTest#indexZeroReservedAsUnset`、`PaletteTest#capacityCappedAt256`

### REQ-PAL-002 发出后冻结（先于任何瓦片、发一次后不再变）【已废弃，由 REQ-PAL-003 取代】

**SHALL**：首次瓦片刷写必须**先于任何 MapTileEvent** 发送 MapPaletteEvent（在 TileQueue 锁内完成，鹊桥 send 是本地入队快操作）；发送成功即 `paletteSent=true` + `Palette.freeze()`——本会话内调色板内容不再变化，后续新颜色一律就近取整；发送失败则整批退回队列、paletteSent 不置位。每会话只发一次；服务端重启/调色板升级由新 Palette 实例 + 新 palette_id（修订位递增）重新发送。

**场景**：
- 给定首次 flush 且通道就绪，那么事件观察序列为 MapPaletteEvent 先于首个 MapTileEvent，且此后不再出现第二个 MapPaletteEvent。
- 给定冻结后出现新方块色，那么该像素量化为既有最近色下标，colors() 快照与发送时完全一致。

**验证**：`PaletteTest#freezeKeepsSentContentFrozen`、`PaletteTest#colorsReturnsDefensiveCopy`

### REQ-PAL-003 首发先于一切并冻结；已发布快照周期重发（晚接入自愈）

**SHALL**：首发语义与已废弃的 REQ-PAL-002 完全一致——首次瓦片刷写必须在 pack 量化之后、任何 MapTileEvent / MapRegionEvent 之前，在 TileQueue 锁内发送 MapPaletteEvent（鹊桥 send 是本地入队快操作）；发送成功即 `paletteSent=true` + `Palette.freeze()`；发送失败则整批退回队列、paletteSent 不置位。新增 `TileQueue.republishPalette(EventSink)`：仅当首发已成功后重发同一 MapPaletteEvent（id 与 colors 均与首发完全一致），重发不改变 paletteSent 与调色板冻结态；首发未成功时必须返回 false 且零事件——否则会发出未注册真实色的半空调色板并被冻结。重发节奏由 lifecycle 按配置键 `palette_republish_interval_ms`（默认 10000ms，0=关闭；键与钳制见 REQ-CFG-005）驱动；消费端对同 id+同 colors 幂等，晚接入消费者收到任一快照即可自愈并解码全部后续瓦片/区域。

**场景**：
- 给定调色板尚未首发成功，当调用 republishPalette，那么返回 false 且 sink 收到零事件（禁止把未注册真实色的半空调色板发出并冻结）。
- 给定首发已成功（paletteSent 置位、Palette 已冻结），当连续多次调用 republishPalette，那么每次重发的 MapPaletteEvent 的 id 与 colors 都和首发逐一相同，palettePublished 与冻结态保持不变。

**验证**：`ModLifecycleSmokeTest#paletteRepublishedEveryInterval`、`TileQueueTest#republishPaletteOnlyAfterFirstPublishWithIdenticalSnapshot`
