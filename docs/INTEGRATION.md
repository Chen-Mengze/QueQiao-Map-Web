# INTEGRATION.md — 第三方接入说明与解析解释

> 本文是**消费端契约**：任何第三方（owner 自有 Web 项目、AstrBot 插件、自研地图端）按本文即可
> 独立接收并解析全部地图/位置事件。设计权威是 [ARCHITECTURE.md](../ARCHITECTURE.md)；
> 服务端实现行为以 `common/` 源码为准，本文与源码逐字段核对过。

## 0. 定位：开放的数据结构与 API

本 mod 是**鹊桥（QueQiao）的附属扩展**：鹊桥是唯一通道，不存在 WebSocket 直连或其他降级路径。
服务端把数据封装成 5 个继承鹊桥 `BaseEvent` 的事件，经 `GlobalContext.sendEvent` 进入鹊桥消息流；
消费端在鹊桥的消息接收侧按 `sub_type` 旁路收取即可，**不需要本 mod 的任何代码**。

开放面有三层，全部稳定：

1. **JSON 协议**（首选消费方式）：5 个 `sub_type` 的消息 JSON，字段语义见 §2–§4。
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
| `map_region` | MapRegionEvent | dimension, regions[]（二维区域全量/续传，见 §3.5） |

- `server_name / server_version / server_type / timestamp` 由鹊桥在发送路径自动填充；消费端可用来区分多台服务器。
- 事件名（`PlayerMoveEvent` 等）与 postType（`notice`）也在信封里，但键名以鹊桥实际输出为准——**不要依赖它们路由**，只认 `sub_type`。
- **前向兼容铁律**：遇到未知 `sub_type`、未知字段，一律忽略而不是报错。协议只会加字段，不改语义（§7）。

## 2. 连接时序（发送端保证的顺序）

```
鹊桥 init 完成 + 服务端启动  →  mod 才开始发送（此前完全静默，零流量）
  1. map_dimensions   连接建立后一次；之后清单变化时重发（5s 巡检）
  2. map_palette      任何 map_tile / map_region 之前必达；发出后即冻结；首发成功后按 palette_republish_interval_ms（默认 10s）周期性重发同一快照——晚接入/WS 重连的消费端在间隔内自愈（REQ-PAL-003）
  3. player_move / map_tile / map_region   持续批量流（map_region 为区域全量/续传流，节流见 §6）
```

消费端建议：收到 `map_tile` 但尚无调色板时要么丢弃、要么挂起等调色板；调色板按 `(server_name, paletteId)` 缓存（palette 会按间隔重发；旧版本服务端只发一次时落盘仍是兜底，见 §9.2）。

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
- 发出后调色板**冻结**：本次服务端会话内所有瓦片都用它量化。服务端重启会**重发**（按配置 id）；调色板内容升级先递增 id（`…-a` → `…-b`）再发（见 §7）
  （`vanilla-1.20.1-a` → `-b` 递增修订位）。
- 同一 id+colors 的 `map_palette` 会按间隔**重复到达**（`palette_republish_interval_ms`，默认 10s，§2/§6）——消费端必须**幂等**处理：`(server_name, id)` 已缓存且 colors 相同即直接跳过，这是晚接入/断线重连的自愈通道（REQ-PAL-003）；调色板内容升级仍走 id 递增修订位（§7 不变）。

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

### 3.5 map_region — 二维区域（全量/续传）

```json
{
  "sub_type": "map_region",
  "dimension": "minecraft:overworld",
  "regions": [
    {
      "rx": 0, "rz": -1,
      "format": "zlib-pal8",
      "xor": 1234567890,
      "sha1": "e3b0c4…", "b64": "eJxjYmBgYmJg…"
    }
  ]
}
```

