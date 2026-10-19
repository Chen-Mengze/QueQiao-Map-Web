package net.yumezawa.queqiao_map_web.mapstore;

import net.yumezawa.queqiao_map_web.config.MapExtConfig;
import net.yumezawa.queqiao_map_web.event.MapRegionEvent;
import net.yumezawa.queqiao_map_web.model.RegionPayloadModel;
import net.yumezawa.queqiao_map_web.sender.EventSink;
import net.yumezawa.queqiao_map_web.tile.Palette;
import net.yumezawa.queqiao_map_web.tile.TileQueue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.Deflater;

/**
 * 区域流引擎（DESIGN_MAPSTORE §3.2）：独立 daemon 线程，把地图库中「变化区域」
 * （xor32 != lastSentXor）量化压缩后经 map_region 事件补传，成功后 markSent。
 *
 * 门（REQ-LIFE-005）：sink.isReady() 且调色板已发布——未发布则先经
 * TileQueue.publishPalette 补发（顺序铁律：palette 先于任何 map_region）。
 * 节流（REQ-MS-007）：令牌桶按【压缩字节】regionStreamBytesPerSec（≥4096）。
 * 每事件最多 regionStreamMaxRegionsPerEvent 个区域；发完 30s 复查一轮；
 * 维度变化由 ModLifecycle.requestRecheck 提前唤醒。一切异常吞 FINE，绝不影响服务端。
 *
 * 校验语义（§3.3）：事件 xor 字段 = store 头里的原始 RGB24 u32 校验（发送前已读）；
 * sha1 = 本次压缩字节的 hex；两者都发，消费端各自可验。
 */
public final class RegionStreamer {

    private static final Logger LOG = Logger.getLogger("queqiao_map_web.mapstore");
    /** 区域边长（区块数），与 MapStore 存储格式一致 */
    private static final int SIDE = 512;
    private static final int REGION_RGB_BYTES = SIDE * SIDE * 3;
    /** 一轮发完后的复查周期 */
    private static final long RECHECK_MS = 30_000;

    private final MapStore store;
    private final TileQueue tileQueue;
    private final Palette palette;
    private final MapExtConfig config;
    private final Supplier<EventSink> sinkRef;

    private final Object pause = new Object();
    private volatile boolean stopped;
    private volatile Thread worker;

    // 令牌桶（仅 worker 线程访问；capacity = 每秒压缩字节）
    private long tokens;
    private long lastRefillNs;

    public RegionStreamer(MapStore store, TileQueue tileQueue, Palette palette,
                          MapExtConfig config, Supplier<EventSink> sinkRef) {
        this.store = store;
        this.tileQueue = tileQueue;
        this.palette = palette;
        this.config = config;
        this.sinkRef = sinkRef;
        this.lastRefillNs = System.nanoTime(); // 构造即起算（测试直调 streamOnce 也有限速语义）
    }

