package net.yumezawa.queqiao_map_web.tile;

import net.yumezawa.queqiao_map_web.config.MapExtConfig;
import net.yumezawa.queqiao_map_web.event.MapPaletteEvent;
import net.yumezawa.queqiao_map_web.event.MapTileEvent;
import net.yumezawa.queqiao_map_web.mapstore.MapStore;
import net.yumezawa.queqiao_map_web.model.TilePayloadModel;
import net.yumezawa.queqiao_map_web.platform.PlatformAdapter.ChunkPos;
import net.yumezawa.queqiao_map_web.sender.EventSink;
import net.yumezawa.queqiao_map_web.testsupport.FakePlatformAdapter;
import net.yumezawa.queqiao_map_web.testsupport.RecordingEventSink;
import net.yumezawa.queqiao_map_web.testsupport.QueqiaoToolBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/** REQ-TILE-001/003/004/005：待处理队列合并、去重、封顶与每 tick 渲染预算（Fake 的 runAsync 同步执行）。 */
class TileQueueTest {

    private static final String DIM = "minecraft:overworld";

    @BeforeAll
    static void initQueqiaoStatics() {
        // MapPaletteEvent/MapTileEvent 构造器经 BaseEvent 硬依赖 GlobalContext.getConfig()（鹊桥库行为）
        QueqiaoToolBootstrap.ensureDummyConfig();
    }

    @TempDir
    Path dir;

    private FakePlatformAdapter adapter;
    private RecordingEventSink okSink;
    private AtomicReference<EventSink> sinkRef;
    private Palette palette;

    private MapExtConfig cfg(String... lines) throws IOException {
        Path f = dir.resolve("queqiao_map_web.yml");
        if (lines.length > 0) {
            Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        }
        return MapExtConfig.load(f); // 无行 → 缺文件 → 全默认
    }

    private TileQueue newQueue(MapExtConfig c) {
        adapter = new FakePlatformAdapter();
        adapter.solidChunk(0x3f76e4);
        okSink = new RecordingEventSink(true, true);
        sinkRef = new AtomicReference<>(okSink);
        palette = new Palette();
        return new TileQueue(adapter, c, palette, sinkRef::get, null);
    }

    private static void enqueueChunks(TileQueue q, int... cxThenCz) {
        for (int i = 0; i < cxThenCz.length; i += 2) {
            q.enqueue(DIM, new ChunkPos(cxThenCz[i], cxThenCz[i + 1]));
        }
    }

    private static List<MapTileEvent> tileEvents(RecordingEventSink s) {
        List<MapTileEvent> out = new ArrayList<>();
        for (var e : s.sent) if (e instanceof MapTileEvent t) out.add(t);
        return out;
    }

    private static List<MapPaletteEvent> paletteEvents(RecordingEventSink s) {
        List<MapPaletteEvent> out = new ArrayList<>();
        for (var e : s.sent) if (e instanceof MapPaletteEvent p) out.add(p);
        return out;
    }

