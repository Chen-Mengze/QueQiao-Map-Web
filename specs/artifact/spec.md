# artifact 域 — 构建产物契约

> 域范围：加载器 mod jar 的元数据渲染、内容组成、manifest 与文件命名（现仅 forge 已接入编译，fabric/neoforge 接入后同样适用）。权威来源：AGENT.md §3（ReplaceTokens、不 shade）、forge/build.gradle（命名/manifest/打包）。

### REQ-ART-001 mods.toml/pack.mcmeta token 全渲染（无 @token@ 残留）

**SHALL**：jar 内 `META-INF/mods.toml` 与 `pack.mcmeta` 的全部模板 token 必须被 processResources 的 ReplaceTokens 渲染为 gradle.properties 实值，渲染后文本中不允许出现任何 `@token@` 形态残留；token 集：mod_id、mod_version、mod_name、mod_license、mod_authors、mod_description、loader_version_range、forge_version_range、minecraft_version_range、pack_format_number。模板实现只用 from-closure ReplaceTokens filter（禁 filesMatching/expand/eachFile，AGENT.md §3）。

**场景**：
- 给定 `:forge:build` 产物，当解压读取 META-INF/mods.toml，那么 modId 字段值 = mod_id（queqiao_map_web），全文无 `@[a-z_]+@` 匹配。
- 给定 pack.mcmeta，那么 pack_format 数字 = 版本覆盖层 `pack_format_number`。

**验证**：`ArtifactSmokeTest#tokensFullyRendered`（CI jar 冒烟步骤，[.github/workflows/ci.yml](../../.github/workflows/ci.yml)：unzip + grep 断言）

### REQ-ART-002 jar 含 common 类、绝不含 com.github.theword（不 shade）

**SHALL**：mod jar 必须并入 `:common` 主源集全部产物（`net/yumezawa/queqiao_map_web/**` 完整类树），且 jar 条目列表中 `com/github/theword/**` 必须为 0（queqiao-tool 为 compileOnly，运行期由鹊桥本体提供，绝不 shade/relocate）。

**场景**：
- 给定 `:forge:build` 产物清单，那么含 `net/yumezawa/queqiao_map_web/sender/QueQiaoSink.class`（及 ModLifecycle 等全部 common 类），不含任何 `com/github/theword/` 条目。

**验证**：`ArtifactSmokeTest#bundlesCommonNoBridgeClasses`（CI jar 冒烟步骤）

### REQ-ART-003 manifest Minecraft-Mod-ID=mod_id

**SHALL**：jar 的 `META-INF/MANIFEST.MF` 必须含 `Minecraft-Mod: true`、`Minecraft-Mod-ID: <mod_id>`（= queqiao_map_web，与包名、gradle.properties、mods.toml 一致）、`Minecraft-Mod-Version: <mod版本>+mc<MC版本>`。

**场景**：
- 给定产物 MANIFEST.MF，那么三个属性存在且 Minecraft-Mod-ID 与 gradle.properties 的 mod_id 逐字符相等。

**验证**：`ArtifactSmokeTest#manifestModIdMatches`（CI jar 冒烟步骤）

### REQ-ART-004 产物命名 <modid>-<加载器>-<mod版本>+mc<MC版本>.jar

**SHALL**：`:<loader>:build` 产物文件名必须为 `<mod_id>-<加载器名>-<mod_version>+mc<minecraft_version>.jar`——加载器名 = 模块名（forge/fabric/neoforge；archivesBaseName = `mod_id-loaderName`，version = `mod_version+mc<覆盖层 minecraft_version>`）；位于 `<loader>/build/libs/`。例：`queqiao_map_web-forge-0.0.1+mc1.20.1.jar`。

**场景**：
- 给定 `:forge:build -PmcTarget=1.20.1` 完成，那么 `forge/build/libs/` 下 jar 名匹配 `^queqiao_map_web-forge-.+\+mc1\.20\.1\.jar$`。

**验证**：`ArtifactSmokeTest#jarFileNamePattern`（CI jar 冒烟步骤）
