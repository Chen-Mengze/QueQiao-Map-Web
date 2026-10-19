package net.yumezawa.queqiao_map_web.tile;

import net.yumezawa.queqiao_map_web.tile.TopBlockRenderer.Sample;

import net.yumezawa.queqiao_map_web.config.MapExtConfig;
import net.yumezawa.queqiao_map_web.event.MapPaletteEvent;
import net.yumezawa.queqiao_map_web.event.MapTileEvent;
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
 * MapPaletteEvent 必须先于任何 MapTileEvent：首次刷写先发调色板，失败则本批退回队列。
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

    private final Object lock = new Object();
    private final LinkedHashMap<String, Entry> pending = new LinkedHashMap<>();
    private long lastFlushMs = System.currentTimeMillis();
    private boolean flushing;
    private boolean paletteSent;
    private boolean capWarned;

    public TileQueue(PlatformAdapter adapter, MapExtConfig config, Palette palette, Supplier<EventSink> sinkRef) {
        this.adapter = adapter;
        this.config = config;
        this.palette = palette;
        this.sinkRef = sinkRef;
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
        synchronized (lock) {
            if (!paletteSent) {
                // 鹊桥 send 只是本地入队（快操作），锁内发送以保证调色板先于任何瓦片
                if (!sink.send(new MapPaletteEvent(config.paletteId, palette.colors()))) {
                    requeueEntries(batch); // 通道未就绪：整批退回，下次再刷
                    return false;
                }
                paletteSent = true;
                palette.freeze();
            }
        }

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