    /** 消费端解码（复用 TopBlockRendererTest 的 inflate 思路） */
    private static byte[] inflate(String b64) throws IOException {
        Inflater in = new Inflater();
        in.setInput(Base64.getDecoder().decode(b64));
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            while (!in.finished()) {
                int n = in.inflate(buf);
                if (n == 0) break;
                bos.write(buf, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            in.end();
        }
        return bos.toByteArray();
    }

    @Test
    @Tag("REQ-TILE-001")
    @DisplayName("同一 8x8 区域的 4 个脏块合并成一个 size=8 瓦片，cx/cz=区域原点块坐标；map_palette 先于 map_tile")
    void fourChunksMergeIntoSize8() throws IOException {
        TileQueue q = newQueue(cfg("batch_size: 4"));
        enqueueChunks(q, 0, 0, 1, 0, 0, 1, 1, 1);
        q.onTick(System.currentTimeMillis() + 10_000);
        assertEquals(List.of("map_palette", "map_tile"), okSink.subTypes(), "调色板必须先于任何瓦片发出");
        MapTileEvent te = tileEvents(okSink).get(0);
        assertEquals(DIM, te.getDimension());
        assertEquals(1, te.getTiles().size());
        TilePayloadModel t = te.getTiles().get(0);
        assertEquals(8, t.getSize());
        assertEquals(0, t.getCx());
        assertEquals(0, t.getCz());
        assertEquals("zlib-pal8", t.getFormat());
        assertEquals(4, adapter.renderCalls, "4 个脏块各渲染一次");
    }

    @Test
    @Tag("REQ-TILE-001")
    @DisplayName("区域内不足 REGION_MERGE_MIN=4 块 → 逐块 size=1 打包，cx/cz=块坐标")
    void smallRegionPackedPerChunk() throws IOException {
        TileQueue q = newQueue(cfg()); // 默认 batch_size=64 → 靠时间触发
        enqueueChunks(q, 2, 2, 3, 2, 2, 3);
        q.onTick(System.currentTimeMillis() + 10_000);
        MapTileEvent te = tileEvents(okSink).get(0);
        assertEquals(3, te.getTiles().size());
        for (TilePayloadModel t : te.getTiles()) assertEquals(1, t.getSize());
        assertTrue(te.getTiles().stream().anyMatch(t -> t.getCx() == 2 && t.getCz() == 2));
        assertTrue(te.getTiles().stream().anyMatch(t -> t.getCx() == 3 && t.getCz() == 2));
        assertTrue(te.getTiles().stream().anyMatch(t -> t.getCx() == 2 && t.getCz() == 3));
    }

    @Test
    @Tag("REQ-TILE-003")
    @DisplayName("入队按 (dimension,cx,cz) 位置去重（pending putIfAbsent）：重复入队 3 次只渲染发送 1 次")
    void enqueueDedupsByPosition() throws IOException {
        TileQueue q = newQueue(cfg());
        enqueueChunks(q, 5, 5, 5, 5, 5, 5);
        q.onTick(System.currentTimeMillis() + 10_000);
        assertEquals(1, adapter.renderCalls, "重复入队 → 只渲染一次");
        MapTileEvent te = tileEvents(okSink).get(0);
        assertEquals(1, te.getTiles().size());
        assertEquals(5, te.getTiles().get(0).getCx());
        assertEquals(5, te.getTiles().get(0).getCz());
    }

    @Test
    @Tag("REQ-TILE-004")
    @DisplayName("pending_cap 封顶：超额入队静默丢弃（不抛异常），只处理封顶内的 16 条")
    void pendingCapSilentlyDropsOverflow() throws IOException {
        TileQueue q = newQueue(cfg("batch_size: 200", "pending_cap: 16"));
        for (int cx = 0; cx < 6; cx++)
            for (int cz = 0; cz < 5; cz++) enqueueChunks(q, cx, cz); // 30 个不同块 > 16
        long now = System.currentTimeMillis() + 10_000;
        assertDoesNotThrow(() -> q.onTick(now));
        assertEquals(16, adapter.renderCalls, "只渲染封顶内保留的 16 条");
        MapTileEvent te = tileEvents(okSink).get(0);
        assertEquals(1, te.getTiles().size(), "16 块同区域 → 合并为一个瓦片");
        assertEquals(8, te.getTiles().get(0).getSize());
        q.onTick(now + 1);
        assertEquals(16, adapter.renderCalls, "第二 tick 队列已空 → 溢出的 14 块确已丢弃");
    }

    @Test
    @Tag("REQ-TILE-005")
    @DisplayName("默认 max_chunk_renders_per_tick=64：100 个脏块挂起时单个 onTick 只渲染 64 次")
    void defaultRenderCapIs64() throws IOException {
        TileQueue q = newQueue(cfg("batch_size: 1000"));
        for (int cx = 0; cx < 10; cx++)
            for (int cz = 0; cz < 10; cz++) enqueueChunks(q, cx, cz);
        q.onTick(System.currentTimeMillis() + 10_000);
        assertEquals(64, adapter.renderCalls, "picked = min(100, batch_size 1000, 上限 64)");
    }

    @Test
    @Tag("REQ-TILE-005")
    @DisplayName("下调 max_chunk_renders_per_tick=32 → 单个 onTick renderChunk 恰 32 次")
    void loweredRenderCapRespected() throws IOException {
        TileQueue q = newQueue(cfg("batch_size: 1000", "max_chunk_renders_per_tick: 32"));
        for (int cx = 0; cx < 10; cx++)
            for (int cz = 0; cz < 10; cz++) enqueueChunks(q, cx, cz);
        q.onTick(System.currentTimeMillis() + 10_000);
        assertEquals(32, adapter.renderCalls);
    }

    @Test
    @Tag("REQ-LIFE-004")
    @Tag("REQ-TILE-004")
    @DisplayName("flush 失败（send=false）→ 整批静默退回队列；通道恢复后下一次 flush 全量重发，不抛异常")
    void failedFlushRequeuesThenRecovers() throws IOException {
        TileQueue q = newQueue(cfg("batch_size: 4"));
        RecordingEventSink failing = new RecordingEventSink(true, false);
        sinkRef.set(failing);
        enqueueChunks(q, 0, 0, 1, 0, 0, 1, 1, 1);
        long now = System.currentTimeMillis() + 10_000;
        assertDoesNotThrow(() -> q.onTick(now));
        assertEquals(List.of("map_palette"), failing.subTypes(), "调色板发送失败即止，本轮无 map_tile");
        assertEquals(4, adapter.renderCalls);
        sinkRef.set(okSink); // 通道恢复
        q.onTick(now);       // pending=4 仍满足 size 触发
        assertEquals(List.of("map_palette", "map_tile"), okSink.subTypes(), "退回后重发成功");
        assertEquals(8, adapter.renderCalls, "退回后重新渲染（重新取最新顶方块）");
        assertEquals(8, tileEvents(okSink).get(0).getTiles().get(0).getSize());
    }

    @Test
    @Tag("REQ-TILE-002")
    @DisplayName("首批瓦片像素应引用真实色下标（TDD 红→绿：修复 TileQueue.flush 先发调色板再 pack 的冻结时序缺陷）")
    void firstFlushTilesShouldCarryRealColorIndices() throws IOException {
        TileQueue q = newQueue(cfg("batch_size: 4"));
        enqueueChunks(q, 0, 0, 1, 0, 0, 1, 1, 1);
        q.onTick(System.currentTimeMillis() + 10_000);
        TilePayloadModel t = tileEvents(okSink).get(0).getTiles().get(0);
        byte[] px = inflate(t.getB64());
        boolean anyColored = false;
        for (byte b : px) if (b != 0) { anyColored = true; break; }
        assertTrue(anyColored, "纯色块的像素应引用蓝色下标（>=1）而不是全 0");
    }

    @Test
    @Tag("REQ-TILE-006")
    @DisplayName("flush 写穿 store 先于发送（send 恒 false 也已落盘）；publishPalette/palettePublished 幂等门")
    void writeThroughStoreIndependentOfSendAndPaletteGate() throws IOException {
        MapExtConfig c = cfg("batch_size: 4");
        MapStore store = new MapStore(dir); // store 根 = dir/queqiao_map_web/store
        adapter = new FakePlatformAdapter();
        adapter.solidChunk(0x3f76e4);
        RecordingEventSink failing = new RecordingEventSink(true, false);
        sinkRef = new AtomicReference<>(failing);
        palette = new Palette();
        TileQueue q = new TileQueue(adapter, c, palette, sinkRef::get, store);
        assertFalse(q.palettePublished(), "初始未发布调色板");
        enqueueChunks(q, 0, 0, 1, 0, 0, 1, 1, 1);
        long now = System.currentTimeMillis() + 10_000;
        assertDoesNotThrow(() -> q.onTick(now));
        assertEquals(List.of("map_palette"), failing.subTypes(), "调色板发送失败即止，本轮无 map_tile");
        assertFalse(q.palettePublished(), "发送失败 → paletteSent 不置位");
        byte[] rgb = store.readRegion(DIM, 0, 0);
        assertNotNull(rgb, "写穿独立于发送成败：区域文件已存在");
        assertEquals(MapStore.PAYLOAD_BYTES, rgb.length);
        assertEquals((byte) 0x3f, rgb[0], "块(0,0)首像素 R=0x3f（原样 RGB24，非量化）");
        assertEquals((byte) 0x76, rgb[1]);
        assertEquals((byte) 0xe4, rgb[2]);
        okSink = new RecordingEventSink(true, true);
        sinkRef.set(okSink); // 通道恢复
        q.onTick(now);       // 退回批次重发
        assertEquals(List.of("map_palette", "map_tile"), okSink.subTypes(), "退回后重发成功");
        assertTrue(q.palettePublished());
        RecordingEventSink second = new RecordingEventSink(true, true);
        assertTrue(q.publishPalette(second), "已发布 → 幂等返回 true");
        assertTrue(second.sent.isEmpty(), "重复 publishPalette 不再发 MapPaletteEvent");
    }

    @Test
    @Tag("REQ-PAL-003")
    @DisplayName("首发前 republishPalette 返回 false 零事件；首发后重发事件 id/colors 与首发逐一相同且不改发布/冻结态；send 恒 false 时重发返回 false")
    void republishPaletteOnlyAfterFirstPublishWithIdenticalSnapshot() throws IOException {
        TileQueue q = newQueue(cfg("batch_size: 4"));
        // ① 首发成功前禁止重发：返回 false 且零事件（防把未注册真实色的半空调色板发出并冻结）
        assertFalse(q.republishPalette(okSink), "首发未成功 → republishPalette 必须返回 false");
        assertTrue(okSink.sent.isEmpty(), "首发前重发必须零事件");
        assertFalse(q.palettePublished(), "重发尝试不影响首发状态");
        // ② 一次 flush 令首发成功，记下首个 MapPaletteEvent 的 id 与 colors 快照
        enqueueChunks(q, 0, 0, 1, 0, 0, 1, 1, 1);
        q.onTick(System.currentTimeMillis() + 10_000);
        assertTrue(q.palettePublished(), "flush 成功 → 调色板已首发");
        MapPaletteEvent first = paletteEvents(okSink).get(0);
        String firstId = first.getId();
        List<String> firstColors = first.getColors();
        // ③ 再重发两次：均成功，新增 MapPaletteEvent 的 id 与 colors 与首发逐一相同
        assertTrue(q.republishPalette(okSink), "已首发 → 重发成功（第 1 次）");
        assertTrue(q.republishPalette(okSink), "已首发 → 重发成功（第 2 次）");
        List<MapPaletteEvent> palettes = paletteEvents(okSink);
        assertEquals(3, palettes.size(), "首发 1 条 + 重发 2 条");
        for (int i = 1; i < palettes.size(); i++) {
            assertEquals(firstId, palettes.get(i).getId(), "重发事件 id 与首发一致");
            assertEquals(firstColors, palettes.get(i).getColors(), "重发事件 colors 与首发逐一相同");
        }
        // ④ 重发不改变 palettePublished 与调色板冻结态
        assertTrue(q.palettePublished(), "重发后 palettePublished 仍为 true");
        assertTrue(palette.isFrozen(), "重发后 Palette 仍冻结");
        // ⑤ 通道失败：send 恒 false 的 sink 上重发返回 false（首发状态不受影响）
        RecordingEventSink failing = new RecordingEventSink(true, false);
        assertFalse(q.republishPalette(failing), "send 恒 false → republishPalette 返回 false");
        assertTrue(q.palettePublished(), "重发失败不改变已发布状态");
    }
}
