# CONTRIBUTING.md — PR 规范

本仓库整体由 AI 制作（见 README），但任何 PR（含 owner 自用分支）都按本节验收。

## 1. 类命名标准（三段式，PR 必检）

顶层类名 = **上级目录简写 + 实现方式 + 功能**（PascalCase 连写，不用下划线/破折号）。

| 上级目录 | 简写 | 例 |
|---|---|---|
| 根包 queqiao_map_web | Qmw | QmwLifecycle（装配生命周期） |
| config/ | Cfg | CfgMapExt |
| event/ | Evt | EvtPlayerMove / EvtMapTile / EvtMapPalette / EvtMapDimensions |
| model/ | Mdl | MdlPlayerPos / MdlTilePayload |
| platform/ | Plat | PlatAdapter（接口）；实现 = Plat + 加载器 + Adapter：PlatForgeAdapter |
| render/ | Rnd | RndPalette / RndTopBlock |
| sampler/ | Smp | SmpPosition / SmpTileQueue |
| sender/ | Snd | SndEvent（接口）/ SndQueQiao |
| 各加载器分支根 | Forge / Fabric / NeoForge | 入口类 = <加载器>Entry（ForgeEntry…） |

细则：

- 「实现方式」段只在实现本身是区分维度时出现（PlatForgeAdapter / PlatFabricAdapter）；目录已唯一确定职责时不重复堆词（EventSink 语义即"事件发送"→ SndEvent）。
- 嵌套/支撑类型跟随宿主类，不加目录前缀（PlayerSnapshot、ChunkPos、ChunkDirtyListener、RndTopBlock.Sample、SmpTileQueue.Captured/Entry/Last）。
- 新目录：选定一个新的两三个字母简写并登记到上表；新加载器：入口 <加载器>Entry、适配器 Plat<加载器>Adapter。
- 类名改动不影响协议：JSON 只由 BaseEvent 构造器字符串（sub_type）与字段名决定，两者与类名无关；但构造器字符串是协议本体，**永不随重命名改动**。

历史映射（M1 重命名 2026-10，旧 → 新）：MapWeb→QmwLifecycle；MapExtConfig→CfgMapExt；PlatformAdapter→PlatAdapter；PlayerMoveEvent→EvtPlayerMove；MapTileEvent→EvtMapTile；MapPaletteEvent→EvtMapPalette；MapDimensionsEvent→EvtMapDimensions；PlayerPosModel→MdlPlayerPos；TilePayloadModel→MdlTilePayload；Palette→RndPalette；TopBlockRenderer→RndTopBlock；PositionSampler→SmpPosition；TileQueue→SmpTileQueue；EventSink→SndEvent；QueQiaoSink→SndQueQiao；MapWebForge→ForgeEntry；MapWebFabric→FabricEntry；MapWebNeoForge→NeoForgeEntry；ForgePlatformAdapter→PlatForgeAdapter；FabricPlatformAdapter→PlatFabricAdapter；NeoForgePlatformAdapter→PlatNeoForgeAdapter。

## 2. PR 验收清单

1. 构建绿：set JAVA_HOME=C:\Program Files\BellSoft\LibericaJDK-17-Full && gradlew :origin:build :forge:origin:build。
2. origin 改动跑 javac 秒级自检（命令见 AGENT.md §3），不引入 net.minecraft.* 与第三方 import。
3. 新类名带目录前缀（本文件 §1 表），旧类名零残留（rg \b旧名\b）。
4. 协议铁律：sub_type 与既有字段语义不变、字段只加不改（docs/INTEGRATION.md §7）；DTO 不手写鹊桥自动字段。
5. 发送铁律不破坏：见 AGENT.md §4（零注册零 Mixin、未就绪完全静默、GlobalContext 只出现在 sender/SndQueQiao.java）。
6. 构建脚本：资源模板只用 ReplaceTokens（@token@），禁 filesMatching/expand/eachFile；gradle.properties 中文一律 \uXXXX 转义（AGENT.md §3）。
7. 提交信息用中文描述实质变化；一次提交一个主题；涉及文档同步（AGENT.md 结构树 / ARCHITECTURE.md 树与矩阵 / docs/INTEGRATION.md 契约）。
