package net.yumezawa.queqiao_map_web.mapstore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回填引擎（蓝图 §2.6）：每会话一个后台守护线程，把世界存档里已有的 mca 区域解析成
 * RGB24 全区域写入 {@link MapStore}，让"开包即有全图"，不依赖在线采样攒像素。
 *
 * <p>每轮扫描：对 {@link MapStore#listDimensions()} 的每个维度，mca 目录 =
 * overworld → worldSaveRoot/region；其余 → worldSaveRoot/dimension/&lt;ns&gt;_&lt;path&gt;/region。
 * 扫描 r.&lt;rx&gt;.&lt;rz&gt;.mca：若 store 已有该区域且 mca 不比它更新 → 整文件跳过（增量）；
 * 否则逐块 listChunks → readChunkNbt → {@link NbtReader} → {@link ChunkTopScanner#scanTopStates}
 * 得 256 列顶面状态，逐列 1:1 上色（未收录方块走 BlockColors 灰兜底，无地面列与缺块像素保持 0），
 * 拼成 512×512 RGB24 后一次 {@link MapStore#putRegion}。</p>
 *
 * <p>节流：令牌桶，构造器参数 maxChunksPerSec（MapExtConfig 默认 512，下限 clamp 由 config 层负责，
 * 本类只把 &lt;=0 兜底为 1）。一切异常吞掉记 FINE；stop() 置位 + interrupt，由 ModLifecycle.stop() 在
 * streamer 停止之后调用（adapter.shutdown 已由加载器入口先行，见 REQ-MS-008）。</p>
 */
public final class BackfillEngine {

    private static final Logger LOG = Logger.getLogger("queqiao_map_web.mapstore");

    /** r.<rx>.<rz>.mca 文件名模式 */
    private static final Pattern MCA_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    /** 未指定维度时的最低建筑高度（1.18+ overworld） */
    private static final int DEFAULT_MIN_BUILD_HEIGHT = -64;

    private final MapStore store;
    private final Path worldSaveRoot;
    private final int maxChunksPerSec;
    private final long idleIntervalMs;

    private volatile boolean running;
    private Thread worker;

    /** 令牌桶状态（仅回填线程/测试线程单线程访问） */
    private double tokens;
    private long lastRefillNanos;

    /** 默认扫描间隔 30s（与区域流巡检一致） */
    public BackfillEngine(MapStore store, Path worldSaveRoot, int maxChunksPerSec) {
        this(store, worldSaveRoot, maxChunksPerSec, 30_000L);
    }

    /**
     * @param store           地图库
     * @param worldSaveRoot   世界存档根（与 MapStore 构造参数同一目录）
     * @param maxChunksPerSec 节流：每秒最多解析的块数（令牌桶容量）
     * @param idleIntervalMs  一轮完整扫描后的休眠毫秒数
     */
    public BackfillEngine(MapStore store, Path worldSaveRoot, int maxChunksPerSec, long idleIntervalMs) {
        this.store = store;
        this.worldSaveRoot = worldSaveRoot;
        this.maxChunksPerSec = Math.max(1, maxChunksPerSec);
        this.idleIntervalMs = Math.max(100L, idleIntervalMs);
    }

    /** 启动后台守护线程（幂等）。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        tokens = 0.0;
        lastRefillNanos = 0L;
        worker = new Thread(this::runLoop, "queqiao-mapstore-backfill");
        worker.setDaemon(true);
        worker.start();
        LOG.log(Level.CONFIG, "回填引擎启动: maxChunksPerSec={0}", maxChunksPerSec);
    }

    /** 停止：置位 + interrupt + 短 join（异常吞掉）。 */
    public synchronized void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.log(Level.CONFIG, "回填引擎已停止");
    }

    /** 是否在运行（诊断/测试用） */
    public boolean isRunning() {
        return running;
    }

    private void runLoop() {
        while (running) {
            try {
                runScanCycle();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "回填扫描轮异常，继续下一轮", e);
            }
            if (!running) {
                break;
            }
            try {
                Thread.sleep(idleIntervalMs);
            } catch (InterruptedException e) {
                break; // stop() 的 interrupt
            }
        }
    }

    /** 完整扫一轮所有维度（包私有便于测试同步调用） */
    void runScanCycle() {
        for (String dim : store.listDimensions()) {
            Path mcaDir = mcaDirFor(dim);
            if (!Files.isDirectory(mcaDir)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mcaDir, "r.*.mca")) {
                for (Path mca : ds) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    scanRegionFile(mca);
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "扫描 mca 目录失败: " + mcaDir, e);
            }
        }
    }

    /** 单个 mca 区域文件：增量判定 → 逐块解析逐列上色 → 整区域写入 */
    private void scanRegionFile(Path mcaFile) {
        Matcher m = MCA_NAME.matcher(mcaFile.getFileName().toString());
        if (!m.matches()) {
            return;
        }
        String dim = dimForRegionFile(mcaFile);
        int rx = Integer.parseInt(m.group(1));
        int rz = Integer.parseInt(m.group(2));
        if (dim == null) {
            return;
        }
        // 增量：store 已有且 mca 不更新 → 跳过整文件
        if (store.header(dim, rx, rz) != null && !store.isRegionFileNewer(dim, rx, rz, mcaFile)) {
            return;
        }
        byte[] pixels = new byte[MapStore.PAYLOAD_BYTES];
        int minBuildHeight = minBuildHeightFor(dim);
        int painted = 0;
        for (RegionFileReader.ChunkLocation loc : RegionFileReader.listChunks(mcaFile)) {
            acquirePermit(); // 令牌桶节流
            String[] top = topStatesForChunk(mcaFile, loc, minBuildHeight);
            if (top == null) {
                continue;
            }
            paintChunk(pixels, loc, top);
            painted++;
        }
        if (painted > 0) {
            store.putRegion(dim, rx, rz, pixels);
            LOG.log(Level.FINE, "回填区域 {0} r.{1}.{2}: {3} 块", new Object[]{dim, rx, rz, painted});
        }
    }

    /** 由 mca 所在目录反推注册键（region 根 = overworld；dimension/<folder>/region = folderToDim） */
    private String dimForRegionFile(Path mcaFile) {
        Path regionDir = mcaFile.getParent();
        if (regionDir == null) {
            return null;
        }
        Path rootDir = worldSaveRoot.toAbsolutePath().normalize();
        Path overworldRegion = rootDir.resolve("region");
        Path abs = regionDir.toAbsolutePath().normalize();
        if (abs.equals(overworldRegion)) {
            return "minecraft:overworld";
        }
        Path dimParent = rootDir.resolve("dimension");
        Path rel = dimParent.relativize(abs); // "<folder>/region"
        if (rel.getNameCount() == 2 && rel.endsWith(regionDir.getFileName())) {
            return MapStore.folderToDim(rel.getName(0).toString());
        }
        LOG.log(Level.FINE, "mca 目录不在约定的 region 位置，跳过: {0}", regionDir);
        return null;
    }

    /** 读一个块 → 256 列顶面状态名；任何失败返回 null（记 FINE） */
    private String[] topStatesForChunk(Path mcaFile, RegionFileReader.ChunkLocation loc, int minBuildHeight) {
        try {
            byte[] nbtBytes = RegionFileReader.readChunkNbt(mcaFile, loc);
            var root = NbtReader.readCompound(new ByteArrayInputStream(nbtBytes));
            return ChunkTopScanner.scanTopStates(root, minBuildHeight);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "解析块失败: " + mcaFile + " chunk=" + loc.x() + "," + loc.z(), e);
            return null;
        }
    }

    /** 256 列顶面 → 该块 16×16 像素逐列 1:1 上色（区域网格 = 每方块一像素，与 putChunk 的 768B 补丁同一几何；null 列保持 0） */
    private static void paintChunk(byte[] pixels, RegionFileReader.ChunkLocation loc, String[] top) {
        int colOrigin = (loc.x() & 31) * 16;
        int rowOrigin = (loc.z() & 31) * 16;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                String state = top[lz * 16 + lx];
                if (state == null) {
                    continue;
                }
                int rgb = BlockColors.colorOf(state);
                int p = (((rowOrigin + lz) * MapStore.SIDE) + colOrigin + lx) * 3;
                pixels[p] = (byte) (rgb >> 16);
                pixels[p + 1] = (byte) ((rgb >> 8) & 0xFF);
                pixels[p + 2] = (byte) (rgb & 0xFF);
            }
        }
    }

    /** mca 目录：overworld → 存档根/region；其余 → 存档根/dimension/<ns>_<path>/region（蓝图 §2.6） */
    Path mcaDirFor(String dim) {
        String folder = MapStore.dimFolder(dim);
        if (folder.equals("overworld")) {
            return worldSaveRoot.resolve("region");
        }
        return worldSaveRoot.resolve("dimension").resolve(folder).resolve("region");
    }

    /** 维度最低建筑高度：the_nether/the_end → 0，其余按 1.18+ overworld 的 -64
     * （dimFolder 恒补命名空间前缀，故只需比带前缀的形式） */
    private static int minBuildHeightFor(String dim) {
        String f = MapStore.dimFolder(dim);
        if (f.equals("minecraft_the_nether") || f.equals("minecraft_the_end")) {
            return 0;
        }
        return DEFAULT_MIN_BUILD_HEIGHT;
    }

    /** 令牌桶：容量 maxChunksPerSec，按时间补充；不足则 sleep。只在回填线程内使用。 */
    private void acquirePermit() {
        long now = System.nanoTime();
        if (lastRefillNanos == 0L) {
            lastRefillNanos = now;
            tokens = maxChunksPerSec; // 初始满桶
        }
        tokens += (now - lastRefillNanos) / 1_000_000_000.0 * maxChunksPerSec;
        lastRefillNanos = now;
        if (tokens > maxChunksPerSec) {
            tokens = maxChunksPerSec;
        }
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return;
        }
        long waitMs = (long) Math.ceil((1.0 - tokens) / maxChunksPerSec * 1000.0);
        try {
            Thread.sleep(Math.max(1L, waitMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        tokens = 0.0;
    }
}
