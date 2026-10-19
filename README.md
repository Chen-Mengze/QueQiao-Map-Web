# QueQiao Map Web

Minecraft 服务端 mod：把「顶层方块地图瓦片 + 玩家位置」以事件形式经 **鹊桥（QueQiao）事件桥**推给 Web 端，实时拼出服务器地图。

- **零 Mixin、不 fork、协议侧零注册**：只调鹊桥公开 API 与加载器标准事件。
- **默认零行为**：`enabled: false` 出厂；鹊桥未就绪完全静默、零流量，无降级通道。
- **只做服务端与协议侧**：接收端（Web 地图、聊天机器人、自研消费端）按 [docs/INTEGRATION.md](docs/INTEGRATION.md) 独立适配。

> 本仓库整体由 AI 制作：全部代码与文档骨架由 AI（Claude）依据所有者的架构设计编写并通过编译验证；协议与 API 均核对自鹊桥 main 分支源码，非推断。MIT License。

## 特性

| 事件 `sub_type` | 内容 | 节流 |
|---|---|---|
| `player_move` | 在线玩家 uuid / 坐标 / 朝向 / 维度，离线自动清理 | 移动 ~2.5Hz，静止心跳 ~0.67Hz，阈值 1.5 格 |
| `map_tile` | 区块顶层方块瓦片：调色板 8-bit 索引 + zlib + Base64（format `zlib-pal8`），sha1 校验 | 脏区块驱动，8×8 区域合并 |
| `map_region` | 二维区域全量/续传流：mca→本地二维库→xor 校验增量（format `zlib-pal8`，单区域 512×512 像素） | 区域粒度默认 64 KB/s 节流；xor 未变即跳过（重启/重连近零流量） |
| `map_palette` | 共享 256 色调色板，发出后冻结 | 每次会话仅一次 |
| `map_dimensions` | 服务器维度清单 | 连接后一次 + 清单变化重发 |

## 前置与快速开始

1. 服务端已安装**鹊桥 QueQiao mod（≥0.5.0）**：本 mod 在三个加载器的元数据里把它声明为**强制依赖**，缺少时加载器直接拒绝加载并抛异常（鹊桥是唯一通道，无降级路径）。
2. 将 `queqiao_map_web-<加载器>-<版本>+mc<MC版本>.jar`（文件名自带适配加载器标注，如 `queqiao_map_web-forge-0.0.1+mc1.20.1.jar`）放入服务端 `mods/`。
3. 编辑服务端 `config/QueQiao/queqiao_map_web.yml`（首次启动自动生成）开启：

```yml
enabled: true
```

## 加载器与版本支持

版本支持是数据不是代码：每个分支的全部受支持 MC 版本列在 `<loader>/versions/` 下，每个版本目录只有一个 `gradle.properties`（MC/加载器版本、映射、Java 版本的唯一真相）；是否已接入编译以 [settings.gradle](settings.gradle) 的 `include` 为准。

| 加载器 | 版本清单（唯一真相） | 编译接入 |
|---|---|---|
| Forge | `forge/versions/` | 已接入，产出 mod jar（reobf 完成） |
| Fabric | `fabric/versions/` | 未接入，骨架已就位（M2/M3） |
| NeoForge | `neoforge/versions/` | 未接入，骨架已就位（M4） |

当前具体支持哪些 MC 版本：直接查看上述版本目录列表，无需改任何业务代码。

## 项目结构

```
queqiao_map_web/
├── common/        主结构（多加载器标准目录名）：采样 / 瓦片队列 / 调色板 / 编码 / 二维地图库 / 发送，零 net.minecraft.* 引用
├── forge/         Forge 分支：入口 QueqiaoMapWeb + ForgePlatformAdapter（分支内唯一接触 MC 类处）
│   └── versions/  每 MC 版本一个目录，只放 gradle.properties（版本/映射/Java 唯一真相）
├── fabric/        Fabric 分支（同构，M2）
└── neoforge/      NeoForge 分支（同构，M4）
```

数据流：服务端事件 → `PlatformAdapter`（平台契约）→ `common` 主结构 → `QueQiaoSink` → 鹊桥事件桥 → 第三方消费端。

加一个 MC 版本 = 加一个版本目录；换一个加载器 = 加一个适配器；鹊桥换 API = 换一个 `EventSink`。业务代码不因版本变化。

## 构建

仓库自带 Gradle Wrapper，本机只需 JDK 17+ 启动环境；实际编译用的 Java 版本由 Gradle toolchain 按各版本目录的 `java_version` 自动匹配，无需手动指定 `JAVA_HOME`。

编译期鹊桥接口从 **GitHub Packages** 联网拉取（`com.github.theword.queqiao:queqiao-tool`，版本见 `gradle.properties` 的 `queqiao_tool_version`）。公开包也要求认证，一次性配置任选其一：

- 环境变量（与鹊桥官方构建同名）：`setx GH_USERNAME "<GitHub用户名>"`、`setx PACKAGE_READ_ONLY_TOKEN "<PAT>"`（PAT 只勾 `read:packages`；重开终端生效）；
- 或 `~/.gradle/gradle.properties` 写 `gpr.user=<GitHub用户名>`、`gpr.key=<PAT>`。

CI 零配置：workflow 已注入 `GITHUB_TOKEN`。

```bat
:: Windows
gradlew.bat :common:build :forge:build
```

```sh
# Linux / macOS
./gradlew :common:build :forge:build
```

构建指定 MC 版本加 `-PmcTarget=<MC版本>`（对应 `<loader>/versions/<MC版本>/`；省略时用 `forge/build.gradle` 声明的默认值）。产物统一位于 `<loader>/build/libs/<mod_id>-<加载器>-<mod版本>+mc<MC版本>.jar`（文件名自带适配加载器标注）。Gradle 发行与依赖仓库均已配置国内镜像（腾讯 gradle 发行 / 阿里云插件 / forge 官方 maven），本地直连即可，无需代理。

正式发布走 CI/CD：推送 `v*` 标签触发 GitHub Actions，自动构建 `forge/versions/` 下全部 MC 版本并发布 GitHub Release（见 [.github/workflows/release.yml](.github/workflows/release.yml)）。

本地测试：`gradlew :common:test`（JUnit5，位于 `common/src/test`；每个测试方法带 `@Tag("REQ-XXX-NNN")` 绑定 [`specs/`](specs/README.md) 中的行为 REQ——`specs/` 是行为规格的唯一契约源）。PR 的 CI 门禁 = 测试 + 架构守卫 + jar 冒烟 + 规格覆盖双向校验（见 [.github/workflows/ci.yml](.github/workflows/ci.yml)）。

## 文档

- [ARCHITECTURE.md](ARCHITECTURE.md) —— 设计权威：事实基础、协议契约、带宽实测、风险与里程碑。
- [docs/INTEGRATION.md](docs/INTEGRATION.md) —— 第三方接入：JSON 契约、字段语义、zlib-pal8 解码步骤、xor 断点续传、§9 端到端消费端教程。
- [docs/DESIGN_MAPSTORE.md](docs/DESIGN_MAPSTORE.md) —— 设计记录：地图数据路径重设计（mca → 本地二维地图库 → 区域流全量/续传）。
- [AGENT.md](AGENT.md) —— 开发引导（面向 AI 与人类）：发送铁律、平台 API 矩阵、代码风格。
- [CONTRIBUTING.md](CONTRIBUTING.md) —— 命名标准与 PR 验收清单。

## License

MIT。欢迎直接拿走用；缺什么、踩了坑，开 Issue 或 PR——AI 维护的项目对「需求太怪」免疫，尽管提。
