# specs/ — 行为规格（SDD 唯一契约源）

> **specs/ 是本仓库行为规格的唯一契约源**：mod「做什么」以这里为准，代码是规格的实现；
> 上游权威依次为 ARCHITECTURE.md（设计）、AGENT.md §4/§5（发送铁律与协议）、docs/INTEGRATION.md（消费端契约）。
> 文档与规格冲突时以 specs/ 为准并回改文档；规格与源码冲突时先立 issue / 报 lead。

## SDD 流程（四步，操作细节见 CONTRIBUTING.md §3）

1. **规格先行**：新行为先在对应域 `specs/<域>/spec.md` 立/改 REQ（模板见 [TEMPLATE.md](TEMPLATE.md)）→ 评审 → 合并。
2. **测试先行（红）**：按 REQ 在 `common/src/test` 写测试（方法带 `@Tag("REQ-<域>-NNN")`），先失败。
3. **实现（绿）**：实现令测试通过，再重构。
4. **CI 门禁**：测试 + 架构守卫 + jar 冒烟 + spec-coverage 双向映射（workflow 暂移至 [.github/workflows.disabled/](../.github/workflows.disabled/)）全绿才可合并；CI/CD 暂停期间该门禁在本地手动跑，恢复后由 workflow 接管。

## REQ id 规则

- 书写格式：spec.md 内三级标题 `### REQ-<域>-<三位序号> 标题`；域段 = 域目录名的全大写（lifecycle→LIFE、config→CFG、sampler→SAMP、palette→PAL、tile→TILE、sender→SEND、protocol→PROTO、artifact→ART、mapstore→MS）。
- **REQ id 永不复用**：条目废弃后编号留洞，不回填。
- **语义变更必须开新 REQ**：旧 REQ 节标题追加「【已废弃，由 REQ-<域>-NNN 取代】」并保留原位，新 REQ 承载新语义——保证历史测试 @Tag 到规格的映射始终可解析。
- 每条 REQ 的「验证：」行绑定测试类#方法；CI spec-coverage 双向校验：specs 中每个生效 REQ ≥1 个测试 `@Tag`，每个测试 `@Tag` 必须映射到 specs 已定义 REQ。

## 域目录清单

| 域目录 | spec 文件 | REQ 段 | 覆盖内容 |
|---|---|---|---|
| lifecycle | [lifecycle/spec.md](lifecycle/spec.md) | REQ-LIFE-001…005 | 装配与生命周期、静默门、停服顺序、异常吞噬 |
| config | [config/spec.md](config/spec.md) | REQ-CFG-001…005 | queqiao_map_web.yml 生成 / 极简解析 / 非法值回退 |
| sampler | [sampler/spec.md](sampler/spec.md) | REQ-SAMP-001…004 | 位移阈值、静止心跳、移动限速、离线清理 |
| palette | [palette/spec.md](palette/spec.md) | REQ-PAL-001…003 | 256 色上限与下标 0、先发后冻结 |
| tile | [tile/spec.md](tile/spec.md) | REQ-TILE-001…006 | 8×8 合并、zlib-pal8+sha1、去重、队列封顶、每 tick 渲染上限 |
| mapstore | [mapstore/spec.md](mapstore/spec.md) | REQ-MS-001…009 | mca 解码、本地二维库、回填、区域流 |
| sender | [sender/spec.md](sender/spec.md) | REQ-SEND-001…002 | GlobalContext 单文件守卫、未就绪零发送 |
| protocol | [protocol/spec.md](protocol/spec.md) | REQ-PROTO-001…005 | sub_type 固定、DTO 字段名、registry key、只加不改 |
| artifact | [artifact/spec.md](artifact/spec.md) | REQ-ART-001…004 | 模板 token 渲染、jar 内容、manifest、产物命名 |

新域 = 新建目录 + `spec.md`（复制 [TEMPLATE.md](TEMPLATE.md) 起步），域名 kebab-case 小写，并在上表登记。