- `rx / rz`：**MC 区域坐标**（整数，可为负），与存档文件 `r.<rx>.<rz>.mca` 同款坐标——每区域覆盖区块 `[rx*32,(rx+1)*32) × [rz*32,(rz+1)*32)`，即 **32×32 区块 = 512×512 像素**（1 像素 = 1 方块列）。
- 解码与 §4 的 `zlib-pal8` 完全一致，仅 `side` 固定为 **512**：`b64` → inflate → 512×512 索引字节（行优先 `index = pz*512 + px`），字节 = `map_palette` 下标（0 = 未设置，渲染透明）。
- `xor` 是**服务端库内容校验**（Xaero 式异或）：服务端本地地图库对该区域**未压缩 RGB24** 载荷按 4 字节大端组异或。服务端只在 `(server, dimension, rx, rz) + xor` 变化时重发该区域——这就是**断点续传**：重连时消费端已一致的区域不会再收到。注意与 `sha1` 分属两层：`sha1` = 压缩载荷字节的 hex 摘要（去重用），`xor` = 未压缩库内容的版本指纹（续传判定用）。
- 区域到达语义 = **整块替换**该 512×512 范围（含区域内未生成区块的全 0 列）。
- 与 `map_tile`（size 1/8 增量）**叠加幂等**：区域流 = 整块替换、tile = 局部覆盖，按位置键覆盖写即可，到达顺序无关。
- 时序：`map_palette` 先于任何 `map_region` 必达（与 `map_tile` 同一条保证，见 §2）。
- 消费端健壮性适用 §5 清单：未知 `format` 跳过并记日志、解析全程 `try/except` 兜底、未知字段忽略。
- 预算：单区域压缩后典型 **15–60 KB**（512×512 pal8 raw 256 KB → zlib）；默认 **64 KB/s** 节流（见 §6），全量补发 2000 区域 ≈ **10–20 分钟**一次性，此后仅变化区域。

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

区域级去重同构：`map_region` 的位置键是 `(server, dimension, rx, rz)`——先比 `xor`（服务端库对未压缩内容的 Xaero 式校验）：相等即服务端直接跳过、只有 xor 变化才重发（= 断点续传）；消费端收到后再按 `sha1`（压缩字节摘要）跳过重复解压与绘制。

## 5. 消费端健壮性清单

1. 未知 `sub_type` / 未知字段：忽略。
2. `format` 不等于 `zlib-pal8`：跳过该瓦片并记日志（新编码会用新 format 字符串）。
3. 调色板缺 id：挂起或丢弃瓦片，等新 `map_palette`。服务端现按间隔重发已发布调色板（§2），丢弃未决瓦片的窗口最迟一个间隔即恢复；挂起等调色板仍是最稳选择。
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
backfill_enabled: true                   # mca→本地地图库 回填开关
backfill_max_chunks_per_sec: 512         # 回填节流（按 chunk 计，钳制 ≥16）
region_stream_enabled: true              # map_region 区域流开关
region_stream_bytes_per_sec: 65536       # 区域流节流（按压缩字节计，钳制 ≥4096）
region_stream_max_regions_per_event: 4   # 单事件最多区域数（钳制 ≥1 ≤16）
palette_republish_interval_ms: 10000     # 已发布调色板重发间隔毫秒（0=关闭）
region_replay_on_start: false            # true=会话启动时复位全部 lastSentXor 全量重放区域（一次性自愈开关）
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

## 9. 教程案例：端到端最小消费端

本节把 §1–§6 串成可运行的最小闭环：部署二选一 → 单文件 Python 消费端 → 时序预期与排错。
示例仅作教程；生产接入仍以 §5 健壮性清单为验收线。

### 9.1 部署与连接（二选一）

通道拓扑由**鹊桥本体**的 config.yml 决定，两种模式**只启用其一**；本 mod 侧要
`config/QueQiao/queqiao_map_web.yml` 的 `enabled: true`（§6）才有流量，否则全链路零流量。

**模式 A（推荐）：第三方自跑 WS 服务器，鹊桥作客户端主动外连**——地址在第三方手里，防火墙只开第三方端：

```yaml
# 鹊桥 config.yml
websocket_client:
  enable: true
  url_list: ["ws://<第三方IP>:8080/minecraft/ws"]   # 指向第三方监听（§9.2 模式 A）
  access_token: "<共享密钥>"                        # 与消费端一致，两端校验
  # 断线自动重连，间隔默认 5s；其余握手字段以你所用鹊桥版本的 config.yml 注释为准
```

**模式 B：鹊桥起 WS 服务端，第三方作客户端连入**（MC 服务器需可被第三方直连）：

```yaml
# 鹊桥 config.yml
websocket_server:
  enable: true
  host: "0.0.0.0"                                  # 绑定与端口按部署填
  port: 8081
  access_token: "<共享密钥>"                        # 与消费端一致，两端校验
```

第三方连 `ws://<MC服务器IP>:<port>`（§9.2 模式 B）。

