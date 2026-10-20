package net.yumezawa.queqiao_map_web;

import net.yumezawa.queqiao_map_web.config.MapExtConfig;
import net.yumezawa.queqiao_map_web.event.MapDimensionsEvent;
import net.yumezawa.queqiao_map_web.event.PlayerMoveEvent;
import net.yumezawa.queqiao_map_web.mapstore.BackfillEngine;
import net.yumezawa.queqiao_map_web.mapstore.MapStore;
import net.yumezawa.queqiao_map_web.mapstore.RegionStreamer;
import net.yumezawa.queqiao_map_web.platform.PlatformAdapter;
import net.yumezawa.queqiao_map_web.tile.Palette;
import net.yumezawa.queqiao_map_web.sampler.PositionSampler;
import net.yumezawa.queqiao_map_web.tile.TileQueue;
import net.yumezawa.queqiao_map_web.sender.EventSink;
import net.yumezawa.queqiao_map_web.sender.QueQiaoSink;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 主结构（平台无关）：装配 + 生命周期。三个加载器入口（同名 QueqiaoMapWeb，
 * 各自包内）只做两件事——把加载器事件转交给 PlatformAdapter 实现、调用本类的
 * start / stop。所有业务逻辑在这里与各领域子包里，加载器差异被 PlatformAdapter 抹平。
 *
 * 前置铁律：本 mod 是鹊桥（QueQiao）的附属扩展——鹊桥是唯一发送通道，不存在任何
 * 兜底/直连通道。鹊桥未就绪时整个数据面休眠（零采样、零渲染、零流量），就绪后自动开启。
 *
 * 其余铁律：
 * - enabled=false（默认）时除一条自检日志外零行为、零流量；
 * - 发送失败静默丢弃，绝不打断服务端。
 */
public final class ModLifecycle {

    public static final String MOD_ID = "queqiao_map_web";
    private static final Logger LOG = Logger.getLogger(MOD_ID);

    private static final ModLifecycle INSTANCE = new ModLifecycle();

    private PlatformAdapter adapter;
    private MapExtConfig config;
    private PositionSampler positionSampler;
    private TileQueue tileQueue;
    private Palette palette;
    /** 本地地图库（REQ-LIFE-005）：null = worldSaveRoot 不可得或双开关均关 */
    private MapStore mapStore;
    private BackfillEngine backfillEngine;
    private RegionStreamer regionStreamer;
    private boolean enginesStarted;
    private final AtomicReference<EventSink> sinkRef = new AtomicReference<>();

    private boolean running;
    private boolean bridgeReadyLogged;
    private List<String> lastDimensions = List.of();
    private long lastDimensionCheckMs;
    private long lastPaletteRepublishMs;

    private ModLifecycle() {
    }

    public static ModLifecycle get() {
        return INSTANCE;
    }

    /** 加载器在【服务端启动完成】后调用（鹊桥 GlobalContext 此时应已 init） */
    public synchronized void start(PlatformAdapter adapter, Path configDir) {
        if (running) return;
        this.adapter = adapter;
        this.config = MapExtConfig.load(configDir.resolve(MapExtConfig.SUB_DIR).resolve(MapExtConfig.FILE_NAME));

        if (!config.enabled) {
            LOG.info("[" + MOD_ID + "] 已安装但未启用（config/" + MapExtConfig.SUB_DIR + "/" + MapExtConfig.FILE_NAME
                    + " 中 enabled: false），零行为零流量。");
            return;
        }

        this.palette = new Palette();
        this.positionSampler = new PositionSampler(config);
        // 地图库（REQ-LIFE-005）：平台给出世界存档根且任一引擎开关开启才建；否则整链路为 null
        Path worldRoot = null;
        try {
            worldRoot = adapter.worldSaveRoot();
        } catch (Throwable t) {
            LOG.log(Level.FINE, "worldSaveRoot 获取失败（地图库关闭）", t);
        }
        this.mapStore = (worldRoot != null && (config.backfillEnabled || config.regionStreamEnabled))
                ? new MapStore(worldRoot) : null;
        this.backfillEngine = null;
        this.regionStreamer = null;
        this.enginesStarted = false;
        this.tileQueue = new TileQueue(adapter, config, palette, sinkRef::get, mapStore);
        sinkRef.set(new QueQiaoSink());

        adapter.onTick(this::onServerTick);
        adapter.onChunkDirty((dimension, pos) -> tileQueue.enqueue(dimension, pos));

        running = true;
        lastDimensionCheckMs = 0;
        lastPaletteRepublishMs = System.currentTimeMillis(); // 周期重发计时起点（REQ-PAL-003）
        // 单例跨服务端重启复用：维度清单基线必须清空——新会话（新 sink）里
        // 若与上个会话比对相等，map_dimensions 永远不会重发，Web 端缺清单
        lastDimensions = List.of();
        bridgeReadyLogged = sinkRef.get().isReady();
        LOG.info("[" + MOD_ID + "] 已启用：platform=" + adapter.platformName()
                + " sink=" + sinkName() + " batch=" + config.batchSize);
        if (!bridgeReadyLogged) {
            LOG.warning("未检测到就绪的鹊桥：本 mod 是鹊桥的附属扩展（鹊桥为唯一通道），就绪前保持完全静默。"
                    + "请确认服务端已安装鹊桥；不需要本功能请把 config/" + MapExtConfig.SUB_DIR + "/" + MapExtConfig.FILE_NAME + " 的 enabled 保持 false。");
        }
    }