    /** 启动 daemon 线程（幂等） */
    public synchronized void start() {
        if (worker != null || stopped) return;
        lastRefillNs = System.nanoTime();
        Thread t = new Thread(this::loop, "queqiao-map-web-region");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** 停止并等待 worker 退出（上限 5s；测试断言返回后 !isRunning） */
    public void stop() {
        stopped = true;
        synchronized (pause) {
            pause.notifyAll();
        }
        Thread t = worker;
        if (t != null) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }

    public boolean isRunning() {
        return !stopped && worker != null;
    }

    /** 唤醒 30s 等待立即复查（维度清单变化时由 ModLifecycle 调用） */
    public void requestRecheck() {
        synchronized (pause) {
            pause.notifyAll();
        }
    }

    private void loop() {
        while (!stopped) {
            try {
                streamOnce();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "区域流一轮扫描失败（下轮再试）", t);
            }
            try {
                synchronized (pause) {
                    if (!stopped) pause.wait(RECHECK_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 一轮完整扫描（包私有：RegionStreamerTest 同包直调，无需开线程）。
     * @return 本轮成功发出的区域数
     */
    int streamOnce() {
        EventSink sink = sinkRef.get();
        if (sink == null || !sink.isReady()) return 0;
        // 调色板门：未发布先补发（发不出去 = 通道未就绪，本轮放弃）
        if (!tileQueue.publishPalette(sink)) return 0;

        int sentRegions = 0;
        int maxPerEvent = Math.max(1, config.regionStreamMaxRegionsPerEvent);
        for (String dim : store.listDimensions()) {
            if (stopped) break;
            List<RegionPayloadModel> batch = new ArrayList<>();
            List<Integer> batchXor = new ArrayList<>();
            for (MapStore.RegionInfo info : store.listRegions(dim)) {
                if (stopped) break;
                // xor 与 lastSentXor 相同 = 自上次发送后未变（§2.5 去重门）
                if (info.xor32() == info.lastSentXor()) continue;
                byte[] rgb = store.readRegion(dim, info.rx(), info.rz());
                if (rgb == null || rgb.length != REGION_RGB_BYTES) continue; // 竞态损坏/未写完：跳过
                Encoded e = encode(info, rgb);
                if (!acquire(e.bytes())) break; // 令牌不足或停止：本轮到此，下轮续
                batch.add(e.model());
                batchXor.add(info.xor32());
                if (batch.size() >= maxPerEvent) {
                    sentRegions += sendBatch(sink, dim, batch, batchXor);
                    batch = new ArrayList<>();
                    batchXor = new ArrayList<>();
                }
            }
            if (!batch.isEmpty()) sentRegions += sendBatch(sink, dim, batch, batchXor);
        }
        return sentRegions;
    }

    private record Encoded(RegionPayloadModel model, int bytes) {
    }

    /** RGB24 -> pal8（§3.3：Palette.indexOf 线程安全，冻结后仍走同一路径）-> zlib -> sha1/b64 */
    private Encoded encode(MapStore.RegionInfo info, byte[] rgb24) {
        byte[] pal = new byte[SIDE * SIDE];
        for (int i = 0; i < pal.length; i++) {
            int o = i * 3;
            int color = ((rgb24[o] & 0xFF) << 16) | ((rgb24[o + 1] & 0xFF) << 8) | (rgb24[o + 2] & 0xFF);
            pal[i] = (byte) palette.indexOf(color);
        }
        byte[] deflated = zlib(pal);
        RegionPayloadModel model = new RegionPayloadModel(
                info.rx(), info.rz(), "zlib-pal8", info.xor32(), sha1Hex(deflated),
                Base64.getEncoder().encodeToString(deflated));
        return new Encoded(model, deflated.length);
    }

    /** 发送整批；成功才 markSent（失败保持未确认，下轮 xor 比对再试） */
    private int sendBatch(EventSink sink, String dim, List<RegionPayloadModel> batch, List<Integer> xors) {
        if (!sink.send(new MapRegionEvent(dim, batch))) return 0;
        for (int i = 0; i < batch.size(); i++) {
            RegionPayloadModel r = batch.get(i);
            store.markSent(dim, r.getRx(), r.getRz(), xors.get(i));
        }
        return batch.size();
    }

    /** 令牌桶：capacity = regionStreamBytesPerSec（按真实经过时间补充），不足则睡眠 */
    private boolean acquire(int bytes) {
        long rate = Math.max(4096, config.regionStreamBytesPerSec);
        while (!stopped) {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNs;
            if (elapsed > 0) {
                // 整秒增量；突发上限 = 一秒压缩字节（空闲久后不得倾泻，REQ-MS-007 限速语义）
                tokens = Math.min(rate, tokens + elapsed / 1_000_000_000L * rate);
                if (elapsed >= 1_000_000_000L) lastRefillNs = now;
            }
            // 不足一整秒的余量留在 elapsed 里（lastRefillNs 未动），避免丢帧
            if (tokens >= bytes) {
                tokens -= bytes;
                return true;
            }
            long sleepMs = Math.max(20, Math.min(500, (long) ((bytes - tokens) * 1000.0 / rate)));
            try {
                synchronized (pause) {
                    pause.wait(sleepMs); // stop/requestRecheck 可提前唤醒
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static byte[] zlib(byte[] raw) {
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        byte[] out = new byte[Math.max(1024, raw.length / 2)];
        int n = 0;
        while (!deflater.finished()) {
            if (n == out.length) {
                byte[] bigger = new byte[out.length * 2];
                System.arraycopy(out, 0, bigger, 0, n);
                out = bigger;
            }
            n += deflater.deflate(out, n, out.length - n);
        }
        deflater.end();
        byte[] exact = new byte[n];
        System.arraycopy(out, 0, exact, 0, n);
        return exact;
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // JDK 必有 SHA-1
        }
    }
}