要点：`access_token` 两端一致校验，不一致直接连不上/断连（零流量先查它）；
信封 `server_name` 用于多台 MC 服务器分流（§1、§5.5），消费端缓存与落盘目录都按它隔离；
模式 A 鹊桥默认 5s 自动重连，WS 层断连窗口内的状态由 §9.2 的 `state.json` 兜底。

### 9.2 单文件 Python 消费端（可直接运行）

`pip install websockets`，其余全标准库（zlib/base64/json/struct/binascii）。
字段名与 §2–§5 逐字段一致：路由只认 `sub_type`；palette 用 `id/colors[]`；tile 用 `cx/cz/size/format/sha1/b64`；
region 用 `rx/rz/format/xor/sha1/b64`；解码一律 `b64 → zlib.decompress → 索引字节`，`index = pz*side + px`。

```python
#!/usr/bin/env python3
"""queqiao_map_consumer.py — 教程示例：端到端最小消费端（§9，单文件）

只依赖第三方库 websockets（pip install websockets），其余全部标准库。
  模式 A（自跑 WS 服务器，配合鹊桥 websocket_client）：python queqiao_map_consumer.py listen
  模式 B（连鹊桥 websocket_server）：                    python queqiao_map_consumer.py connect
"""
import asyncio, base64, binascii, json, os, struct, sys, time, zlib

import websockets

# ---------- 配置（§9.1） ----------
SERVER_NAME = None                                   # None=收所有服务器；否则只处理该 server_name（信封分流，§5.5）
LISTEN_HOST, LISTEN_PORT = "0.0.0.0", 8080           # 模式 A：填进鹊桥 websocket_client.url_list
CONNECT_URL = "ws://<MC服务器IP>:<port>"             # 模式 B：鹊桥 websocket_server 的 host/port
ACCESS_TOKEN = "<共享密钥>"                          # 两端一致校验；模式 B 按你所用鹊桥版本的握手字段（路径/头）附带
STATE_FILE, OUT_DIR = "state.json", "out"
OFFLINE_AFTER_S = 5.0                                # §3.3：离线判定在消费端，>5s 未更新即标离线

# ---------- 状态（键全部含 server_name，多台互不串扰，§1/§5.5） ----------
palettes = {}        # (server_name, palette id) → colors[#RRGGBB 列表]（§3.2）；JSON 落盘=旧服务端只发一次的兜底
last_palette_id = {} # server_name → 最新调色板 id（渲染入口）
xor_of = {}          # "server|dim|rx|rz" → xor（§3.5：服务端据此决定重发；本地留档核对续传）
grids = {}           # (server, dim, rx, rz) → bytearray(512*512) 索引网格；1 像素=1 字节=调色板下标
dirty = set()        # 需重画 png 的网格键
seen_sha1 = set()    # 位置键+sha1 内容寻址去重（§4.4；不落盘——重放到达也只是幂等重写，§5.6）
players = {}         # (server, uuid) → 最近一次 upsert（§3.3）

def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            s = json.load(f)
        for server, pid, colors in s.get("palettes", []):
            palettes[(server, pid)] = colors
            last_palette_id.setdefault(server, pid)
        xor_of.update(s.get("xors", {}))
    except FileNotFoundError:
        pass

def save_state():
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump({"palettes": [[s, p, c] for (s, p), c in palettes.items()],
                   "xors": xor_of}, f, ensure_ascii=False)

# ---------- 纯标准库 PNG 写出（RGB8；zlib 压缩 + crc32 分块） ----------
def write_png(path, w, h, rgb):
    raw = b"".join(b"\x00" + rgb[y * w * 3:(y + 1) * w * 3] for y in range(h))
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", binascii.crc32(tag + data) & 0xFFFFFFFF))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 6))
                + chunk(b"IEND", b""))

def flush_pngs():
    for key in list(dirty):
        server, dim, rx, rz = key
        colors = palettes.get((server, last_palette_id.get(server)))
        if not colors:
            continue                                   # §5.3：调色板缺 → 挂起等新 map_palette
        colors = colors + ["#000000"] * (256 - len(colors))
        tbl = [bytes.fromhex("101010") if i == 0 else
                bytes.fromhex(colors[i][1:3]) + bytes.fromhex(colors[i][3:5]) + bytes.fromhex(colors[i][5:7])
                for i in range(256)]
        # 下标 0 契约上是「未设置=透明」（§4.3）；示例 PNG 不带 alpha，画 #101010 深灰占位，勿当真值
        write_png(os.path.join(OUT_DIR, server, dim.replace(":", "_"), f"region_{rx}_{rz}.png"),
                  512, 512, b"".join(tbl[b] for b in grids[key]))
        dirty.discard(key)

# ---------- map_tile 局部覆盖进所属 512 网格（rx=cx>>5；§3.5 叠加幂等） ----------
def overlay_tile(server, dim, cx, cz, size, idx):
    side = size * 16                                   # §4.1：side = size*16 像素
    x0, z0 = cx * 16, cz * 16                          # 绝对方块坐标；1 像素 = 1 方块列
    for pz in range(side):                             # 行优先 index = pz*side + px（§4.2）
        az = z0 + pz
        rz, lz = az >> 9, az & 511                     # Python >> 是算术移位：负坐标=floorDiv，与 MC 区域坐标一致
        x = x0
        while x < x0 + side:                           # 8×8 瓦片可跨区域边界：按 512 边界切段落多个网格
            rx = x >> 9
            w = min(x0 + side - x, ((rx + 1) << 9) - x)
            g = grids.setdefault((server, dim, rx, rz), bytearray(512 * 512))
            dst, src = lz * 512 + (x & 511), (x - x0) + pz * side
            g[dst:dst + w] = idx[src:src + w]          # 局部覆盖：按位置键写入即幂等（§5.6）
            dirty.add((server, dim, rx, rz))
            x += w

# ---------- 路由：只看 sub_type（§1 铁律）；每消息 try/except（§5.4） ----------
async def handle(raw):
    try:
        m = json.loads(raw)
    except Exception as e:
        print("[skip] 非法 JSON:", e); return
    st = m.get("sub_type")
    if st not in ("map_dimensions", "map_palette", "player_move", "map_tile", "map_region"):
        return                                         # §5.1：未知 sub_type/字段忽略（前向兼容）
    server = m.get("server_name", "?")
    if SERVER_NAME and server != SERVER_NAME:
        return
    try:
        if st == "map_dimensions":
            print("[dims]", server, m.get("dimensions"))          # §3.1：registry key，直接生成页签
        elif st == "map_palette":
            pid, colors = m["id"], m["colors"]
            palettes[(server, pid)] = colors
            last_palette_id[server] = pid
            save_state()                                            # palette 按间隔重发（旧服务端只发一次）：(server,id) 幂等缓存+落盘仍是冷启动兜底
            print("[palette]", server, pid, f"{len(colors)} colors")
        elif st == "player_move":
            now = time.monotonic()
            for p in m.get("players", []):                          # §3.3：批内 upsert 语义，非逐人一条
                players[(server, p["uuid"])] = {**p, "ts": now, "offline": False}
            print(f"[move] {server} {len(m.get('players', []))} 玩家 upsert")
        elif st == "map_tile":
            dim = m["dimension"]
            for t in m.get("tiles", []):
                if t.get("format") != "zlib-pal8":
                    print("[skip] 未知 format:", t.get("format")); continue   # §5.2
                key = (server, dim, t["cx"], t["cz"], t["size"], t["sha1"])
                if key in seen_sha1: continue                       # §4.4：位置键+sha1 去重
                seen_sha1.add(key)
                idx = zlib.decompress(base64.b64decode(t["b64"]))   # §4.2 三步：b64→inflate→索引
                overlay_tile(server, dim, t["cx"], t["cz"], t["size"], idx)
        elif st == "map_region":
            dim = m["dimension"]
            for rg in m.get("regions", []):
                if rg.get("format") != "zlib-pal8":
                    print("[skip] 未知 format:", rg.get("format")); continue
                key = (server, dim, rg["rx"], rg["rz"], rg["sha1"])
                if key in seen_sha1: continue                       # §3.5：消费端按 sha1（压缩字节摘要）去重
                seen_sha1.add(key)
                idx = zlib.decompress(base64.b64decode(rg["b64"]))
                if len(idx) != 512 * 512:
                    print("[skip] 区域载荷长度异常:", len(idx)); continue
                grids[(server, dim, rg["rx"], rg["rz"])] = bytearray(idx)  # 区域=整块替换（§3.5）
                xor_of[f"{server}|{dim}|{rg['rx']}|{rg['rz']}"] = rg.get("xor")  # xor 留档：xor 变=服务端重发过
                dirty.add((server, dim, rg["rx"], rg["rz"]))
    except Exception as e:
        print("[skip] 解析异常兜底（绝不抛回接收循环）:", repr(e))   # §5.4

async def stream(ws):
    async for raw in ws:
        await handle(raw)

async def janitor():
    while True:
        await asyncio.sleep(1.0)
        now = time.monotonic()
        for (server, uuid), p in players.items():
            if not p["offline"] and now - p["ts"] > OFFLINE_AFTER_S:
                p["offline"] = True
                print(f"[offline] {server} {p.get('nickname')} ({p.get('dimension')})")
        flush_pngs()

async def main():
    load_state()
    mode = (sys.argv[1] if len(sys.argv) > 1 else "listen").lower()
    jan = asyncio.create_task(janitor())
    try:
        if mode == "listen":      # 模式 A：本端跑 WS 服务器，鹊桥 websocket_client 主动连进来（断线它自己重连，默认 5s）
            async def per_client(ws, *args):
                print("[conn] 鹊桥已连入:", ws.remote_address)
                await stream(ws)
            async with websockets.serve(per_client, LISTEN_HOST, LISTEN_PORT):
                print(f"[listen] ws://{LISTEN_HOST}:{LISTEN_PORT} —— 把地址填进鹊桥 websocket_client.url_list")
                await asyncio.Future()
        else:                     # 模式 B：鹊桥 websocket_server 起服务端，本端作客户端连入
            uri = CONNECT_URL     # ACCESS_TOKEN 按你所用鹊桥版本的握手方式附带（两端一致校验，§9.1）
            async with websockets.connect(uri) as ws:
                print("[conn]", uri)
                await stream(ws)
    finally:
        jan.cancel()
        save_state()

if __name__ == "__main__":
    asyncio.run(main())
```

