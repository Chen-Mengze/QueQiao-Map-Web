package net.yumezawa.queqiao_map_web.tile;

import net.yumezawa.queqiao_map_web.tile.TopBlockRenderer.Sample;

import net.yumezawa.queqiao_map_web.config.MapExtConfig;
import net.yumezawa.queqiao_map_web.event.MapPaletteEvent;
import net.yumezawa.queqiao_map_web.event.MapTileEvent;
import net.yumezawa.queqiao_map_web.mapstore.MapStore;
import net.yumezawa.queqiao_map_web.model.TilePayloadModel;
import net.yumezawa.queqiao_map_web.platform.PlatformAdapter;
import net.yumezawa.queqiao_map_web.platform.PlatformAdapter.ChunkPos;
import net.yumezawa.queqiao_map_web.sender.EventSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 脏区块去重 + 双触发刷写：攒够 batchSize（默认 64 = 8x8）立即刷；
 * 距上次刷写超过 flushIntervalMs（默认 1000）强制刷。
 * 线程纪律：主线程只做入队与 renderChunk 采样（每 tick 硬上限）；
 * 量化/压缩/发送只在 runAsync 后台线程跑——后台线程绝不触碰 Level/Chunk 对象。
 * MapPaletteEvent 必须先于任何 MapTileEvent（发送顺序）：首次刷写先打包量化
 * （pack 把颜色注册进调色板），再发调色板快照并冻结，最后发瓦片；调色板发送失败则本批退回队列。
 * 调色板快照经 publishPalette 首发一次后内容冻结；晚接入消费者的自愈靠 republishPalette 按周期重发同一快照（消费端对同 id+colors 幂等）。
 *
 * 线程安全：pending / flushing / paletteSent / capWarned 跨线程共享——
 * 主线程（enqueue / onTick / drain）与后台 flush 线程（发送失败后的 requeue、调色板标记）
 * 一律在 lock 上同步；renderChunk 采样在锁外执行以缩短持锁时间。
 */
public final class TileQueue {

    private static final Logger LOG = Logger.getLogger("queqiao_map_web.tile");
    /** 同一 8x8 区域脏块达到该数量才按 size=8 打包，否则逐块 size=1 */
    private static final int REGION_MERGE_MIN = 4;

    private static final class Entry {
        final String dimension;
        final int cx, cz;

        Entry(String dimension, int cx, int cz) {
            this.dimension = dimension;
            this.cx = cx;
            this.cz = cz;
        }

        String key() {
            return dimension + '@' + cx + ',' + cz;
        }
    }

    /** 主线程采样后交给后台的载荷：维度 + 已采到的 RGB24 */
    private static final class Captured {
        final String dimension;
        final Sample sample;

        Captured(String dimension, Sample sample) {
            this.dimension = dimension;
            this.sample = sample;
        }
    }

    private final PlatformAdapter adapter;
    private final MapExtConfig config;
    private final Palette palette;
    private final Supplier<EventSink> sinkRef;
    /** 本地地图库（REQ-TILE-006）：null = 平台未提供 worldSaveRoot，跳过写穿 */
    private final MapStore store;

    private final Object lock = new Object();
    private final LinkedHashMap<String, Entry> pending = new LinkedHashMap<>();
    private long lastFlushMs = System.currentTimeMillis();
    private boolean flushing;
    private boolean paletteSent;
    private boolean capWarned;

    public TileQueue(PlatformAdapter adapter, MapExtConfig config, Palette palette, Supplier<EventSink> sinkRef, MapStore store) {
        this.adapter = adapter;
        this.config = config;
        this.palette = palette;
        this.sinkRef = sinkRef;
        this.store = store;
    }

    /**
     * 首发调色板快照并冻结（顺序铁律：palette 先于任何 map_tile/map_region）：首发只发一次，
     * 此后的周期重发走 republishPalette。
     * 已发过直接返回 true；发送失败返回 false 且不置位（下次再试）。
     * flush 内部调用；RegionStreamer（后台线程）发任何区域前也必须先过本门。
     */
    public boolean publishPalette(EventSink sink) {
        synchronized (lock) {
            if (paletteSent) return true;
            // 鹊桥 send 只是本地入队（快操作），锁内发送以保证调色板先于任何瓦片/区域
            if (!sink.send(new MapPaletteEvent(config.paletteId, palette.colors()))) return false;
            paletteSent = true;
            palette.freeze();
            return true;
        }
    }

