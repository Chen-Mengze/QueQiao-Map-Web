# sender 域 — 唯一发送通道

> 域范围：`EventSink` 抽象与 `QueQiaoSink` 实现（鹊桥是唯一通道）。权威来源：AGENT.md §4.2/§4.4（唯一通道、GlobalContext 单文件）、docs/INTEGRATION.md §0。

### REQ-SEND-001 GlobalContext 只允许出现在 sender/QueQiaoSink.java（架构守卫）

**SHALL**：全工程所有源码集（common/fabric/forge/neoforge 的 main 与 test）中，import 或以任何代码形式引用 `com.github.theword.queqiao.tool.GlobalContext` 的文件有且仅有 `common/src/main/java/net/yumezawa/queqiao_map_web/sender/QueQiaoSink.java`。架构守卫按 import 语句与代码引用检查（注释/Javadoc 中的提及不算违规——现有 4 处注释性提及见 ModLifecycle/EventSink/两加载器入口）。

**场景**：
- 给定新增任一文件 import GlobalContext，当守卫执行，那么该文件被列出、CI 失败。
- 给定仅 QueQiaoSink.java 有 `import com.github.theword.queqiao.tool.GlobalContext;`，那么守卫通过。

**验证**：`GlobalContextIsolationTest#globalContextOnlyReferencedInSender`（测试内源码扫描 + ci.yml 架构守卫同口径）

### REQ-SEND-002 未就绪前零发送（唯一通道、无降级直连）

**SHALL**：任何事件到达鹊桥前必须通过 `EventSink.isReady()`（QueQiaoSink 探针 `GlobalContext.getConfig()!=null`）；未就绪时 `send()` 直接返回 false 且零鹊桥调用。代码中不存在第二发送路径：无降级通道、无直连 WebSocket、无可配置通道切换（配置里没有也不允许有 sink 类型键）。`GlobalContext.sendEvent` 抛任何 Throwable 亦返回 false（配合 REQ-LIFE-004）。

**场景**：
- 给定鹊桥类在 classpath 但未 init，当调用 QueQiaoSink.send(event)，那么返回 false，事件未达任何通道。
- 给定整仓库扫描发送侧出口，那么 `sendEvent` 调用点仅存在于 QueQiaoSink.java。

**验证**：`QueQiaoSinkProbeTest#probeIsFalseBeforeQueqiaoReady`、`QueQiaoSinkProbeTest#sendReturnsFalseWhenChannelThrows`
