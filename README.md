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
| `map_palette` | 共享 256 色调色板，发出后冻结 | 每次会话仅一次 |
| `map_dimensions` | 服务器维度清单 | 连接后一次 + 清单变化重发 |

## 前置与快速开始

1. 服务端已安装鹊桥 QueQiao Tool（本 mod 是其附属扩展，鹊桥是唯一通道；未安装时本 mod 整体不启动）。
2. 将 `queqiao_map_web-<加载器>-<版本>+mc<MC版本>.jar`（文件名自带适配加载器标注，如 `queqiao_map_web-forge-0.0.1+mc1.20.1.jar`）放入服务端 `mods/`。
3. 编辑服务端 `config/queqiao-mapext.yml` 开启：

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
├── common/        主结构（多加载器标准目录名）：采样 / 瓦片队列 / 调色板 / 编码 / 发送，零 net.minecraft.* 引用
├── forge/         Forge 分支：入口 QueqiaoMapWeb + ForgePlatformAdapter（分支内唯一接触 MC 类处）
│   └── versions/  每 MC 版本一个目录，只放 gradle.properties（版本/映射/Java 唯一真相）
├── fabric/        Fabric 分支（同构，M2）
├── neoforge/      NeoForge 分支（同构，M4）
└── libs/          compileOnly 鹊桥接口 jar（运行期由鹊桥本体提供，绝不 shade）
```

数据流：服务端事件 → `PlatformAdapter`（平台契约）→ `common` 主结构 → `QueQiaoSink` → 鹊桥事件桥 → 第三方消费端。

加一个 MC 版本 = 加一个版本目录；换一个加载器 = 加一个适配器；鹊桥换 API = 换一个 `EventSink`。业务代码不因版本变化。

## 构建

仓库自带 Gradle Wrapper，本机只需 JDK 17+ 启动环境；实际编译用的 Java 版本由 Gradle toolchain 按各版本目录的 `java_version` 自动匹配，无需手动指定 `JAVA_HOME`。

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

## 文档

- [ARCHITECTURE.md](ARCHITECTURE.md) —— 设计权威：事实基础、协议契约、带宽实测、风险与里程碑。
- [docs/INTEGRATION.md](docs/INTEGRATION.md) —— 第三方接入：JSON 契约、字段语义、zlib-pal8 解码步骤。
- [AGENT.md](AGENT.md) —— 开发引导（面向 AI 与人类）：发送铁律、平台 API 矩阵、代码风格。
- [CONTRIBUTING.md](CONTRIBUTING.md) —— 命名标准与 PR 验收清单。

## License

MIT。欢迎直接拿走用；缺什么、踩了坑，开 Issue 或 PR——AI 维护的项目对「需求太怪」免疫，尽管提。