    /**
     * 重发已发布的调色板快照：晚接入消费者（消费端中途接入，如 AstrBot 插件）的自愈通道。
     * 快照与首发完全一致——同一 config.paletteId + 冻结后的 palette.colors()，
     * 消费端对同 id+同 colors 幂等，重复收到无副作用。
     * 首发（publishPalette）成功前禁止重发：否则会发出未注册真实色的半空调色板并被冻结。
     * 不改变 paletteSent 与冻结态，也不影响 publishPalette / palettePublished 的既有语义；
     * 重发节奏由 ModLifecycle.onServerTick 按 palette_republish_interval_ms 周期调度，本方法只执行一次重发。
     *
     * @return true = 已首发且本次 send 成功；false = 尚未首发（零事件）或本次 send 失败
     */
    public boolean republishPalette(EventSink sink) {
        synchronized (lock) {
            // 首发未成功：调色板可能尚未注册真实色，重发会冻结半空调色板——直接拒绝，零事件
            if (!paletteSent) return false;
            return sink.send(new MapPaletteEvent(config.paletteId, palette.colors()));
        }
    }

    /** true = 调色板快照已发出（含冻结） */
    public boolean palettePublished() {
        synchronized (lock) {
            return paletteSent;
        }
    }

    /** 主线程入队（区块加载 / 方块改动事件里调用），去重 */
    public void enqueue(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) return;
        synchronized (lock) {
            if (pending.size() >= config.pendingCap) {
                if (!capWarned) {
                    capWarned = true;
                    LOG.warning("瓦片队列超过 pending_cap=" + config.pendingCap + "，后续脏区块静默丢弃（后端按底图补齐）");
                }
                return;
            }
            Entry e = new Entry(dimension, pos.x, pos.z);
            pending.putIfAbsent(e.key(), e);
        }
    }

    /** 主线程 tick 里调用：判断刷写时机；主线程采样（硬上限内）后把数据交给后台线程打包发送 */
    public void onTick(long nowMs) {
        List<Entry> picked;
        synchronized (lock) {
            if (flushing || pending.isEmpty()) return;
            boolean sizeTrigger = pending.size() >= config.batchSize;
            boolean timeTrigger = nowMs - lastFlushMs >= config.flushIntervalMs;
            if (!sizeTrigger && !timeTrigger) return;

            lastFlushMs = nowMs;
            picked = new ArrayList<>();
            var it = pending.entrySet().iterator();
            while (it.hasNext() && picked.size() < config.batchSize && picked.size() < config.maxChunkRendersPerTick) {
                picked.add(it.next().getValue());
                it.remove();
            }
            if (picked.isEmpty()) return;
            flushing = true;
        }

        // 主线程采样：唯一允许接触世界数据的地方；锁外执行，后台只短暂抢锁
        List<Captured> batch = new ArrayList<>();
        for (Entry e : picked) {
            byte[] rgb = adapter.renderChunk(e.dimension, new ChunkPos(e.cx, e.cz));
            if (rgb != null) batch.add(new Captured(e.dimension, new Sample(e.cx, e.cz, rgb)));
            // null = 区块未加载/已卸载：丢弃；重新加载时 ChunkEvent.Load 会再入队
        }
        if (batch.isEmpty()) {
            synchronized (lock) {
                flushing = false;
            }
            return;
        }

        adapter.runAsync(() -> {
            try {
                flush(batch);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "瓦片刷写失败", t);
            } finally {
                synchronized (lock) {
                    flushing = false;
                }
            }
        });
    }

    /** 关闭时排空（主线程调用）：同步发送，不再走 runAsync */
    public void drain() {
        while (true) {
            List<Entry> picked;
            synchronized (lock) {
                if (pending.isEmpty()) return;
                picked = new ArrayList<>();
                var it = pending.entrySet().iterator();
                while (it.hasNext() && picked.size() < config.batchSize) {
                    picked.add(it.next().getValue());
                    it.remove();
                }
            }
            List<Captured> batch = new ArrayList<>();
            for (Entry e : picked) {
                byte[] rgb = adapter.renderChunk(e.dimension, new ChunkPos(e.cx, e.cz));
                if (rgb != null) batch.add(new Captured(e.dimension, new Sample(e.cx, e.cz, rgb)));
            }
            if (batch.isEmpty()) continue; // 采到的全是 null：条目已出队，继续清下一批
            boolean sent;
            try {
                sent = flush(batch);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "关闭排空失败", t);
                return;
            }
            if (!sent) {
                // 通道不可用（鹊桥未就绪或先于本 mod 关闭）：失败批次已退回队列。
                // 继续循环只会「重采样→再失败→再退回」无限空转，卡死停服主线程——
                // 立即停止排空，剩余条目静默丢弃（铁律：绝不阻断服务端）。
                LOG.log(Level.FINE, "排空时通道不可用，剩余瓦片条目丢弃");
                break;
            }
        }
    }

    /** @return true = 本批全部发送成功；false = 任一 send 失败（失败部分已退回队列） */
    private boolean flush(List<Captured> batch) {
        EventSink sink = sinkRef.get();

        // 顺序铁律：pack 量化必须先于调色板快照——pack 把颜色注册进调色板；
        // 若先发快照再 pack，快照只含占位色且 pack 时调色板已冻结，像素全退化为下标 0
        // （TDD 红→绿用例：TileQueueTest#firstFlushTilesShouldCarryRealColorIndices）。
        // 按 dimension + 8x8 对齐区域分组（batch 为线程私有的已采样数据，锁外打包安全）
        Map<String, List<Captured>> groups = new HashMap<>();
        for (Captured c : batch) {
            String regionKey = c.dimension + '@' + (c.sample.cx() >> 3) + ',' + (c.sample.cz() >> 3);
            groups.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(c);
        }

        Map<String, List<TilePayloadModel>> tilesByDim = new HashMap<>();
        for (List<Captured> group : groups.values()) {
            Captured first = group.get(0);
            if (group.size() >= REGION_MERGE_MIN) {
                int rx = (first.sample.cx() >> 3) * 8, rz = (first.sample.cz() >> 3) * 8;
                List<Sample> samples = new ArrayList<>(group.size());
                for (Captured c : group) samples.add(c.sample);
                tilesByDim.computeIfAbsent(first.dimension, k -> new ArrayList<>())
                        .add(TopBlockRenderer.pack(rx, rz, 8, samples, palette));
            } else {
                for (Captured c : group) {
                    tilesByDim.computeIfAbsent(c.dimension, k -> new ArrayList<>())
                            .add(TopBlockRenderer.pack(c.sample.cx(), c.sample.cz(), 1, List.of(c.sample), palette));
                }
            }
        }

        // 写穿本地地图库（REQ-TILE-006）：pack 之后逐块 putChunk，与发送成败无关；
        // 本段在后台 flush 线程（停服 drain 时为主线程）执行，逐块吞异常（FINE）
        if (store != null) {
            for (Captured c : batch) {
                try {
                    store.putChunk(c.dimension, c.sample.cx(), c.sample.cz(), c.sample.rgb24());
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "地图库写穿失败", t);
                }
            }
        }

        // 量化完成、颜色已注册后才发调色板快照：发送顺序仍是 palette 先于任何 tile
        if (!publishPalette(sink)) {
            requeueEntries(batch); // 通道未就绪：整批退回，下次再刷
            return false;
        }

        boolean allSent = true;
        for (Map.Entry<String, List<TilePayloadModel>> dim : tilesByDim.entrySet()) {
            if (!sink.send(new MapTileEvent(dim.getKey(), dim.getValue()))) {
                requeue(dim.getKey(), dim.getValue()); // 只按坐标退回，下一 tick 主线程重新采样
                allSent = false;
            }
        }
        return allSent;
    }

    private void requeueEntries(List<Captured> batch) {
        for (Captured c : batch) enqueue(c.dimension, new ChunkPos(c.sample.cx(), c.sample.cz()));
    }

    private void requeue(String dimension, List<TilePayloadModel> tiles) {
        synchronized (lock) {
            for (TilePayloadModel t : tiles) {
                if (t.getSize() == 1) {
                    if (pending.size() < config.pendingCap)
                        pending.putIfAbsent(dimension + '@' + t.getCx() + ',' + t.getCz(), new Entry(dimension, t.getCx(), t.getCz()));
                } else {
                    for (int dx = 0; dx < t.getSize(); dx++)
                        for (int dz = 0; dz < t.getSize(); dz++)
                            if (pending.size() < config.pendingCap)
                                pending.putIfAbsent(dimension + '@' + (t.getCx() + dx) + ',' + (t.getCz() + dz),
                                        new Entry(dimension, t.getCx() + dx, t.getCz() + dz));
                }
            }
        }
    }
}
