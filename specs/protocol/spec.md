# protocol 域 — 事件协议契约

> 域范围：4 个事件类的构造契约与 DTO Gson 序列化字段名（协议侧）。权威来源：AGENT.md §5（协议表）、ARCHITECTURE.md §4（契约 JSON 样例）、docs/INTEGRATION.md §1/§7（信封与演进规则）。

### REQ-PROTO-001 四事件 sub_type 固定、eventName=类名、协议侧零注册

**SHALL**：四个事件类 extends 鹊桥 BaseEvent，构造器 super 实参固定为：`("PlayerMoveEvent","notice","player_move")`、`("MapTileEvent","notice","map_tile")`、`("MapPaletteEvent","notice","map_palette")`、`("MapDimensionsEvent","notice","map_dimensions")`。eventName 字面量必须 = 类简单名（类重命名须同步）；sub_type 字面量永不变更。协议侧零注册：不改鹊桥任何配置/注册表（BaseEvent POJO Gson 反射直出，新事件=新类）。

**场景**：
- 给定构造任一事件，那么 eventName/postType/subType 三字段与上列字面量逐字符一致。
- 给定消费端按 sub_type 字典路由，那么四个值之外不出现任何本 mod 的 sub_type。

**验证**：`EventContractTest#subTypesFixedEventNameEqualsClass`

### REQ-PROTO-002 DTO JSON 字段名 = 单单词 camelCase、不手写鹊桥自动字段

**SHALL**：DTO 字段名必须为单单词 camelCase，由 Gson 反射直出（不配命名策略）：players / uuid / nickname / x / y / z / yaw / dimension / tiles / cx / cz / size / format / sha1 / b64 / id / colors / dimensions。DTO 及其父类链上绝不手写 `server_name / server_version / server_type / timestamp` 等鹊桥自动填充字段（信封字段由鹊桥发送路径给出）。

**场景**：
- 给定 Gson 默认配置序列化 PlayerPosModel 与 TilePayloadModel，那么 JSON 键集合恰为上述字段名，无 snake_case、无 server_* 键。
- 给定新增 DTO 字段，那么命名仍为单单词 camelCase，并登记进 REQ-PROTO-004 的基线快照。

**验证**：`DtoFieldContractTest#jsonFieldNamesSingleWordCamelCase`、`DtoFieldContractTest#noBridgeAutoFields`

### REQ-PROTO-003 dimension 一律 registry key

**SHALL**：一切维度取值（PlayerPosModel.dimension、MapTileEvent.dimension、MapDimensionsEvent.dimensions、PlatformAdapter.PlayerSnapshot.dimension）必须是 registry key `命名空间:路径`（如 `minecraft:overworld`、`minecraft:the_nether`、`twilightforest:twilight_forest`），绝不用世界文件夹名（DIM-1/世界目录名）。由平台适配层负责提取：Forge `level.dimension().location()`、NeoForge 同 Forge、Fabric `world.getRegistryKey().getValue()`。

**场景**：
- 给定下界玩家，当上报 player_move，那么 dimension=`minecraft:the_nether`。
- 给定 mod 包含自定义维度，当上报 map_dimensions，那么每项均匹配 `[a-z0-9_.-]+:[a-z0-9/._-]+`。

**验证**：`DimensionKeyContractTest#registryKeyFormatOnly`（假适配器注入，断言透传不改写）

### REQ-PROTO-004 协议字段只加不改，消费端忽略未知字段

**SHALL**：`sub_type` 与既有字段名/语义**永不变更**（要变就出新 sub_type）；新增字段只加不改删；编码变化 = 新 format 字符串（`zlib-pal8` token 不变义）；调色板内容变化 = palette_id 递增修订位（`…-a`→`…-b`）。消费端「忽略未知 sub_type/未知字段」为契约义务，条文引用 docs/INTEGRATION.md §1、§5、§7——本仓库侧义务是：产出 JSON 只增键，不做会让旧消费端崩掉的破坏。

**场景**：
- 给定 DTO 新增一个字段，那么旧 JSON 键全部原样存在，基线快照 diff 只有新增。
- 给定需要改某字段语义，那么必须开新 REQ + 新 sub_type（或新 format），旧 REQ 标记废弃，不得原地改语义。

**验证**：`ProtocolBaselineTest#jsonFieldSetOnlyGrows`（Gson 输出字段集基线快照比对，基线文件与 specs 同评审）

### REQ-PROTO-005 新 sub_type map_region：地图库补传区域快照（新 DTO 字段名）

**SHALL**：`MapRegionEvent` 构造实参 `super("MapRegionEvent", "notice", "map_region")`；字段：`dimension`（registry key，与 map_tile 同源）+ `regions`（至多 `region_stream_max_regions_per_event` 个）。`RegionPayloadModel` Gson 反射直出，JSON 字段名恰为 `rx` / `rz` / `format` / `xor` / `sha1` / `b64`（单词 camelCase）：rx/rz 为 Minecraft 区域坐标（覆盖区块 [rx*32,(rx+1)*32) × [rz*32,(rz+1)*32)）；`format` 复用 `zlib-pal8` token（像素 payload = pal8 512×512 行主序 → zlib，调色板语义与 map_tile 完全一致）；`xor` = MapStore 区域头对原始 RGB24 的 u32 XOR 校验（docs/DESIGN_MAPSTORE.md §2.5）；`sha1` = b64 内压缩字节的 hex（传输完整性可独立核验）；`b64` = 压缩字节本体。顺序铁律同样适用：map_palette 必须先于任何 map_region。本 sub_type 为新增，不改动任何既有字段（REQ-PROTO-004 义务不变）。

**场景**：
- 给定 Gson 序列化 MapRegionEvent 的键集，那么 = BaseEvent 基字段 + dimension、regions；regions 元素的键集恰为 rx/rz/format/xor/sha1/b64。
- 给定消费端按 sub_type 字典分发（docs/INTEGRATION.md §1），那么 map_region 是本轮唯一新增 sub_type，未知消费端按契约忽略。

**验证**：`ProtocolBaselineTest#newRegionDtoFieldsRegistered`