行为摘要：五 `sub_type` 路由、未知忽略；`map_palette` 按 `(server_name, id)` 缓存并 JSON 落盘；
`map_region` 去重后整块替换 512×512 网格、纯标准库写 `region_<rx>_<rz>.png`；
`map_tile` 去重后局部覆盖进所属网格（`rx = cx >> 5`，跨界瓦片按行切段）；
`player_move` upsert + >5s 标离线；每条消息独立 `try/except`，异常不打断连接（§5.4）。

### 9.3 启动时序预期与排错清单

正常时序（§2 的发送端保证）：

| 时间点 | 应看到 |
|---|---|
| 启动后 ~5s | 一条 `map_dimensions`（之后 5s 巡检跟变化） |
| 紧随 | 首刷 `map_palette`（服务端启动后发一次，随后冻结；随后按 palette_republish_interval_ms 周期重发） |
| 持续 | `map_tile` / `player_move` 增量流；老服务器首会话另有 `map_region` 全量补发，按默认 64 KB/s ≈ 2000 区域 10–20 分钟（§3.5 预算） |

排错：

- **任何阶段零流量**：依次查 本 mod `enabled: true` → 鹊桥 init 完成（此前 mod 完全静默，§2）→ 两模式恰好启用其一、`url_list`/host:port 正确、防火墙放行 → `access_token` 两端一致。
- **只有 `map_tile` 没有 `map_region`**：`region_stream_enabled` 未开（§6），或服务端 `worldSaveRoot()` 不支持导致 mapstore 关闭、仅剩实时流（见 ARCHITECTURE.md §5.5）；再看 `backfill_enabled` 与 store 目录是否已有 `.qms`。
- **重连后没有重复 region 流**：这是正常——xor 续传生效（§3.5），服务端判定你已一致，只有新增/变化区域会流。
- **区域曾在消费端离线期间已发送导致永不重发**：把 `region_replay_on_start` 设 `true` 启动一次（就绪首 tick 复位全部 `lastSentXor`，全部区域按区域流限速重放一遍，§6），恢复后改回 `false`；旧办法=删世界存档下 `queqiao_map_web/store/` 让回填重建。
- **重连收不到 `map_palette`**：新版服务端按间隔自动重发调色板（§2/§6，≤10s 自愈）；旧版本服务端只发一次时，§9.2 的 `state.json` 落盘缓存兜底，不要因此挂起全部渲染。
- **图大面积深灰**：该范围索引全 0（未采样，§4.3），不是解码错误；等回填/区域流追上即可。
