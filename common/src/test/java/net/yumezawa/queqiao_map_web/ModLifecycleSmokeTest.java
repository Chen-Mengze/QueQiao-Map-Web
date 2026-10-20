package net.yumezawa.queqiao_map_web;

import net.yumezawa.queqiao_map_web.config.MapExtConfig;
import net.yumezawa.queqiao_map_web.event.MapDimensionsEvent;
import net.yumezawa.queqiao_map_web.event.MapPaletteEvent;
import net.yumezawa.queqiao_map_web.event.MapTileEvent;
import net.yumezawa.queqiao_map_web.model.TilePayloadModel;
import net.yumezawa.queqiao_map_web.platform.PlatformAdapter.ChunkPos;
import net.yumezawa.queqiao_map_web.sender.EventSink;
import net.yumezawa.queqiao_map_web.testsupport.FakePlatformAdapter;
import net.yumezawa.queqiao_map_web.testsupport.QueqiaoToolBootstrap;
import net.yumezawa.queqiao_map_web.testsupport.RecordingEventSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JVM 内冒烟：手写 Fake 平台适配器 + 记录发送器，在单 JVM 跑 ModLifecycle
 * 「start → tick 若干 → stop」全生命周期。
 * ModLifecycle 是进程级单例：用例按 @Order 顺序执行，并在前后通过反射
 * 重置不可注入的私有 sinkRef（测试缝缺口已向 lead 报告）。
 */
