package net.yumezawa.queqiao_map_web;

import net.yumezawa.queqiao_map_web.config.MapExtConfig;
import net.yumezawa.queqiao_map_web.event.MapDimensionsEvent;
import net.yumezawa.queqiao_map_web.event.PlayerMoveEvent;
import net.yumezawa.queqiao_map_web.platform.PlatformAdapter;
import net.yumezawa.queqiao_map_web.render.Palette;
import net.yumezawa.queqiao_map_web.sampler.PositionSampler;
import net.yumezawa.queqiao_map_web.sampler.TileQueue;
import net.yumezawa.queqiao_map_web.sender.EventSink;
import net.yumezawa.queqiao_map_web.sender.QueQiaoSink;
import net.yumezawa.queqiao_map_web.sender.StandaloneSink;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 主结构（平台无关）：装配 + 生命周期。三个加载器入口（MapWebForge / MapWebFabric /
 * MapWebNeoForge）只做两件事——把加载器事件转交给 PlatformAdapter 实现、调用本类的
 * start / stop。所有业务逻辑在这里与 origin 子包里，加载器差异被 PlatformAdapter 抹平。
 *
 * 铁律：
 * - enabled=false（默认）时除一条自检日志外零行为、零流量；
 * - 发送只发生在鹊桥 GlobalContext 就绪之后（start 在 ServerStarted 之后调用，
 *   QueQiaoSink.isReady 再做二次探针）；
 * - 发送失败静默丢弃，绝不打断服务端。
 */
public final class MapWeb {

    public static final String MOD_ID = "queqiao_map_web";
    private static final Logger LOG = Logger.getLogger(MOD_ID);

    private static final MapWeb INSTANCE = new MapWeb();

    private PlatformAdapter adapter;
    private MapExtConfig config;
    private PositionSampler positionSampler;
    private TileQueue tileQueue;
    private Palette palette;
    private final AtomicReference<EventSink> sinkRef = new AtomicReference<>();

    private boolean running;
    private boolean degradedWarned;
    private List<String> lastDimensions = List.of();
    private long lastDimensionCheckMs;

    private MapWeb() {
    }

    public static MapWeb get() {
        return INSTANCE;
    }

    /** 加载器在【服务端启动完成】后调用（鹊桥 GlobalContext 此时已 init） */
    public synchronized void start(PlatformAdapter adapter, Path configDir) {
        if (running) return;
        this.adapter = adapter;
        this.config = MapExtConfig.load(configDir.resolve(MapExtConfig.FILE_NAME));

        if (!config.enabled) {
            LOG.info("[" + MOD_ID + "] 已安装但未启用（config/" + MapExtConfig.FILE_NAME
                    + " 中 enabled: false），零行为零流量。");
            return;
        }

        this.palette = new Palette();
        this.positionSampler = new PositionSampler(config);
        this.tileQueue = new TileQueue(adapter, config, palette, sinkRef::get);
        selectSink();

        adapter.onTick(this::onServerTick);
        adapter.onChunkDirty((dimension, pos) -> tileQueue.enqueue(dimension, pos));

        running = true;
        lastDimensionCheckMs = 0;
        LOG.info("[" + MOD_ID + "] 已启用：platform=" + adapter.platformName()
                + " sink=" + sinkName() + " batch=" + config.batchSize);
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

    /** 主线程 tick：位置采样 + 瓦片刷写时机 + 维度清单变化 */
    private void onServerTick(List<PlatformAdapter.PlayerSnapshot> snapshots) {
        long now = System.currentTimeMillis();
        try {
            maybeSwapSink(now);

            var players = positionSampler.sample(snapshots, now);
            if (!players.isEmpty()) sinkRef.get().send(new PlayerMoveEvent(players));

            tileQueue.onTick(now);

            if (now - lastDimensionCheckMs >= config.dimensionCheckIntervalMs) {
                lastDimensionCheckMs = now;
                List<String> dims = adapter.listDimensions();
                if (!dims.equals(lastDimensions)) {
                    lastDimensions = dims;
                    sinkRef.get().send(new MapDimensionsEvent(dims));
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "tick 处理异常（已吞掉，不影响服务端）", t);
        }
    }

    /** sink 选择：queqiao 不可用且配了 standalone_ws_url 时降级 */
    private void selectSink() {
        EventSink sink;
        if (MapExtConfig.SINK_STANDALONE.equalsIgnoreCase(config.sink)) {
            sink = new StandaloneSink(config.standaloneWsUrl);
        } else {
            sink = new QueQiaoSink();
        }
        sinkRef.set(sink);
    }

    /** 运行期探针：鹊桥迟迟不就绪且配了 WS 地址 → 一次性降级 */
    private void maybeSwapSink(long now) {
        EventSink current = sinkRef.get();
        if (current.isReady()) return;
        if (current instanceof QueQiaoSink && !config.standaloneWsUrl.isEmpty() && !degradedWarned) {
            degradedWarned = true;
            LOG.warning("鹊桥通道未就绪，降级到 standalone: " + config.standaloneWsUrl);
            sinkRef.set(new StandaloneSink(config.standaloneWsUrl));
        }
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
