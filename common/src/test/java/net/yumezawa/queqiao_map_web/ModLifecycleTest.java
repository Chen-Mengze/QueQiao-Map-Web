package net.yumezawa.queqiao_map_web;

import com.github.theword.queqiao.tool.event.base.BaseEvent;
import net.yumezawa.queqiao_map_web.config.MapExtConfig;
import net.yumezawa.queqiao_map_web.event.MapRegionEvent;
import net.yumezawa.queqiao_map_web.mapstore.BackfillEngine;
import net.yumezawa.queqiao_map_web.mapstore.MapStore;
import net.yumezawa.queqiao_map_web.mapstore.RegionStreamer;
import net.yumezawa.queqiao_map_web.platform.PlatformAdapter.ChunkPos;
import net.yumezawa.queqiao_map_web.sender.EventSink;
import net.yumezawa.queqiao_map_web.testsupport.FakePlatformAdapter;
import net.yumezawa.queqiao_map_web.testsupport.QueqiaoToolBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-LIFE-005 / REQ-MS-008：停服收尾顺序 streamer → backfill →（入口 adapter.shutdown）→ drain。
 * adapter.shutdown 在加载器入口（forge QueqiaoMapWeb.java:56-57）先于 ModLifecycle.stop，
 * 本仓库 common 侧可断言的是 ①②④ 的顺序与「drain 的 map_tile 之后不再有 map_region」的效果。
 * 区域流在 4096 B/s 节流下对 20 个区域的广播必然未完成时 stop() 介入。
 */
@Timeout(30)
class ModLifecycleTest {

    private static final String DIM = "minecraft:overworld";

    @TempDir
    Path dir;

    private final ModLifecycle lifecycle = ModLifecycle.get();
    private FakePlatformAdapter adapter;

    @BeforeEach
    void resetSingleton() throws Exception {
        QueqiaoToolBootstrap.ensureDummyConfig();
        try {
            lifecycle.stop();
        } catch (Throwable ignored) {
        }
        sinkRef().set(null);
        adapter = new FakePlatformAdapter();
        adapter.solidChunk(0x3f76e4);
    }

