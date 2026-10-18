package net.yumezawa.queqiao_map_web;

import net.yumezawa.queqiao_map_web.config.CfgMapExt;
import net.yumezawa.queqiao_map_web.event.EvtMapDimensions;
import net.yumezawa.queqiao_map_web.event.EvtPlayerMove;
import net.yumezawa.queqiao_map_web.platform.PlatAdapter;
import net.yumezawa.queqiao_map_web.render.RndPalette;
import net.yumezawa.queqiao_map_web.sampler.SmpPosition;
import net.yumezawa.queqiao_map_web.sampler.SmpTileQueue;
import net.yumezawa.queqiao_map_web.sender.SndEvent;
import net.yumezawa.queqiao_map_web.sender.SndQueQiao;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 主结构（平台无关）：装配 + 生命周期。三个加载器入口（ForgeEntry / FabricEntry /
 * NeoForgeEntry）只做两件事——把加载器事件转交给 PlatAdapter 实现、调用本类的
 * start / stop。所有业务逻辑在这里与 origin 子包里，加载器差异被 PlatAdapter 抹平。
 *
 * 前置铁律：本 mod 是鹊桥（QueQiao）的附属扩展——鹊桥是唯一发送通道，不存在任何
 * 兜底/直连通道。鹊桥未就绪时整个数据面休眠（零采样、零渲染、零流量），就绪后自动开启。
 *
 * 其余铁律：
 * - enabled=false（默认）时除一条自检日志外零行为、零流量；
 * - 发送失败静默丢弃，绝不打断服务端。
 */
public final class QmwLifecycle {

    public static final String MOD_ID = "queqiao_map_web";
    private static final Logger LOG = Logger.getLogger(MOD_ID);

    private static final QmwLifecycle INSTANCE = new QmwLifecycle();

    private PlatAdapter adapter;
    private CfgMapExt config;
    private SmpPosition positionSampler;
    private SmpTileQueue tileQueue;
    private RndPalette palette;
    private final AtomicReference<SndEvent> sinkRef = new AtomicReference<>();

    private boolean running;
    private boolean bridgeReadyLogged;
    private List<String> lastDimensions = List.of();
    private long lastDimensionCheckMs;

    private QmwLifecycle() {
    }

    public static QmwLifecycle get() {
        return INSTANCE;
    }

    /** 加载器在【服务端启动完成】后调用（鹊桥 GlobalContext 此时应已 init） */
    public synchronized void start(PlatAdapter adapter, Path configDir) {
        if (running) return;
        this.adapter = adapter;
        this.config = CfgMapExt.load(configDir.resolve(CfgMapExt.FILE_NAME));

        if (!config.enabled) {
            LOG.info("[" + MOD_ID + "] 已安装但未启用（config/" + CfgMapExt.FILE_NAME
                    + " 中 enabled: false），零行为零流量。");
            return;
        }

        this.palette = new RndPalette();
        this.positionSampler = new SmpPosition(config);
        this.tileQueue = new SmpTileQueue(adapter, config, palette, sinkRef::get);
        sinkRef.set(new SndQueQiao());

        adapter.onTick(this::onServerTick);
        adapter.onChunkDirty((dimension, pos) -> tileQueue.enqueue(dimension, pos));

        running = true;
        lastDimensionCheckMs = 0;
        bridgeReadyLogged = sinkRef.get().isReady();
        LOG.info("[" + MOD_ID + "] 已启用：platform=" + adapter.platformName()
                + " sink=" + sinkName() + " batch=" + config.batchSize);
        if (!bridgeReadyLogged) {
            LOG.warning("未检测到就绪的鹊桥：本 mod 是鹊桥的附属扩展（鹊桥为唯一通道），就绪前保持完全静默。"
                    + "请确认服务端已安装鹊桥；不需要本功能请把 config/" + CfgMapExt.FILE_NAME + " 的 enabled 保持 false。");
        }
    }

    /** 加载器在服务端停止时调用 */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            tileQueue.drain();
        } catch (Throwable t) {
            LOG.log(Level.FINE, "停止时排空失败", t);
        }
        LOG.info("[" + MOD_ID + "] 已停用。");
    }

    /** 主线程 tick：鹊桥就绪门 → 位置采样 + 瓦片刷写时机 + 维度清单变化 */
    private void onServerTick(List<PlatAdapter.PlayerSnapshot> snapshots) {
        long now = System.currentTimeMillis();
        try {
            SndEvent sink = sinkRef.get();
            if (sink == null || !sink.isReady()) {
                // 鹊桥前置未满足：零采样、零渲染、零流量（isReady 探针 2s 节流，开销可忽略）
                bridgeReadyLogged = false;
                return;
            }
            if (!bridgeReadyLogged) {
                bridgeReadyLogged = true;
                LOG.info("[" + MOD_ID + "] 鹊桥已就绪，事件流开始。");
            }

            var players = positionSampler.sample(snapshots, now);
            if (!players.isEmpty()) sink.send(new EvtPlayerMove(players));

            tileQueue.onTick(now);

            if (now - lastDimensionCheckMs >= config.dimensionCheckIntervalMs) {
                lastDimensionCheckMs = now;
                List<String> dims = adapter.listDimensions();
                if (!dims.equals(lastDimensions)) {
                    lastDimensions = dims;
                    sink.send(new EvtMapDimensions(dims));
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "tick 处理异常（已吞掉，不影响服务端）", t);
        }
    }

    private String sinkName() {
        SndEvent s = sinkRef.get();
        return s == null ? "none" : s.name();
    }

    /** 仅测试可见 */
    static Logger log() {
        return LOG;
    }

}