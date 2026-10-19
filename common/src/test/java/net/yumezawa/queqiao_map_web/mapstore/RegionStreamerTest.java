package net.yumezawa.queqiao_map_web.mapstore;

import net.yumezawa.queqiao_map_web.config.MapExtConfig;
import net.yumezawa.queqiao_map_web.event.MapRegionEvent;
import net.yumezawa.queqiao_map_web.model.RegionPayloadModel;
import net.yumezawa.queqiao_map_web.sender.EventSink;
import net.yumezawa.queqiao_map_web.testsupport.FakePlatformAdapter;
import net.yumezawa.queqiao_map_web.testsupport.QueqiaoToolBootstrap;
import net.yumezawa.queqiao_map_web.testsupport.RecordingEventSink;
import net.yumezawa.queqiao_map_web.tile.Palette;
import net.yumezawa.queqiao_map_web.tile.TileQueue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-MS-007：xor 跳过、markSent 更新、变化重发与字节令牌桶节流（蓝图 §3.2：
 * region_stream_bytes_per_sec 按压缩字节限速 + 每事件 ≤region_stream_max_regions_per_event）。
 * streamOnce() 包私有直调，不经 worker 线程，时序完全确定。
 */
class RegionStreamerTest {

    private static final String DIM = "minecraft:overworld";

    @BeforeAll
    static void initQueqiaoStatics() {
        QueqiaoToolBootstrap.ensureDummyConfig();
    }

    @TempDir
    Path dir;

    private static byte[] solid(int rgb) {
        byte[] px = new byte[MapStore.PAYLOAD_BYTES];
        for (int i = 0; i < px.length; i += 3) {
            px[i] = (byte) (rgb >> 16);
            px[i + 1] = (byte) (rgb >> 8);
            px[i + 2] = (byte) rgb;
        }
        px[1] ^= 0x01; // 纯色周期3与4字节大端XOR互相抵消（xor32=0），翻一位使 xor≠0
        return px;
    }

    private static String sha1Hex(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder(40);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] inflate(byte[] deflated) throws Exception {
        Inflater in = new Inflater();
        in.setInput(deflated);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        while (!in.finished()) {
            int n = in.inflate(buf);
            if (n == 0) break;
            bos.write(buf, 0, n);
        }
        in.end();
        return bos.toByteArray();
    }

    @Test
    @Tag("REQ-MS-007")
    @DisplayName("3 区域 2 已发：只发 xor 变化者并 markSent；二轮零发送；改动区域重发；压缩字节限速下限成立")
    void skipUnchangedXorRegionsAndThrottle() throws Exception {
        MapStore store = new MapStore(dir);
        store.putRegion(DIM, 0, 0, solid(0x3f76e4));
        store.putRegion(DIM, 1, 0, solid(0x3f76e4));
        store.putRegion(DIM, 2, 0, solid(0x3f76e4));
        for (int rx : new int[]{0, 1}) {
            store.markSent(DIM, rx, 0, store.header(DIM, rx, 0).xor32());
        }
        MapExtConfig c = MapExtConfig.load(dir.resolve("queqiao_map_web.yml")); // 缺文件 → 全默认
        c.regionStreamBytesPerSec = 4096;
        c.regionStreamMaxRegionsPerEvent = 1;
        Palette palette = new Palette();
        FakePlatformAdapter adapter = new FakePlatformAdapter();
        RecordingEventSink sink = new RecordingEventSink(true, true);
        AtomicReference<EventSink> ref = new AtomicReference<>(sink);
        TileQueue tileQueue = new TileQueue(adapter, c, palette, ref::get, null);
        RegionStreamer streamer = new RegionStreamer(store, tileQueue, palette, c, ref::get);

        long t0 = System.nanoTime();
        assertEquals(1, streamer.streamOnce(), "仅 xor≠lastSentXor 的 1 个区域被发送");
        assertEquals(List.of("map_palette", "map_region"), sink.subTypes(), "调色板先于 map_region（顺序铁律）");
        MapRegionEvent ev = (MapRegionEvent) sink.sent.get(1);
        assertEquals(DIM, ev.getDimension());
        assertEquals(1, ev.getRegions().size(), "每事件 ≤ region_stream_max_regions_per_event=1");
        RegionPayloadModel p = ev.getRegions().get(0);
        assertEquals(2, p.getRx());
        assertEquals(0, p.getRz());
        assertEquals("zlib-pal8", p.getFormat());
        assertEquals(store.header(DIM, 2, 0).xor32(), p.getXor(), "xor = store 对原始 RGB24 的校验和");
        byte[] deflated1 = Base64.getDecoder().decode(p.getB64());
        assertEquals(p.getSha1(), sha1Hex(deflated1), "sha1 = 压缩字节 hex（传输校验）");
        assertEquals(512 * 512, inflate(deflated1).length, "pal8 512×512 行主序");
        for (MapStore.RegionInfo info : store.listRegions(DIM)) {
            // rx0/1 已被播种时显式 markSent（xor≠0），rx2 发送成功后被 markSent → 三者 xor==lastSentXor
            assertEquals(info.xor32(), info.lastSentXor(), "发送成功才 markSent（去重门下一轮生效）");
        }
        assertEquals(0, streamer.streamOnce(), "二轮巡检全部 xor==lastSentXor → 零发送");

        byte[] green = solid(0x2ecc71);
        green[2] ^= 0x02; // 再翻一位：否则与蓝色版 xor 相同（同为 0x00010000）会被去重门跳过
        store.putRegion(DIM, 0, 0, green);
        assertEquals(1, streamer.streamOnce(), "xor 变化 → 重发");
        MapRegionEvent ev2 = (MapRegionEvent) sink.sent.get(2);
        assertEquals(0, ev2.getRegions().get(0).getRx());
        assertEquals(store.header(DIM, 0, 0).xor32(), ev2.getRegions().get(0).getXor());
        byte[] deflated2 = Base64.getDecoder().decode(ev2.getRegions().get(0).getB64());

        // 节流下限：两个区域事件的压缩字节按 4096 B/s 至少耗时此下限（令牌桶不得超发）
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        long compressed = deflated1.length + deflated2.length;
        assertTrue(elapsedMs >= compressed * 1000L / 4096L - 100,
                "压缩字节 " + compressed + "B @4096B/s 下限 " + (compressed * 1000L / 4096L) + "ms，实测 " + elapsedMs + "ms");
        assertEquals(2, sink.subTypes().stream().filter(s -> s.equals("map_region")).count());
    }
}
