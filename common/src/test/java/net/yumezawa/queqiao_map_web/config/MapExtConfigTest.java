package net.yumezawa.queqiao_map_web.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** REQ-CFG-*：queqiao_map_web.yml 极简解析与默认值契约（纯 JDK）。 */
class MapExtConfigTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(MapExtConfig.FILE_NAME);
    }

    private MapExtConfig writeAndLoad(String... lines) throws IOException {
        Files.writeString(file(), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return MapExtConfig.load(file());
    }

    @Test
    @Tag("REQ-CFG-001")
    @DisplayName("配置文件缺失 → 全默认（enabled=false）并写出默认文件")
    void missingFileYieldsAllDefaults() throws IOException {
        MapExtConfig c = MapExtConfig.load(file());
        assertFalse(c.enabled, "默认总开关必须关（零流量铁律）");
        assertEquals(64, c.batchSize);
        assertEquals(1000L, c.flushIntervalMs);
        assertEquals(1.5, c.moveThreshold);
        assertEquals(400L, c.moveActiveIntervalMs);
        assertEquals(1500L, c.moveIdleIntervalMs);
        assertEquals(64, c.maxChunkRendersPerTick);
        assertEquals(1024, c.pendingCap);
        assertEquals("vanilla-1.20.1-a", c.paletteId);
        assertEquals(5000L, c.dimensionCheckIntervalMs);
        assertTrue(Files.exists(file()), "缺文件时应写出默认配置供用户编辑");
        assertTrue(Files.readString(file(), StandardCharsets.UTF_8).contains("enabled: false"));
    }

    @Test
    @Tag("REQ-CFG-002")
    @DisplayName("yml 解析：enabled 布尔与全部数值项生效；整行注释、行尾注释、未知键一律忽略")
    void parsesValuesAndIgnoresCommentsAndUnknownKeys() throws IOException {
        MapExtConfig c = writeAndLoad(
                "# 整行注释",
                "enabled: true            # 行尾注释后仍生效 true",
                "batch_size: 16",
                "flush_interval_ms: 250",
                "move_threshold: 2.25",
                "move_active_interval_ms: 500",
                "move_idle_interval_ms: 2000",
                "max_chunk_renders_per_tick: 8",
                "pending_cap: 32",
                "palette_id: vanilla-1.20.1-b",
                "dimension_check_interval_ms: 6000",
                "totally_unknown_key: whatever"
        );
        assertTrue(c.enabled);
        assertEquals(16, c.batchSize);
        assertEquals(250L, c.flushIntervalMs);
        assertEquals(2.25, c.moveThreshold);
        assertEquals(500L, c.moveActiveIntervalMs);
        assertEquals(2000L, c.moveIdleIntervalMs);
        assertEquals(8, c.maxChunkRendersPerTick);
        assertEquals(32, c.pendingCap);
        assertEquals("vanilla-1.20.1-b", c.paletteId);
        assertEquals(6000L, c.dimensionCheckIntervalMs);
    }

    @Test
    @Tag("REQ-CFG-003")
    @DisplayName("数值解析失败 → 保持默认值且不抛异常；非布尔 enabled → false")
    void illegalNumbersKeepDefaults() throws IOException {
        MapExtConfig c = writeAndLoad(
                "enabled: not_a_bool",
                "batch_size: abc",
                "flush_interval_ms: 1s",
                "move_threshold: wide",
                "pending_cap:",
                "palette_id: ok-value"
        );
        assertFalse(c.enabled, "Boolean.parseBoolean：非 true 值一律 false（与默认一致）");
        assertEquals(64, c.batchSize, "解析失败 → 保持默认");
        assertEquals(1000L, c.flushIntervalMs);
        assertEquals(1.5, c.moveThreshold);
        assertEquals(1024, c.pendingCap, "空值 → 解析失败 → 保持默认");
        assertEquals("ok-value", c.paletteId, "字符串项原样取用");
    }

    @Test
    @Tag("REQ-CFG-003")
    @DisplayName("可解析但越界的数值 → 钳制到下限（源码 Math.max 钳制），不抛异常")
    void outOfRangeValuesClamped() throws IOException {
        MapExtConfig c = writeAndLoad(
                "batch_size: 0",
                "flush_interval_ms: 1",
                "move_active_interval_ms: 1",
                "move_idle_interval_ms: 100",
                "max_chunk_renders_per_tick: 0",
                "pending_cap: 1",
                "dimension_check_interval_ms: 1"
        );
        assertEquals(1, c.batchSize);
        assertEquals(100L, c.flushIntervalMs);
        assertEquals(100L, c.moveActiveIntervalMs);
        assertEquals(500L, c.moveIdleIntervalMs);
        assertEquals(1, c.maxChunkRendersPerTick);
        assertEquals(16, c.pendingCap);
        assertEquals(1000L, c.dimensionCheckIntervalMs);
    }

    @Test
    @Tag("REQ-CFG-004")
    @DisplayName("地图库五新键：默认值、DEFAULT_YML 含五键、显式解析生效")
    void newMapStoreKeysParsed() throws IOException {
        MapExtConfig def = MapExtConfig.load(file());
        assertTrue(def.backfillEnabled, "backfill_enabled 默认 true（仍受总开关门控）");
        assertEquals(512, def.backfillMaxChunksPerSec);
        assertTrue(def.regionStreamEnabled);
        assertEquals(65536, def.regionStreamBytesPerSec);
        assertEquals(4, def.regionStreamMaxRegionsPerEvent);
        String yml = Files.readString(file(), StandardCharsets.UTF_8);
        for (String key : new String[]{"backfill_enabled", "backfill_max_chunks_per_sec",
                "region_stream_enabled", "region_stream_bytes_per_sec", "region_stream_max_regions_per_event"}) {
            assertTrue(yml.contains(key + ":"), "DEFAULT_YML 必须含键 " + key);
        }
        MapExtConfig c = writeAndLoad(
                "backfill_enabled: false",
                "backfill_max_chunks_per_sec: 64",
                "region_stream_enabled: false",
                "region_stream_bytes_per_sec: 8192",
                "region_stream_max_regions_per_event: 2"
        );
        assertFalse(c.backfillEnabled);
        assertEquals(64, c.backfillMaxChunksPerSec);
        assertFalse(c.regionStreamEnabled);
        assertEquals(8192, c.regionStreamBytesPerSec);
        assertEquals(2, c.regionStreamMaxRegionsPerEvent);
    }

    @Test
    @Tag("REQ-CFG-004")
    @DisplayName("新键越界钳制：≥16 / ≥4096 / 1..16；解析失败保持默认")
    void newMapStoreKeysClamped() throws IOException {
        MapExtConfig c = writeAndLoad(
                "backfill_max_chunks_per_sec: 1",
                "region_stream_bytes_per_sec: 0",
                "region_stream_max_regions_per_event: 99"
        );
        assertEquals(16, c.backfillMaxChunksPerSec);
        assertEquals(4096, c.regionStreamBytesPerSec);
        assertEquals(16, c.regionStreamMaxRegionsPerEvent);
        MapExtConfig d = writeAndLoad(
                "region_stream_bytes_per_sec: abc",
                "region_stream_max_regions_per_event: -5"
        );
        assertEquals(65536, d.regionStreamBytesPerSec, "解析失败 → 保持默认");
        assertEquals(1, d.regionStreamMaxRegionsPerEvent, "-5 → 钳制下限 1");
    }
}
