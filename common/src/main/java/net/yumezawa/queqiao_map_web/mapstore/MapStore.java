package net.yumezawa.queqiao_map_web.mapstore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 本地二维地图库（蓝图 §2.5）：世界存档目录下 {@code queqiao_map_web/store/<dimFolder>/r.<rx>.<rz>.qms}。
 *
 * <p>文件格式（全部大端）：magic "QMS1"(4B) | u16 side=512 | u8 formatVer=1 | u32 xor32 |
 * u32 lastSentXor | u64 mtimeMillis | u32 payloadLen | zlib(RGB24 512×512×3 = 786432B)。
 * xor32 = 对解压后 RGB24 负载按 4 字节大端分组做 XOR（未初始化像素为 0）。</p>
 *
 * <p>dimFolder 约定：overworld（含 "minecraft:overworld"）→ "overworld"；其余 → "命名空间_路径"
 * （不带命名空间的裸名按 minecraft: 补齐）。{@link #listDimensions()} 做逆映射还原注册键。</p>
 *
 * <p>线程安全：按维度目录 + 文件粒度加锁（进程内），写入经临时文件 + ATOMIC_MOVE 原子替换。
 * 一切 IO/解析异常吞掉记 FINE：读失败返回 null，写失败静默。</p>
 */
public final class MapStore {

    private static final Logger LOG = Logger.getLogger("queqiao_map_web.mapstore");

    /** 区域边长（像素 / 块数×16） */
    public static final int SIDE = 512;
    /** RGB24 负载字节数：512×512×3 */
    public static final int PAYLOAD_BYTES = SIDE * SIDE * 3;
    /** 单块 patch 字节数：16×16×3 */
    static final int CHUNK_PIXELS = 16 * 16 * 3;
    /** 文件魔数 */
    static final byte[] MAGIC = "QMS1".getBytes(StandardCharsets.US_ASCII);
    /** 格式版本 */
    public static final int FORMAT_VERSION = 1;
    /** 文件头字节数（4+2+1+4+4+8+4） */
    static final int FILE_HEADER_BYTES = 27;
    /** lastSentXor 在文件头中的字节偏移（原地改写用） */
    static final int LAST_SENT_XOR_OFFSET = 11;

    /** r.<rx>.<rz>.qms 文件名模式 */
    private static final Pattern QMS_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.qms$");

    /**
     * 区域文件头信息。
     *
     * @param side         边长（512）
     * @param formatVer    格式版本
     * @param xor32        当前负载 XOR（写端每次重算）
     * @param lastSentXor  最近一次已发送/已广播的 xor32
     * @param mtimeMillis  最近一次写入时刻（epoch millis）
     * @param payloadLen   压缩负载字节数
     */
    public record Header(int side, int formatVer, int xor32, int lastSentXor, long mtimeMillis, int payloadLen) {
    }

    /**
     * 区域清单项。
     *
     * @param rx          区域 X
     * @param rz          区域 Z
     * @param xor32       当前负载 XOR
     * @param lastSentXor 最近已发送 XOR
     * @param mtimeMillis 最近写入时刻
     */
    public record RegionInfo(int rx, int rz, int xor32, int lastSentXor, long mtimeMillis) {
    }

    private final Path storeRoot;
    /** 文件路径 → 进程内互斥锁 */
    private final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();

    /**
     * @param worldSaveRoot 世界存档根目录（PlatformAdapter.worldSaveRoot() 的产出；测试可传 @TempDir）
     */
    public MapStore(Path worldSaveRoot) {
        this.storeRoot = worldSaveRoot.resolve("queqiao_map_web").resolve("store");
    }

    /** store 根目录（诊断/测试用） */
    public Path storeRoot() {
        return storeRoot;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 读取区域完整 RGB24 负载（786432B）。文件缺失/损坏返回 null（记 FINE）。
     */
    public byte[] readRegion(String dim, int rx, int rz) {
        Path file = regionPath(dim, rx, rz);
        synchronized (lockFor(file)) {
            try {
                byte[] raw = Files.readAllBytes(file);
                return decodePayload(raw);
            } catch (IOException e) {
                LOG.log(Level.FINE, "读取区域失败: " + file, e);
                return null;
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "区域内容损坏: " + file, e);
                return null;
            }
        }
    }

    /**
     * 读取区域文件头。缺失/损坏返回 null。
     */
    public Header header(String dim, int rx, int rz) {
        Path file = regionPath(dim, rx, rz);
        synchronized (lockFor(file)) {
            try {
                byte[] raw = Files.readAllBytes(file);
                return parseHeader(raw);
            } catch (IOException e) {
                LOG.log(Level.FINE, "读取区域头失败: " + file, e);
                return null;
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "区域头损坏: " + file, e);
                return null;
            }
        }
    }

    /** 列出维度下所有区域（文件名为 r.<rx>.<rz>.qms 且头可解析）。 */
    public List<RegionInfo> listRegions(String dim) {
        List<RegionInfo> out = new ArrayList<>();
        Path dir = storeRoot.resolve(dimFolder(dim));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                Matcher m = QMS_NAME.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                Header h = header(dim, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                if (h != null) {
                    out.add(new RegionInfo(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                            h.xor32(), h.lastSentXor(), h.mtimeMillis()));
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "列出区域失败: " + dir, e);
        }
        out.sort((a, b) -> a.rx() != b.rx() ? Integer.compare(a.rx(), b.rx()) : Integer.compare(a.rz(), b.rz()));
        return out;
    }

    /** 列出 store 中已有数据的维度（目录名逆映射回注册键：overworld→minecraft:overworld，ns_path→ns:path）。 */
    public List<String> listDimensions() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(storeRoot)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(storeRoot)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) {
                    continue;
                }
                out.add(folderToDim(p.getFileName().toString()));
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "列出维度失败: " + storeRoot, e);
        }
        out.sort(String::compareTo);
        return out;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 写入单块 16×16 像素（768B RGB24），patch 进所属区域并原子替换；区域不存在则以全 0 底色新建。
     * 写入后重算 xor32、更新 mtime。rgb24 长度不是 768 时静默跳过（记 FINE）。
     */
    public void putChunk(String dim, int cx, int cz, byte[] rgb24) {
        if (rgb24 == null || rgb24.length != CHUNK_PIXELS) {
            LOG.log(Level.FINE, "putChunk 像素长度非法: {0}", rgb24 == null ? "null" : rgb24.length);
            return;
        }
        int rx = Math.floorDiv(cx, 32);
        int rz = Math.floorDiv(cz, 32);
        int colOrigin = (cx & 31) * 16;
        int rowOrigin = (cz & 31) * 16;
        Path file = regionPath(dim, rx, rz);
        synchronized (lockFor(file)) {
            byte[] grid = readRegionLockedNoLock(dim, rx, rz);
            if (grid == null) {
                grid = new byte[PAYLOAD_BYTES];
            }
            for (int py = 0; py < 16; py++) {
                int srcRow = py * 16 * 3;
                int dstRow = ((rowOrigin + py) * SIDE + colOrigin) * 3;
                System.arraycopy(rgb24, srcRow, grid, dstRow, 16 * 3);
            }
            writeRegionLockedNoLock(file, grid);
        }
    }

    /**
     * 整区域写入（回填 / 整段刷新用）。rgb 必须恰为 786432B，否则静默跳过（记 FINE）。
     */
    public void putRegion(String dim, int rx, int rz, byte[] rgb) {
        if (rgb == null || rgb.length != PAYLOAD_BYTES) {
            LOG.log(Level.FINE, "putRegion 像素长度非法: {0}", rgb == null ? "null" : rgb.length);
            return;
        }
        Path file = regionPath(dim, rx, rz);
        synchronized (lockFor(file)) {
            writeRegionLockedNoLock(file, rgb);
        }
    }

    /**
     * 记录最近一次已发送的 xor32（原地改写文件头 u32 lastSentXor，不解压负载）。
     */
    public void markSent(String dim, int rx, int rz, int xor32) {
        Path file = regionPath(dim, rx, rz);
        synchronized (lockFor(file)) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                ByteBuffer head = ByteBuffer.allocate(FILE_HEADER_BYTES);
                if (ch.read(head, 0) < FILE_HEADER_BYTES || !hasMagic(head.array())) {
                    LOG.log(Level.FINE, "markSent 目标不是有效 qms: {0}", file);
                    return;
                }
                ByteBuffer patch = ByteBuffer.allocate(4);
                patch.putInt(xor32);
                patch.flip();
                ch.write(patch, LAST_SENT_XOR_OFFSET);
            } catch (IOException e) {
                LOG.log(Level.FINE, "markSent 失败: " + file, e);
            }
        }
    }

    /**
     * mca 文件是否比 store 中该区域更新（回填增量判定）。
     * store 无该区域 → true（必须处理）；mca 无法 stat → false（跳过，下轮再查）。
     */
    public boolean isRegionFileNewer(String dim, int rx, int rz, Path mcaFile) {
        Header h = header(dim, rx, rz);
        if (h == null) {
            return true;
        }
        try {
            FileTime t = Files.getLastModifiedTime(mcaFile);
            return t.toMillis() > h.mtimeMillis();
        } catch (IOException e) {
            LOG.log(Level.FINE, "无法 stat mca: " + mcaFile, e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 区域文件路径（不创建目录） */
    Path regionPath(String dim, int rx, int rz) {
        return storeRoot.resolve(dimFolder(dim)).resolve("r." + rx + "." + rz + ".qms");
    }

    /** 注册键 / 裸名 → store 子目录名 */
    static String dimFolder(String dim) {
        if (dim == null || dim.isEmpty() || dim.equals("overworld") || dim.equals("minecraft:overworld")) {
            return "overworld";
        }
        int colon = dim.indexOf(':');
        if (colon < 0) {
            return "minecraft_" + dim;
        }
        return dim.substring(0, colon) + "_" + dim.substring(colon + 1);
    }

    /** store 子目录名 → 注册键（dimFolder 的逆映射） */
    static String folderToDim(String folder) {
        if (folder.equals("overworld")) {
            return "minecraft:overworld";
        }
        int us = folder.indexOf('_');
        if (us <= 0 || us == folder.length() - 1) {
            return "minecraft:" + folder;
        }
        return folder.substring(0, us) + ":" + folder.substring(us + 1);
    }

    /** 调用方已持锁的区域读取（避免重入锁开销；this.lockFor 语义下重入也安全） */
    private byte[] readRegionLockedNoLock(String dim, int rx, int rz) {
        try {
            byte[] raw = Files.readAllBytes(regionPath(dim, rx, rz));
            return decodePayload(raw);
        } catch (IOException e) {
            return null; // 不存在 = 全 0 底色新建
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "区域负载损坏，按全 0 底色重建", e);
            return null;
        }
    }

    /** 调用方已持锁的原子写：压缩 + 头 + 临时文件 + ATOMIC_MOVE */
    private void writeRegionLockedNoLock(Path file, byte[] payload) {
        try {
            Files.createDirectories(file.getParent());
            int xor = xor32(payload);
            byte[] compressed = zlib(payload);
            ByteBuffer buf = ByteBuffer.allocate(FILE_HEADER_BYTES + compressed.length);
            buf.put(MAGIC);
            buf.putShort((short) SIDE);
            buf.put((byte) FORMAT_VERSION);
            buf.putInt(xor);
            buf.putInt(0); // lastSentXor：新写入尚未发送
            buf.putLong(System.currentTimeMillis());
            buf.putInt(compressed.length);
            buf.put(compressed);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, buf.array(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                LOG.log(Level.FINE, "ATOMIC_MOVE 不可用，退化为普通替换: " + file, atomicUnsupported);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "写入区域失败: " + file, e);
        }
    }

    /** 校验并解压负载；任何损坏抛 RuntimeException/IOException 由调用方吞 */
    private static byte[] decodePayload(byte[] raw) throws IOException {
        Header h = parseHeader(raw);
        if (h == null) {
            throw new IOException("qms 头校验失败");
        }
        int off = FILE_HEADER_BYTES;
        byte[] payload = new byte[PAYLOAD_BYTES];
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(raw, off, h.payloadLen()))) {
            int pos = 0;
            while (pos < PAYLOAD_BYTES) {
                int n = in.read(payload, pos, PAYLOAD_BYTES - pos);
                if (n <= 0) {
                    break;
                }
                pos += n;
            }
        }
        return payload;
    }

    /** 解析并校验文件头；失败返回 null */
    private static Header parseHeader(byte[] raw) {
        if (raw == null || raw.length < FILE_HEADER_BYTES || !hasMagic(raw)) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        buf.position(4);
        int side = buf.getShort() & 0xFFFF;
        int ver = buf.get() & 0xFF;
        int xor = buf.getInt();
        int lastSent = buf.getInt();
        long mtime = buf.getLong();
        int payloadLen = buf.getInt();
        if (side != SIDE || ver != FORMAT_VERSION || payloadLen < 0 || payloadLen > raw.length - FILE_HEADER_BYTES) {
            return null;
        }
        return new Header(side, ver, xor, lastSent, mtime, payloadLen);
    }

    private static boolean hasMagic(byte[] raw) {
        return raw != null && raw.length >= 4 && raw[0] == 'Q' && raw[1] == 'M' && raw[2] == 'S' && raw[3] == '1';
    }

    /** xor32：对负载按 4 字节大端分组 XOR（REQ-MS-005） */
    static int xor32(byte[] payload) {
        int xor = 0;
        for (int i = 0; i + 3 < payload.length; i += 4) {
            int group = ((payload[i] & 0xFF) << 24) | ((payload[i + 1] & 0xFF) << 16)
                    | ((payload[i + 2] & 0xFF) << 8) | (payload[i + 3] & 0xFF);
            xor ^= group;
        }
        return xor;
    }

    private static byte[] zlib(byte[] payload) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(PAYLOAD_BYTES / 4);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(bos, new Deflater(Deflater.DEFAULT_COMPRESSION))) {
            dos.write(payload);
        }
        return bos.toByteArray();
    }

    private Object lockFor(Path file) {
        return fileLocks.computeIfAbsent(file.toAbsolutePath().normalize().toString(), k -> new Object());
    }
}
