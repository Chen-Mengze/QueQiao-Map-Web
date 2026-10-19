# CONTRIBUTING.md — PR 规范

本仓库整体由 AI 制作（见 README），但任何 PR（含 owner 自用分支）都按本节验收。

## 1. 类命名标准（功能命名，PR 必检）

顶层类名 = **功能角色名**（PascalCase 完整英文单词；不用目录简写前缀、下划线、破折号）。目录（包）承载职责，类名不再重复目录名。

| 位置 | 类 | 说明 |
|---|---|---|
| 根包 queqiao_map_web | ModLifecycle | 装配 + 生命周期（平台无关核心） |
| config/ | MapExtConfig | queqiao-mapext.yml 解析与默认值 |
| event/ | PlayerMoveEvent / MapTileEvent / MapPaletteEvent / MapDimensionsEvent | 四个上报事件（extends BaseEvent） |
| model/ | PlayerPosModel / TilePayloadModel | Gson DTO（字段名单单词，反射直出） |
| platform/ | PlatformAdapter（接口）；实现 = <加载器>PlatformAdapter（ForgePlatformAdapter…） | 加载器差异抹平 |
| tile/ | TileQueue / Palette / TopBlockRenderer | 瓦片功能集：脏区块刷写队列 / 调色板冻结 / 瓦片编码 |
| sampler/ | PositionSampler | 玩家位置差分采样（瓦片队列在 tile/） |
| sender/ | EventSink（接口）/ QueQiaoSink | 发送抽象与鹊桥实现（GlobalContext 只许出现在 QueQiaoSink.java） |
| 各加载器分支根 | **QueqiaoMapWeb**（三加载器同名、包隔离） | 主入口类名 = modid（queqiao_map_web）的 PascalCase |

细则：

- 主入口类名与 modid 同步：改 mod_id 必须同步三个 QueqiaoMapWeb、fabric.mod.json、mods.toml、gradle.properties。
- 嵌套/支撑类型按语义命名、不加前缀（PlayerSnapshot、ChunkPos、ChunkDirtyListener、TopBlockRenderer.Sample、TileQueue.Captured/Entry/Last）。
- 新目录/新加载器：类名直接取功能角色名；新加载器入口同样命名 QueqiaoMapWeb（包隔离）。
- 协议铁律：`sub_type` 与 DTO 字段名**永不**随重命名改动；BaseEvent 构造器 eventName 字面量 = 类名，随类名同步（Web 端只按 sub_type 路由，见 docs/INTEGRATION.md §2；改名须同步 ARCHITECTURE.md §4 示例 JSON）。

历史映射（命名演变 2026-10 两轮：功能命名 →（fbaa536）三段式 →（本次）功能命名+modid 入口）：MapWeb→QmwLifecycle→ModLifecycle；MapExtConfig→CfgMapExt→MapExtConfig；PlatformAdapter→PlatAdapter→PlatformAdapter；PlayerMoveEvent→EvtPlayerMove→PlayerMoveEvent；MapTileEvent→EvtMapTile→MapTileEvent；MapPaletteEvent→EvtMapPalette→MapPaletteEvent；MapDimensionsEvent→EvtMapDimensions→MapDimensionsEvent；PlayerPosModel→MdlPlayerPos→PlayerPosModel；TilePayloadModel→MdlTilePayload→TilePayloadModel；Palette→RndPalette→Palette；TopBlockRenderer→RndTopBlock→TopBlockRenderer；PositionSampler→SmpPosition→PositionSampler；TileQueue→SmpTileQueue→TileQueue；EventSink→SndEvent→EventSink；QueQiaoSink→SndQueQiao→QueQiaoSink；MapWebForge→ForgeEntry→QueqiaoMapWeb；MapWebFabric→FabricEntry→QueqiaoMapWeb；MapWebNeoForge→NeoForgeEntry→QueqiaoMapWeb；ForgePlatformAdapter→PlatForgeAdapter→ForgePlatformAdapter；FabricPlatformAdapter→PlatFabricAdapter→FabricPlatformAdapter；NeoForgePlatformAdapter→PlatNeoForgeAdapter→NeoForgePlatformAdapter。

目录演变（2026-10 本轮，对齐标准 mod 布局）：`origin/`→`common/`（多加载器标准主结构目录）；`<loader>/origin/`→`<loader>/`（去嵌套扁平化）；版本覆盖层 `<loader>/<loader>-<ver>/`→`<loader>/versions/<ver>/`；`render/`→`tile/`（并入 `TileQueue`），`sampler/` 仅剩 `PositionSampler`；neoforge 模板归位 `src/main/templates/`。

## 2. PR 验收清单

1. 构建绿：set JAVA_HOME=C:\Program Files\BellSoft\LibericaJDK-17-Full && gradlew :common:build :forge:build。
2. common 改动跑 javac 秒级自检（命令见 AGENT.md §3），不引入 net.minecraft.* 与第三方 import。
3. 类名符合功能命名（本文件 §1 表），改名后旧类名零残留（rg \b旧名\b，含 eventName 字面量与 fabric.mod.json 入口）。
4. 协议铁律：sub_type 与既有字段语义不变、字段只加不改（docs/INTEGRATION.md §7）；DTO 不手写鹊桥自动字段。
5. 发送铁律不破坏：见 AGENT.md §4（零注册零 Mixin、未就绪完全静默、GlobalContext 只出现在 sender/QueQiaoSink.java）。
6. 构建脚本：资源模板只用 ReplaceTokens（@token@），禁 filesMatching/expand/eachFile；gradle.properties 中文一律 \uXXXX 转义（AGENT.md §3）。
7. 提交信息用中文描述实质变化；一次提交一个主题；涉及文档同步（AGENT.md 结构树 / ARCHITECTURE.md 树与矩阵 / docs/INTEGRATION.md 契约）。
