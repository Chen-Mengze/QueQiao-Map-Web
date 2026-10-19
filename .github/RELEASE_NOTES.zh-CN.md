## QueQiao Map Web v0.0.1

鹊桥（QueQiao）附属扩展：把服务端区块顶层方块地图瓦片与玩家位置，经鹊桥事件通道推送到 Web 地图。

### 功能一览

- `player_move` —— 在线玩家 uuid / 坐标 / 朝向 / 维度，离线自动清理：移动中约 2.5Hz 上报，静止心跳约 0.67Hz，位移阈值 1.5 格
- `map_tile` —— 区块顶层方块瓦片：调色板 8-bit 索引 + zlib + Base64（格式 `zlib-pal8`），sha1 校验；脏区块驱动，8×8 区域合并
- `map_palette` —— 共享 256 色调色板，每次会话仅发一次
- `map_dimensions` —— 服务器维度清单，连接后一次、清单变化重发

### 兼容性保护

- 配置文件 `config/QueQiao/queqiao_map_web.yml` 中 `enabled` 默认 `false`：不开启则零行为、零流量，仅一条自检日志
- 未检测到鹊桥就绪前完全静默（零采样、零渲染、零流量）；发送失败静默丢弃，绝不打断服务端

### 本次构建

- 加载器：Forge；实测 Minecraft 版本：1.20.1
- 前置要求：服务端已安装鹊桥 QueQiao Tool（鹊桥是唯一通道，未安装时本 mod 整体不启动）