    /** 加载器在服务端停止时调用 */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        // 停服顺序（REQ-LIFE-005/REQ-MS-008）：先停区域流（保证 map_region 不再晚于 map_tile）、
        // 再停回填、最后排空 tileQueue；adapter.shutdown 由加载器入口先于本方法调用
        if (regionStreamer != null) {
            try {
                regionStreamer.stop();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "停止区域流失败", t);
            }
            regionStreamer = null;
        }
        if (backfillEngine != null) {
            try {
                backfillEngine.stop();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "停止回填引擎失败", t);
            }
            backfillEngine = null;
        }
        mapStore = null;
        try {
            tileQueue.drain();
        } catch (Throwable t) {
            LOG.log(Level.FINE, "停止时排空失败", t);
        }
        LOG.info("[" + MOD_ID + "] 已停用。");
    }

    /** 主线程 tick：鹊桥就绪门 → 位置采样 + 瓦片刷写时机 + 维度清单变化 */
    private void onServerTick(List<PlatformAdapter.PlayerSnapshot> snapshots) {
        long now = System.currentTimeMillis();
        try {
            EventSink sink = sinkRef.get();
            if (sink == null || !sink.isReady()) {
                // 鹊桥前置未满足：零采样、零渲染、零流量（isReady 探针 2s 节流，开销可忽略）
                bridgeReadyLogged = false;
                return;
            }
            if (!bridgeReadyLogged) {
                bridgeReadyLogged = true;
                LOG.info("[" + MOD_ID + "] 鹊桥已就绪，事件流开始。");
            }
            // 首个就绪 tick：启动地图库引擎（各一次，各受自身开关约束，REQ-LIFE-005）
            startMapStoreEngines();

            var players = positionSampler.sample(snapshots, now);
            if (!players.isEmpty()) sink.send(new PlayerMoveEvent(players));

            tileQueue.onTick(now);

            if (now - lastDimensionCheckMs >= config.dimensionCheckIntervalMs) {
                lastDimensionCheckMs = now;
                List<String> dims = adapter.listDimensions();
                if (!dims.equals(lastDimensions)) {
                    lastDimensions = dims;
                    sink.send(new MapDimensionsEvent(dims));
                    // 维度清单变化：唤醒区域流立即复查新维度（不等 30s 周期）
                    if (regionStreamer != null) regionStreamer.requestRecheck();
                }
            }

            // 已发布调色板的周期重发（REQ-PAL-003）：晚接入/断线重连消费端的自愈通道——
            // 同一 id+colors 快照，消费端幂等；palette_republish_interval_ms=0 关闭（REQ-CFG-005）
            if (config.paletteRepublishIntervalMs > 0 && tileQueue.palettePublished()
                    && now - lastPaletteRepublishMs >= config.paletteRepublishIntervalMs) {
                lastPaletteRepublishMs = now;
                tileQueue.republishPalette(sink);
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "tick 处理异常（已吞掉，不影响服务端）", t);
        }
    }

    /** 就绪门后启动回填与区域流（各一次；异常吞 FINE，不影响服务端 tick） */
    private void startMapStoreEngines() {
        if (enginesStarted || mapStore == null) return;
        enginesStarted = true;
        try {
            if (config.backfillEnabled) {
                backfillEngine = new BackfillEngine(mapStore, adapter.worldSaveRoot(), config.backfillMaxChunksPerSec);
                backfillEngine.start();
            }
            if (config.regionStreamEnabled) {
                // 区域重放开关（REQ-MS-009/REQ-CFG-005）：区域流启动前把全库 lastSentXor 归零，
                // 消费端离线期间已「烧掉」的区域本会话限速重放一遍；xor32==0 区域维持跳过
                if (config.regionReplayOnStart) {
                    int replayCount = mapStore.resetLastSentXor();
                    LOG.info("[" + MOD_ID + "] region_replay_on_start=true：已复位 " + replayCount
                            + " 个区域的 lastSentXor，本会话按区域流限速全量重放（一次性自愈，用完建议改回 false）。");
                }
                regionStreamer = new RegionStreamer(mapStore, tileQueue, palette, config, sinkRef::get);
                regionStreamer.start();
            }
        } catch (Throwable t) {
            backfillEngine = null;
            regionStreamer = null;
            LOG.log(Level.FINE, "地图库引擎启动失败（相关功能本次会话降级）", t);
        }
    }

    /** 仅测试可见：地图库组件（null = 未创建；stop 后置 null） */
    MapStore mapStore() {
        return mapStore;
    }

    BackfillEngine backfillEngine() {
        return backfillEngine;
    }

    RegionStreamer regionStreamer() {
        return regionStreamer;
    }

    private String sinkName() {
        EventSink s = sinkRef.get();
        return s == null ? "none" : s.name();
    }

    /** 仅测试可见 */
    static Logger log() {
        return LOG;
    }

}