# QueQiao-Map-Web

一个 Minecraft 服务端 mod：把「顶层方块地图 + 玩家位置」以事件形式经 **鹊桥（QueQiao）事件桥**
推给 Web 端，实时拼出一张能看的服务器地图。

> **本仓库整体由 AI 制作**：全部代码与文档骨架由 AI（Claude）依据所有者的架构设计编写，并通过编译验证；
> 协议与 API 均核对自鹊桥 main 分支源码，非推断。

## 起点与态度

起点是一份很私人的需要——owner 想给自己的服务器配一张网页上实时更新的地图。
做成通用模组纯属顺手：鹊桥的事件通道（`GlobalContext.sendEvent`，事件名只是构造函数字符串、无白名单）
天然能捎带地图数据，于是有了这个**零 Mixin、不 fork、协议侧零注册**的独立方案。

如果你也有同样的需要，欢迎直接拿走用（MIT License）；
觉得哪里不好用、缺什么、踩了坑，欢迎开 Issue 或 PR——AI 维护的项目对"需求太怪"免疫，尽管提。

## 它做什么

- **玩家位置**：按移动阈值抽样的在线玩家坐标，批量上报（`player_move`），离线自动清理。
- **区块地图**：主线程采样每个区块 16×16 顶层方块颜色，调色板索引 8-bit + zlib + Base64
  打包成瓦片（`map_tile`，format `zlib-pal8`）；脏区块驱动，按 8×8 区域合并批处理。
- **协议**（4 个事件，全部 `extends` 鹊桥 `BaseEvent`，零注册）：

| sub_type | 载荷 |
|---|---|
| `player_move` | players[]：uuid / nickname / x / y / z / yaw / dimension |
| `map_palette` | id + colors[]（≤256，先于任何瓦片发出后即冻结） |
| `map_tile` | dimension + tiles[]：cx / cz / size / format / sha1 / b64 |
| `map_dimensions` | dimensions[]（连接后一次 + 清单变化重发） |

- **前置鹊桥**：本 mod 是鹊桥的附属扩展，唯一通道是鹊桥事件桥（`SndQueQiao`）；
  鹊桥未就绪前完全静默、零流量，不存在降级通道。

本仓库是**服务端 mod 与协议侧**，不内置接收端：数据结构与 API 对第三方开放——
JSON 协议契约 + [docs/INTEGRATION.md](docs/INTEGRATION.md)（接入说明与解析解释），
Web 地图、聊天机器人或自研消费端（含 owner 自有项目）照文档独立适配即可。

## 代码框架

```
服务端事件 → PlatAdapter（平台契约） → origin 主结构（抽样 / 瓦片队列 / 调色板 / 打包） → SndQueQiao → 鹊桥事件桥 → 第三方消费端（Web 地图等）
```

- `origin/`：主结构，**零 `net.minecraft.*` 引用**——事件定义、抽样、打包、发送全部平台无关。
- `forge|fabric|neoforge/origin/`：各加载器入口 + 一个平台适配器，是该分支唯一接触 MC 类的地方。
- `<loader>-<ver>/`：每个 MC 版本一个目录，**只放 gradle.properties**（MC 版本 / 映射 / Java 版本唯一真相）。

加一个 MC 版本 = 加一个版本目录；换一个加载器 = 加一个适配器；鹊桥换 API = 换一个 SndEvent。
业务代码不因版本变化。

| 加载器 | 版本 | 状态 |
|---|---|---|
| Forge | 1.20.1 | ✅ 编译通过并产出 mod jar（M1，reobf 完成） |
| Fabric | 1.20.1 – 1.21.11 | 骨架已就位，待接入编译（M2/M3） |
| NeoForge | 1.21.1 / 26.1 | 骨架已就位（M4） |

## 构建

Gradle 已配置国内镜像（腾讯 gradle 发行版 / 阿里云插件 / forge 官方 maven），无需代理即可构建 Forge 分支：

```
JAVA_HOME=<JDK 17>  ./gradlew.bat :origin:build :forge:origin:build
```

## 文档

- [ARCHITECTURE.md](ARCHITECTURE.md) —— 设计权威：事实基础、协议契约、带宽实测、风险与里程碑。
- [AGENT.md](AGENT.md) —— 开发引导（面向 AI 与人类）：发送铁律、平台 API 矩阵、代码风格。
- [docs/INTEGRATION.md](docs/INTEGRATION.md) —— 第三方接入说明：JSON 契约、字段语义、zlib-pal8 解码步骤。

