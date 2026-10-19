# INTEGRATION.md — 第三方接入说明与解析解释

> 本文是**消费端契约**：任何第三方（owner 自有 Web 项目、AstrBot 插件、自研地图端）按本文即可
> 独立接收并解析全部地图/位置事件。设计权威是 [ARCHITECTURE.md](../ARCHITECTURE.md)；
> 服务端实现行为以 `common/` 源码为准，本文与源码逐字段核对过。

## 0. 定位：开放的数据结构与 API

本 mod 是**鹊桥（QueQiao）的附属扩展**：鹊桥是唯一通道，不存在 WebSocket 直连或其他降级路径。
服务端把数据封装成 4 个继承鹊桥 `BaseEvent` 的事件，经 `GlobalContext.sendEvent` 进入鹊桥消息流；
消费端在鹊桥的消息接收侧按 `sub_type` 旁路收取即可，**不需要本 mod 的任何代码**。

开放面有三层，全部稳定：

1. **JSON 协议**（首选消费方式）：4 个 `sub_type` 的消息 JSON，字段语义见 §2–§4。
2. **Java DTO**（可选，给基于鹊桥的 Java 消费端）：`common` 模块的 `event/` 与 `model/` 包即数据结构本体，
   可 `compileOnly` 引用 `common` 产物与坐标 `com.github.theword.queqiao:queqiao-tool:0.6.8`（GitHub Packages，见 [ARCHITECTURE.md §7](../ARCHITECTURE.md)）直接在进程内 `instanceof` 收取（绝不 shade）。
3. **本文档 + ARCHITECTURE.md §4**：协议契约的唯一出处，改动走版本号（见 §7）。

## 1. 信封字段与路由

每条事件都是鹊桥消息流中的一条 JSON 消息。路由**只看 `sub_type`**（已核对的原始键名）：

| sub_type | 事件类 | 载荷 |
|---|---|---|
| `map_dimensions` | MapDimensionsEvent | dimensions[] |
| `map_palette` | MapPaletteEvent | id, colors[] |
| `player_move` | PlayerMoveEvent | players[] |
| `map_tile` | MapTileEvent | dimension, tiles[] |

- `server_name / server_version / server_type / timestamp` 由鹊桥在发送路径自动填充；消费端可用来区分多台服务器。
- 事件名（`PlayerMoveEvent` 等）与 postType（`notice`）也在信封里，但键名以鹊桥实际输出为准——**不要依赖它们路由**，只认 `sub_type`。
- **前向兼容铁律**：遇到未知 `sub_type`、未知字段，一律忽略而不是报错。协议只会加字段，不改语义（§7）。

## 2. 连接时序（发送端保证的顺序）

```
鹊桥 init 完成 + 服务端启动  →  mod 才开始发送（此前完全静默，零流量）
  1. map_dimensions   连接建立后一次；之后清单变化时重发（5s 巡检）
  2. map_palette      任何 map_tile 之前必达；每连接一次；发出后即冻结
  3. player_move / map_tile   持续批量流（节流参数见 §6）
```

消费端建议：收到 `map_palette` 之前缓存的 `map_tile` 要么丢弃、要么挂起等调色板；按 `(dimension, paletteId)` 做瓦片缓存键。

## 3. 事件契约与 JSON 样例

### 3.1 map_dimensions — 维度清单

```json
{
  "sub_type": "map_dimensions",
  "dimensions": ["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"]
}
```

元素一律 **registry key**（`命名空间:路径`），绝不会出现世界文件夹名。用它给 UI 生成维度页签。

### 3.2 map_palette — 共享调色板

```json
{
  "sub_type": "map_palette",
  "id": "vanilla-1.20.1-a",
  "colors": ["#000000", "#9EA7A9", "#6DBBC7", "…"]
}
```

- `colors` ≤ 256 项，每项 `#RRGGBB`；**下标即像素值**（见 §4）。
- `colors[0]` 固定 `#000000`，语义是**未设置**（见 §4.3），渲染成透明，不要画黑。
- 发出后调色板**冻结**：本次连接内所有瓦片都用它量化。服务端重启/调色板版本升级会带**新 id** 重发
  （`vanilla-1.20.1-a` → `-b` 递增修订位）。

### 3.3 player_move — 玩家位置（批量）

```json
{
  "sub_type": "player_move",
  "players": [
    {
      "uuid": "8b8d…", "nickname": "Yumezawa",
      "x": 128.5, "y": 64.0, "z": -20.25, "yaw": 90.0,
      "dimension": "minecraft:overworld"
    }
  ]
}
```

- 每 tick 抽样、按节流批量合并，**一次事件带多名玩家**，不是逐人一条。
- `x/y/z` 是玩家坐标（方块坐标 = `floor(x)`）；`yaw` 度；`dimension` 同 registry key。
- 语义是 **upsert**：批里只含"到点要更新"的玩家（移动中 ≈2.5Hz，静止心跳 ≈0.67Hz）。
- **离线判定在消费端**：玩家不会发"下线"事件（离线后只是不再出现）。建议超过 5s 没更新即标记离线。

### 3.4 map_tile — 地图瓦片（批量）

```json
{
  "sub_type": "map_tile",
  "dimension": "minecraft:overworld",
  "tiles": [
    {
      "cx": 0, "cz": 0, "size": 8,
      "format": "zlib-pal8",
      "sha1": "e3b0c4…", "b64": "eJxjYmBgYmJg…"
    }
  ]
}
```

字段语义见 §4。