@Tag("smoke")
@Timeout(15)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModLifecycleSmokeTest {

    private static final String DIM = "minecraft:overworld";

    @TempDir
    Path dir;

    private final ModLifecycle lifecycle = ModLifecycle.get();
    private FakePlatformAdapter adapter;

    @BeforeEach
    void resetSingleton() throws Exception {
        // 主代码 onServerTick/flush 构造事件 DTO：BaseEvent 硬依赖 GlobalContext.getConfig()
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

    /** sinkRef 不可注入（start() 内部 new QueQiaoSink），测试用反射拿引用后替换 */
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

    private RecordingEventSink injectSink(boolean ready, boolean sendOk) throws Exception {
        RecordingEventSink s = new RecordingEventSink(ready, sendOk);
        sinkRef().set(s);
        return s;
    }

    /** 直接调已注册的 onChunkDirty 回调 = 模拟方块变化事件源（同 8x8 区域 4 块） */
    private void enqueue4Chunks() {
        adapter.dirtyListener.onChunkDirty(DIM, new ChunkPos(0, 0));
        adapter.dirtyListener.onChunkDirty(DIM, new ChunkPos(1, 0));
        adapter.dirtyListener.onChunkDirty(DIM, new ChunkPos(0, 1));
        adapter.dirtyListener.onChunkDirty(DIM, new ChunkPos(1, 1));
    }

    private static FakePlatformAdapter.PlayerSnapshot player(double x) {
        return FakePlatformAdapter.snapshot("u1", "Yumezawa", x, 64.0, 0, 0f, DIM);
    }

    @Test
    @Order(1)
    @Tag("REQ-LIFE-001")
    @DisplayName("enabled=false：除自检日志外零发送零装配——不注册 tick/脏块监听、不装 sink、stop 空跑安全")
    void disabledMeansZeroAssemblyAndZeroTraffic() throws Exception {
        writeConfig("enabled: false");
        lifecycle.start(adapter, dir);
        assertNull(adapter.tickConsumer, "不得注册 tick 监听");
        assertNull(adapter.dirtyListener, "不得注册脏块监听");
        assertNull(sinkRef().get(), "不得装配发送器");
        assertDoesNotThrow(() -> lifecycle.stop());
        assertEquals(0, adapter.renderCalls);
        assertEquals(0, adapter.runAsyncCalls);
        assertEquals(0, adapter.listDimensionsCalls);
    }

    @Test
    @Order(2)
    @Tag("REQ-LIFE-002")
    @DisplayName("enabled=true 但鹊桥未就绪（isReady=false）：完全静默——零发送、零渲染、零后台任务、零维度枚举")
    void notReadyMeansTotalSilence() throws Exception {
        writeConfig("enabled: true");
        lifecycle.start(adapter, dir);
        assertNotNull(adapter.tickConsumer, "enabled 后应完成装配");
        RecordingEventSink sink = injectSink(false, true);
        for (int i = 0; i < 10; i++) {
            adapter.tickConsumer.accept(List.of(player(0)));
        }
        assertTrue(sink.sent.isEmpty(), "未就绪 → 零发送（连失败尝试都没有）");
        assertEquals(0, adapter.renderCalls);
        assertEquals(0, adapter.runAsyncCalls);
        assertEquals(0, adapter.listDimensionsCalls);
        assertDoesNotThrow(() -> lifecycle.stop());
    }

    @Test
    @Order(3)
    @Tag("REQ-LIFE-003")
    @DisplayName("停服路径：任一 send 失败 → 排空立即停止——不死循环、不抛异常（adapter.shutdown→drain 的顺序本身由各加载器入口完成）")
    void stopDrainStopsAtFirstSendFailure() throws Exception {
        writeConfig("enabled: true", "batch_size: 4");
        lifecycle.start(adapter, dir);
        RecordingEventSink sink = injectSink(true, false); // 通道「就绪」但每次 send 都失败
        enqueue4Chunks();
        assertDoesNotThrow(() -> adapter.tickConsumer.accept(List.of()));
        assertEquals(List.of("map_palette", "map_dimensions"), sink.subTypes(),
                "首个 tick：瓦片 flush 尝试失败并退回队列，维度上报也失败");
        int attemptsAfterTick = sink.sent.size();
        assertDoesNotThrow(() -> lifecycle.stop()); // 若排空不中断，@Timeout(15) 会让本用例失败
        assertEquals(attemptsAfterTick + 1, sink.sent.size(),
                "drain 只做一轮失败尝试即 break（palette 发送 false → 退回 → 跳出）");
        assertEquals(List.of("map_palette", "map_dimensions", "map_palette"), sink.subTypes());
        assertEquals(8, adapter.renderCalls, "tick 一轮 4 块 + drain 一轮 4 块，此后不再空转");
    }

    @Test
    @Order(4)
    @Tag("REQ-LIFE-004")
    @DisplayName("send=false：player_move 静默丢弃、瓦片批静默退回，钩子不抛异常；通道恢复后退回批全量重发")
    void sendFalseDropsOrRequeuesWithoutThrowing() throws Exception {
        writeConfig("enabled: true", "batch_size: 4");
        lifecycle.start(adapter, dir);
        RecordingEventSink failing = injectSink(true, false);
        enqueue4Chunks();
        assertDoesNotThrow(() -> adapter.tickConsumer.accept(List.of(player(0))));
        assertEquals(List.of("player_move", "map_palette", "map_dimensions"), failing.subTypes(),
                "player_move 发送失败即丢，不抛出；瓦片批退回");
        RecordingEventSink ok = injectSink(true, true);
        assertDoesNotThrow(() -> adapter.tickConsumer.accept(List.of(player(0))));
        assertEquals(List.of("map_palette", "map_tile"), ok.subTypes(), "通道恢复 → 退回批全量重发");
        assertEquals(8, adapter.renderCalls);
        assertDoesNotThrow(() -> lifecycle.stop());
    }

    @Test
    @Order(5)
    @Tag("REQ-PROTO-001")
    @Tag("REQ-PROTO-003")
    @DisplayName("全生命周期冒烟：start→tick→stop（enabled=true 且 ready=true），按序 player_move→map_palette→map_tile→map_dimensions；瓦片 size=8、dimension 为 registry key；stop 不抛异常")
    void fullLifecycleEmitsEventsInOrder() throws Exception {
        writeConfig("enabled: true", "batch_size: 4");
        lifecycle.start(adapter, dir);
        RecordingEventSink sink = injectSink(true, true);
        enqueue4Chunks();
        adapter.tickConsumer.accept(List.of(player(10.5)));
        assertEquals(List.of("player_move", "map_palette", "map_tile", "map_dimensions"), sink.subTypes(),
                "一个 tick 内四类事件按源码时序出现（dimensions 在末位）");

        MapPaletteEvent pe = (MapPaletteEvent) sink.sent.get(1);
        assertEquals("vanilla-1.20.1-a", pe.getId());
        assertEquals("#000000", pe.getColors().get(0), "调色板下标 0 = 未设置（pack 量化先于调色板快照的时序由 TileQueueTest 守护）");

        MapTileEvent te = (MapTileEvent) sink.sent.get(2);
        assertEquals(DIM, te.getDimension());
        assertEquals(1, te.getTiles().size());
        TilePayloadModel t = te.getTiles().get(0);
        assertEquals(8, t.getSize());
        assertEquals(0, t.getCx());
        assertEquals(0, t.getCz());
        assertEquals("zlib-pal8", t.getFormat());
        assertTrue(t.getB64() != null && !t.getB64().isEmpty());
        assertTrue(t.getSha1().matches("[0-9a-f]{40}"));

        MapDimensionsEvent de = (MapDimensionsEvent) sink.sent.get(3);
        assertEquals(List.of(DIM), de.getDimensions());

        // 相同状态再 tick：采样未到点、队列已空、维度未变 → 无新事件
        adapter.tickConsumer.accept(List.of(player(10.5)));
        assertEquals(4, sink.sent.size());

        assertDoesNotThrow(() -> lifecycle.stop());
    }

    @Test
    @Order(6)
    @Tag("REQ-LIFE-005")
    @DisplayName("地图库引擎双门：worldSaveRoot=null→全 null；未就绪不启动；就绪后各起一次（幂等门）；双开关全关→store 不建")
    void enginesGatedOnReadyAndFlags() throws Exception {
        // ① worldSaveRoot=null（Fake 默认）：即使就绪，整链路 null（不建任何引擎线程）
        writeConfig("enabled: true");
        lifecycle.start(adapter, dir);
        injectSink(false, true);
        adapter.tickConsumer.accept(List.of());
        assertNull(lifecycle.mapStore(), "worldSaveRoot=null → 不创建 MapStore");
        assertNull(lifecycle.backfillEngine());
        assertNull(lifecycle.regionStreamer());
        injectSink(true, true);
        adapter.tickConsumer.accept(List.of());
        assertNull(lifecycle.backfillEngine(), "无 store → 就绪也不建引擎");
        assertNull(lifecycle.regionStreamer());
        assertDoesNotThrow(() -> lifecycle.stop());

        // ② 有 root：未就绪→不启动；就绪→各起一次（一次性门；再 tick 不换实例）
        adapter = new FakePlatformAdapter();
        adapter.solidChunk(0x3f76e4);
        adapter.worldSaveRootDir = dir;
        lifecycle.start(adapter, dir);
        assertNotNull(lifecycle.mapStore(), "root 非 null 且开关默认 true → store 已建");
        injectSink(false, true);
        adapter.tickConsumer.accept(List.of());
        assertNull(lifecycle.backfillEngine(), "未就绪 → 引擎不启动（就绪门）");
        assertNull(lifecycle.regionStreamer());
        injectSink(true, true);
        adapter.tickConsumer.accept(List.of());
        var backfill = lifecycle.backfillEngine();
        var streamer = lifecycle.regionStreamer();
        assertNotNull(backfill, "backfill_enabled 默认 true → 已启动");
        assertNotNull(streamer, "region_stream_enabled 默认 true → 已启动");
        adapter.tickConsumer.accept(List.of());
        assertSame(backfill, lifecycle.backfillEngine(), "各启动一次（一次性门）");
        assertSame(streamer, lifecycle.regionStreamer());
        assertDoesNotThrow(() -> lifecycle.stop());
        assertFalse(backfill.isRunning(), "stop 返回后回填线程已退出");
        assertFalse(streamer.isRunning(), "stop 返回后区域流线程已退出");

        // ③ 双开关全关：有 root 也不建 store
        adapter = new FakePlatformAdapter();
        adapter.solidChunk(0x3f76e4);
        adapter.worldSaveRootDir = dir;
        writeConfig("enabled: true", "backfill_enabled: false", "region_stream_enabled: false");
        lifecycle.start(adapter, dir);
        assertNull(lifecycle.mapStore(), "两个 enabled 均 false → 不建 store（enabled 门）");
        injectSink(true, true);
        adapter.tickConsumer.accept(List.of());
        assertNull(lifecycle.backfillEngine());
        assertNull(lifecycle.regionStreamer());
        assertDoesNotThrow(() -> lifecycle.stop());
    }

    @Test
    @Order(7)
    @Tag("REQ-PAL-003")
    @DisplayName("调色板周期重发接线：间隔到点后的 tick 重发同一 id+colors 快照（晚接入自愈）；未到点不重发；0=关闭零重发")
    void paletteRepublishedEveryInterval() throws Exception {
        writeConfig("enabled: true", "batch_size: 4", "palette_republish_interval_ms: 1000");
        lifecycle.start(adapter, dir);
        RecordingEventSink sink = injectSink(true, true);
        enqueue4Chunks();
        adapter.tickConsumer.accept(List.of()); // 首个就绪 tick：首发调色板 + map_tile + map_dimensions
        assertEquals(List.of("map_palette", "map_tile", "map_dimensions"), sink.subTypes());
        MapPaletteEvent first = (MapPaletteEvent) sink.sent.get(0);
        int afterFirst = sink.sent.size();
        adapter.tickConsumer.accept(List.of());
        assertEquals(afterFirst, sink.sent.size(), "未到重发间隔 → 无新事件（队列空、维度未变、间隔未到）");
        Thread.sleep(1100);
        adapter.tickConsumer.accept(List.of());
        assertEquals(afterFirst + 1, sink.sent.size(), "间隔到点 → 恰好重发一次");
        MapPaletteEvent again = (MapPaletteEvent) sink.sent.get(sink.sent.size() - 1);
        assertEquals("map_palette", again.getSubType());
        assertEquals(first.getId(), again.getId(), "重发同 palette_id");
        assertEquals(first.getColors(), again.getColors(), "重发快照 colors 逐一相同（消费端幂等前提）");
        assertDoesNotThrow(() -> lifecycle.stop());

        // 0 = 关闭：palette_republish_interval_ms=0 → 任何 tick 都不得周期重发，全场恰一次首发
        adapter = new FakePlatformAdapter();
        adapter.solidChunk(0x3f76e4);
        writeConfig("enabled: true", "batch_size: 4", "palette_republish_interval_ms: 0");
        lifecycle.start(adapter, dir);
        RecordingEventSink offSink = injectSink(true, true);
        enqueue4Chunks();
        adapter.tickConsumer.accept(List.of());
        adapter.tickConsumer.accept(List.of());
        assertEquals(1, offSink.subTypes().stream().filter(s -> s.equals("map_palette")).count(),
                "palette_republish_interval_ms=0 → 周期重发关闭");
        assertDoesNotThrow(() -> lifecycle.stop());
    }
}