    @AfterEach
    void stopSingleton() throws Exception {
        try {
            lifecycle.stop();
        } catch (Throwable ignored) {
        }
        sinkRef().set(null);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<EventSink> sinkRef() throws Exception {
        Field f = ModLifecycle.class.getDeclaredField("sinkRef");
        f.setAccessible(true);
        return (AtomicReference<EventSink>) f.get(lifecycle);
    }

    private void writeConfig(String... lines) throws IOException {
        Path sub = dir.resolve(MapExtConfig.SUB_DIR);
        Files.createDirectories(sub);
        Files.writeString(sub.resolve(MapExtConfig.FILE_NAME),
                String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    /** 事件来自流式线程 + 主线程 drain：sent 必须线程安全（RecordingEventSink 的 ArrayList 不够） */
    private static final class ConcurrentRecordingSink implements EventSink {
        private final List<BaseEvent> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean send(BaseEvent event) {
            sent.add(event);
            return true;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public String name() {
            return "concurrent-recording";
        }

        List<String> subTypes() {
            synchronized (sent) {
                List<String> out = new ArrayList<>(sent.size());
                for (BaseEvent e : sent) out.add(e.getSubType());
                return out;
            }
        }

        List<MapRegionEvent> regionEvents() {
            synchronized (sent) {
                List<MapRegionEvent> out = new ArrayList<>();
                for (BaseEvent e : sent) if (e instanceof MapRegionEvent r) out.add(r);
                return out;
            }
        }
    }

    @Test
    @Tag("REQ-MS-008")
    @Tag("REQ-LIFE-005")
    @DisplayName("停服顺序 streamer→backfill→drain：drain 的 map_tile 之后不再有任何 map_region；stop 返回后两引擎均已退出")
    void stopOrderStreamerBackfillDrain() throws Exception {
        // worldSaveRoot=临时目录；先按「同一 store 根」播种 20 个未发送区域（入口 new MapStore(dir) 读同一批文件）
        adapter.worldSaveRootDir = dir;
        MapStore seed = new MapStore(dir);
        byte[] blue = new byte[MapStore.PAYLOAD_BYTES];
        for (int i = 0; i < blue.length; i += 3) {
            blue[i] = 0x3f;
            blue[i + 1] = 0x76;
            blue[i + 2] = (byte) 0xe4;
        }
        blue[1] ^= 0x01; // 纯色周期3在4字节大端XOR下抵消（xor32=0 会被去重门跳过），翻一位使 xor≠0
        for (int rx = 0; rx < 20; rx++) seed.putRegion(DIM, rx, 0, blue);

        writeConfig("enabled: true", "batch_size: 4",
                "region_stream_bytes_per_sec: 4096",
                "region_stream_max_regions_per_event: 1");
        lifecycle.start(adapter, dir);
        assertNotNull(lifecycle.mapStore(), "root 非 null 且开关开 → store 已建");
        ConcurrentRecordingSink sink = new ConcurrentRecordingSink();
        sinkRef().set(sink);
        adapter.tickConsumer.accept(List.of()); // 首个就绪 tick：两引擎各启动一次

        RegionStreamer streamer = lifecycle.regionStreamer();
        BackfillEngine backfill = lifecycle.backfillEngine();
        assertNotNull(streamer, "region_stream_enabled 默认 true");
        assertNotNull(backfill, "backfill_enabled 默认 true");
        assertTrue(streamer.isRunning());
        assertTrue(backfill.isRunning());

        // 4 个脏块入队（tick 时空队列已过 flush 时机；留待 stop 的 drain 发送）
        adapter.dirtyListener.onChunkDirty(DIM, new ChunkPos(0, 0));
        adapter.dirtyListener.onChunkDirty(DIM, new ChunkPos(1, 0));
        adapter.dirtyListener.onChunkDirty(DIM, new ChunkPos(0, 1));
        adapter.dirtyListener.onChunkDirty(DIM, new ChunkPos(1, 1));

        // 让区域流完成首轮令牌等待并发出若干区域（@4096B/s 首轮约 1s，1.5s 内必发不满 20 个）
        Thread.sleep(1500);
        // stop 在广播中途介入：20 区域 @4096B/s 必然未完成
        assertDoesNotThrow(() -> lifecycle.stop());

        // ①②：stop() 返回即两引擎线程已退出（join 完成；sleep 周期 ≤2s 内退出不违背 @Timeout(30)）
        assertFalse(streamer.isRunning(), "RegionStreamer 已停");
        assertFalse(backfill.isRunning(), "BackfillEngine 已停");
        assertNull(lifecycle.regionStreamer());
        assertNull(lifecycle.backfillEngine());
        assertNull(lifecycle.mapStore());

        // ④ 效果核验：drain 的 map_tile 之后再无 map_region（若 drain 先于 streamer.stop 则可能违背）
        List<String> seq = sink.subTypes();
        int firstTile = seq.indexOf("map_tile");
        assertTrue(firstTile >= 0, "drain 应把退回批次发出（map_tile 出现）");
        assertFalse(seq.subList(firstTile + 1, seq.size()).contains("map_region"),
                "drain 之后不得再有 map_region：streamer 必须先于 drain 停止");
        long regionEvents = seq.stream().filter(s -> s.equals("map_region")).count();
        assertTrue(regionEvents < 20, "节流未完时 stop 中断了区域广播（本轮未发满 20 个）");
        // 顺序铁律经共享门：全场恰一个 map_palette，且先于任何 map_region
        assertEquals(1, seq.stream().filter(s -> s.equals("map_palette")).count(),
                "streamer 与 drain 共享 publishPalette 门 → 调色板恰一次");
        int firstPalette = seq.indexOf("map_palette");
        int firstRegion = seq.indexOf("map_region");
        if (firstRegion >= 0) assertTrue(firstPalette < firstRegion, "map_palette 先于 map_region");
    }

    @Test
    @Tag("REQ-MS-009")
    @Tag("REQ-LIFE-005")
    @DisplayName("region_replay_on_start=true：就绪首 tick 在区域流启动前全库 lastSentXor 归零；消费端离线期间已确认的 3 区域全部限速重放")
    void replayOnStartResetsBeforeStreamer() throws Exception {
        // 播种「已发送」态区域：不开重放则 REQ-MS-007 去重门永不重发（本次修复针对的烧档场景）
        adapter.worldSaveRootDir = dir;
        MapStore seed = new MapStore(dir);
        byte[] blue = new byte[MapStore.PAYLOAD_BYTES];
        for (int i = 0; i < blue.length; i += 3) {
            blue[i] = 0x3f;
            blue[i + 1] = 0x76;
            blue[i + 2] = (byte) 0xe4;
        }
        blue[1] ^= 0x01; // 翻一位使 xor≠0（xor32==0 区域复位后仍被去重门跳过——REQ-MS-009 边界）
        for (int rx = 0; rx < 3; rx++) {
            seed.putRegion(DIM, rx, 0, blue);
            seed.markSent(DIM, rx, 0, seed.header(DIM, rx, 0).xor32());
        }

        writeConfig("enabled: true", "backfill_enabled: false",
                "region_replay_on_start: true",
                "region_stream_max_regions_per_event: 1");
        lifecycle.start(adapter, dir);
        ConcurrentRecordingSink sink = new ConcurrentRecordingSink();
        sinkRef().set(sink);
        adapter.tickConsumer.accept(List.of()); // 就绪首 tick：startMapStoreEngines 同步复位 lastSentXor → 区域流启动
        assertNotNull(lifecycle.regionStreamer(), "region_stream_enabled 默认 true → 已启动");

        List<MapRegionEvent> replayed = List.of();
        long deadline = System.currentTimeMillis() + 12_000; // 令牌桶首秒空桶：首轮 acquire 约 1s
        while (System.currentTimeMillis() < deadline) {
            replayed = sink.regionEvents();
            if (replayed.size() >= 3) break;
            Thread.sleep(200);
        }
        assertTrue(replayed.size() >= 3, "3 个已确认区域全部重放（每事件 1 个），实际 " + replayed.size());
        Set<Integer> rxs = new HashSet<>();
        for (MapRegionEvent ev : replayed) for (var p : ev.getRegions()) rxs.add(p.getRx());
        assertEquals(Set.of(0, 1, 2), rxs, "重放覆盖全部播种区域");
        for (MapStore.RegionInfo info : seed.listRegions(DIM))
            assertEquals(info.xor32(), info.lastSentXor(), "重发成功后去重门重新闭合（重新 markSent）");
        assertEquals(1, sink.subTypes().stream().filter(s -> s.equals("map_palette")).count(),
                "重放不破顺序铁律：全场恰一次调色板首发（默认 10s 周期重发未到点）");
        assertDoesNotThrow(() -> lifecycle.stop());
    }
}