## 4. 瓦片解析：`zlib-pal8` 解码步骤

### 4.1 坐标与锚点

- `cx / cz`：**瓦片左上角的区块坐标**（整数，可为负）。
- `size`：每边**区块**数，当前只有 `1`（单区块）或 `8`（8×8 区域合并，脏区块按 `(cx>>3, cz>>3)` 分组）。
- 瓦片覆盖的方块范围：`x ∈ [cx*16, (cx+size)*16)`，`z ∈ [cz*16, (cz+size)*16)`。
- 解码后的像素网格边长 `side = size * 16` 像素，1 像素 = 1 方块列。
- 像素 (px, pz) 对应方块 `x = cx*16 + px`、`z = cz*16 + pz`；px 向东（x+），pz 向南（z+），左上原点。

### 4.2 解码三步

```
b64 --Base64 解码--> zlib 流 --inflate--> side*side 字节数组   (行优先: index = pz*side + px)
每个字节 = 调色板下标 → colors[下标] = #RRGGBB
```

Python：

```python
import base64, zlib
def decode_tile(t, palette):
    idx = zlib.decompress(base64.b64decode(t["b64"]))
    side = t["size"] * 16
    return [palette[idx[pz * side + px]] for pz in range(side) for px in range(side)]
```

浏览器（`palette` 已按 id 缓存）：

```js
async function decodeTile(t, colors) {
  const comp = Uint8Array.from(atob(t.b64), c => c.charCodeAt(0));
  const idx = new Uint8Array(await new Response(
    new Blob([comp]).stream().pipeThrough(new DecompressionStream("zlib"))).arrayBuffer());
  const side = t.size * 16;
  // idx[pz * side + px] → colors[...]，0 = 透明
}
```

### 4.3 像素语义

- 每个像素是 `map_palette` 中某颜色的**下标**（8-bit），对应 MC `MapColor`（游戏内地图同款颜色）：
  服务端取该列顶层方块（OCEAN_FLOOR 高度图语义）的地图颜色，量化进调色板。
- **下标 0 = 未设置**：采样时该区块未加载/未采到。渲染为透明或"雾"，不要画成黑色。
- 不含高度与遮挡信息（后续协议演进再加，见 §7）。

### 4.4 sha1 去重

`sha1` = **zlib 压缩字节**（base64 之前、压缩之后）的小写 hex 摘要。
用途：内容寻址去重——同一 `(dimension, cx, cz, size)` 位置收到相同 sha1 直接跳过；
服务端对脏区块重采后内容不变时，消费端也应靠它省掉重复绘制。

## 5. 消费端健壮性清单

1. 未知 `sub_type` / 未知字段：忽略。
2. `format` 不等于 `zlib-pal8`：跳过该瓦片并记日志（新编码会用新 format 字符串）。
3. 调色板缺 id：挂起或丢弃瓦片，等新 `map_palette`。
4. 所有解析 `try/except` 兜底，绝不把异常抛回消息接收循环（否则打断整条鹊桥连接）。
5. 多服务器：用信封里的 `server_name/server_version` 分流。
6. 乱序/重复：单条鹊桥连接内 TCP 有序，但重连可能重放；瓦片按位置键覆盖写即可幂等。

## 6. 服务端默认节流（消费端可据此估流量）

`config/QueQiao/queqiao_map_web.yml` 默认值：

```yaml
enabled: false                      # 不开则零流量
batch_size: 64
flush_interval_ms: 1000
move_threshold: 1.5                 # 位移阈值（格）
move_active_interval_ms: 400        # 移动中上报周期
move_idle_interval_ms: 1500         # 静止心跳周期
max_chunk_renders_per_tick: 64
pending_cap: 1024
palette_id: vanilla-1.20.1-a
dimension_check_interval_ms: 5000
```

量级参考：8×8 区域瓦片（128×128 索引）zlib 后典型 0.5–2 KB；合并摊薄后 ≈20 B/区块。
这就是为什么协议绝不用小 PNG（16×16 PNG ≈ 938 B，约为索引的 9 倍）。

## 7. 兼容性承诺与演进规则

- `sub_type` 名与既有字段语义**永不改**；要变就出新 `sub_type`。
- 新增字段随时可能发生：消费端必须忽略未知字段。
- 编码变化 = 新的 `format` 字符串（`zlib-pal8` 这个 token 不变义）。
- 调色板内容变化 = `map_palette.id` 递增修订位（`…-a` → `…-b`）。
- 服务端 DTO（`common` 的 `event/` `model/`）与本文同仓演进，JSON 字段名 = 单词 camelCase 直出，不玩命名策略。

## 8. Java 消费端：DTO 直接复用（可选）

如果消费端本身跑在鹊桥进程内（如另一台服务器上的 Java 扩展），可直接引用：

- `common` 模块产物（无任何 `net.minecraft.*` 依赖）：`event/PlayerMoveEvent|MapTileEvent|MapPaletteEvent|MapDimensionsEvent`、
  `model/PlayerPosModel|TilePayloadModel`；
- `compileOnly` 坐标 `com.github.theword.queqiao:queqiao-tool:0.6.8`（GitHub Packages 拉取；运行期类由鹊桥本体提供，两边类加载器互通；绝不把 tool 打进自己 jar）。

注意包名风险：tool `0.6.8` 是 `com.github.theword.queqiao.tool.*`，新 `queqiao-core` 是 `io.github.theword.queqiao.core.*`
（详见 ARCHITECTURE.md §7）。跨版本前先 `javap` 核对，别凭记忆。
