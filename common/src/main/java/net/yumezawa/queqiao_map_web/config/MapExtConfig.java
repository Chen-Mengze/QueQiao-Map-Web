package net.yumezawa.queqiao_map_web.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * 扩展配置：开关 / 节流 / 批量参数。
 * 纯 JDK 实现（极简 yml 解析），不依赖任何加载器与 MC 类，三个平台共用。
 * 本 mod 是鹊桥的附属扩展：鹊桥是唯一发送通道，没有可配置的通道切换。
 * 铁律：enabled 默认 false —— 不开启时本 mod 除一条自检日志外零行为、零流量。
 */
public final class MapExtConfig {

    /** 配置挂到鹊桥本体的配置子目录：config/QueQiao/ */
    public static final String SUB_DIR = "QueQiao";
    /** 文件名与 mod id 同步 */
    public static final String FILE_NAME = "queqiao_map_web.yml";

    /** 兼容性第 1 层保护：总开关，默认关闭 */
    public boolean enabled = false;
    /** 一批瓦片的区块数上限（64 = 8x8） */
    public int batchSize = 64;
    /** 距上次刷写超过该毫秒数则强制刷 */
    public long flushIntervalMs = 1000;
    /** 位移阈值（格）：小于该位移视为静止 */
    public double moveThreshold = 1.5;
    /** 移动中采样间隔（约 2.5 Hz） */
    public long moveActiveIntervalMs = 400;
    /** 静止心跳间隔（约 0.67 Hz） */
    public long moveIdleIntervalMs = 1500;
    /** 每 tick 顶层方块采样（renderChunk）硬上限 */
    public int maxChunkRendersPerTick = 64;
    /** 待刷写队列长度上限，超出静默丢弃并告警一次 */
    public int pendingCap = 1024;
    /** 调色板标识（MapPaletteEvent 首发一次，之后按 palette_republish_interval_ms 重发同一快照） */
    public String paletteId = "vanilla-1.20.1-a";
    /** 维度列表变化检测间隔 */
    public long dimensionCheckIntervalMs = 5000;
    /** 地图库回填引擎开关（REQ-CFG-004） */
    public boolean backfillEnabled = true;
    /** 回填每秒区块数上限（钳制 ≥16） */
    public int backfillMaxChunksPerSec = 512;
    /** 区域流引擎开关（REQ-CFG-004） */
    public boolean regionStreamEnabled = true;
    /** 区域流每秒压缩字节令牌桶（钳制 ≥4096） */
    public int regionStreamBytesPerSec = 65536;
    /** 单个 map_region 事件区域数上限（钳制 1..16） */
    public int regionStreamMaxRegionsPerEvent = 4;
    /** 已发布调色板的周期重发间隔毫秒（REQ-CFG-005/REQ-PAL-003）：0=关闭重发，其余钳制 ≥1000 */
    public long paletteRepublishIntervalMs = 10000;
    /** 区域重放开关（REQ-CFG-005/REQ-MS-009）：true=就绪首 tick 区域流启动前全库 lastSentXor 归零重放 */
    public boolean regionReplayOnStart = false;

    private static final Logger LOG = Logger.getLogger("queqiao_map_web.config");

    private MapExtConfig() {
    }

    /** 从 yml 加载；文件不存在时写出默认文件并返回默认值 */
    public static MapExtConfig load(Path file) {
        MapExtConfig c = new MapExtConfig();
        if (!Files.exists(file)) {
            try {
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_YML);
            } catch (IOException e) {
                LOG.warning("无法写出默认配置 " + file + ": " + e);
            }
            return c;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty()) continue;
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim();
                String val = line.substring(colon + 1).trim();
                c.apply(key, val);
            }
        } catch (IOException e) {
            LOG.warning("读取配置失败 " + file + ": " + e);
        }
        return c;
    }

    /** 极简 key: value 解析；未知键静默忽略 */
    private void apply(String key, String val) {
        try {
            switch (key) {
                case "enabled" -> enabled = Boolean.parseBoolean(val);
                case "batch_size" -> batchSize = Math.max(1, Integer.parseInt(val));
                case "flush_interval_ms" -> flushIntervalMs = Math.max(100, Long.parseLong(val));
                case "move_threshold" -> moveThreshold = Double.parseDouble(val);
                case "move_active_interval_ms" -> moveActiveIntervalMs = Math.max(100, Long.parseLong(val));
                case "move_idle_interval_ms" -> moveIdleIntervalMs = Math.max(500, Long.parseLong(val));
                case "max_chunk_renders_per_tick" -> maxChunkRendersPerTick = Math.max(1, Integer.parseInt(val));
                case "pending_cap" -> pendingCap = Math.max(16, Integer.parseInt(val));
                case "palette_id" -> paletteId = val;
                case "dimension_check_interval_ms" -> dimensionCheckIntervalMs = Math.max(1000, Long.parseLong(val));
                case "backfill_enabled" -> backfillEnabled = Boolean.parseBoolean(val);
                case "backfill_max_chunks_per_sec" -> backfillMaxChunksPerSec = Math.max(16, Integer.parseInt(val));
                case "region_stream_enabled" -> regionStreamEnabled = Boolean.parseBoolean(val);
                case "region_stream_bytes_per_sec" -> regionStreamBytesPerSec = Math.max(4096, Integer.parseInt(val));
                case "region_stream_max_regions_per_event" -> regionStreamMaxRegionsPerEvent = Math.min(16, Math.max(1, Integer.parseInt(val)));
                case "palette_republish_interval_ms" -> {
                    long v = Long.parseLong(val);
                    paletteRepublishIntervalMs = (v == 0) ? 0 : Math.max(1000, v);
                }
                case "region_replay_on_start" -> regionReplayOnStart = Boolean.parseBoolean(val);
                default -> {
                }
            }
        } catch (NumberFormatException e) {
            LOG.warning("配置项 " + key + " 取值非法: " + val);
        }
    }

    private static final String DEFAULT_YML = """
            # QueQiao Map Web 扩展配置（本 mod 为鹊桥附属扩展，鹊桥为唯一通道）
            # 兼容性第 1 层保护：不打开就一条都不发
            enabled: false
            batch_size: 64
            flush_interval_ms: 1000
            move_threshold: 1.5
            move_active_interval_ms: 400
            move_idle_interval_ms: 1500
            max_chunk_renders_per_tick: 64
            pending_cap: 1024
            palette_id: vanilla-1.20.1-a
            dimension_check_interval_ms: 5000
            # 地图库：回填引擎（首次全量扫描 region 文件建本地库）
            backfill_enabled: true
            backfill_max_chunks_per_sec: 512
            # 地图库：区域流（变化区域经 map_region 事件补传）
            region_stream_enabled: true
            region_stream_bytes_per_sec: 65536
            region_stream_max_regions_per_event: 4
            # 调色板周期重发：首发成功后按该间隔重发同一 id+colors 快照（晚接入/断线重连消费者自愈）；0=关闭
            palette_republish_interval_ms: 10000
            # 区域重放：true=本会话区域流启动前把全部 .qms 的 lastSentXor 归零、全量重放（一次性自愈，用完改回 false）
            region_replay_on_start: false
            """;
}