package net.yumezawa.queqiao_map_web.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * 扩展配置：开关 / 节流 / 批量参数。
 * 纯 JDK 实现（极简 yml 解析），不依赖任何加载器与 MC 类，三个平台共用。
 * 铁律：enabled 默认 false —— 不开启时本 mod 除一条自检日志外零行为、零流量。
 */
public final class MapExtConfig {

    public static final String SINK_QUEQIAO = "queqiao";
    public static final String SINK_STANDALONE = "standalone";
    public static final String FILE_NAME = "queqiao-mapext.yml";

    /** 兼容性第 1 层保护：总开关，默认关闭 */
    public boolean enabled = false;
    /** 发送器：queqiao（首选）| standalone（兜底自建 WS） */
    public String sink = SINK_QUEQIAO;
    /** standalone 兜底的 WS 地址，如 ws://127.0.0.1:6199/ws */
    public String standaloneWsUrl = "";
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
    /** 调色板标识（随 MapPaletteEvent 发送一次） */
    public String paletteId = "vanilla-1.20.1-a";
    /** 维度列表变化检测间隔 */
    public long dimensionCheckIntervalMs = 5000;

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
                case "sink" -> sink = val;
                case "standalone_ws_url" -> standaloneWsUrl = val;
                case "batch_size" -> batchSize = Math.max(1, Integer.parseInt(val));
                case "flush_interval_ms" -> flushIntervalMs = Math.max(100, Long.parseLong(val));
                case "move_threshold" -> moveThreshold = Double.parseDouble(val);
                case "move_active_interval_ms" -> moveActiveIntervalMs = Math.max(100, Long.parseLong(val));
                case "move_idle_interval_ms" -> moveIdleIntervalMs = Math.max(500, Long.parseLong(val));
                case "max_chunk_renders_per_tick" -> maxChunkRendersPerTick = Math.max(1, Integer.parseInt(val));
                case "pending_cap" -> pendingCap = Math.max(16, Integer.parseInt(val));
                case "palette_id" -> paletteId = val;
                case "dimension_check_interval_ms" -> dimensionCheckIntervalMs = Math.max(1000, Long.parseLong(val));
                default -> {
                }
            }
        } catch (NumberFormatException e) {
            LOG.warning("配置项 " + key + " 取值非法: " + val);
        }
    }

    private static final String DEFAULT_YML = """
            # QueQiao Map Web 扩展配置 —— 兼容性第 1 层保护：不打开就一条都不发
            enabled: false
            sink: queqiao
            standalone_ws_url: ""
            batch_size: 64
            flush_interval_ms: 1000
            move_threshold: 1.5
            move_active_interval_ms: 400
            move_idle_interval_ms: 1500
            max_chunk_renders_per_tick: 64
            pending_cap: 1024
            palette_id: vanilla-1.20.1-a
            dimension_check_interval_ms: 5000
            """;
}
