package net.yumezawa.queqiao_map_web.mapstore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * mca region 文件独立读取器（零 MC 依赖，蓝图 §2.1）。
 *
 * <p>文件布局：头部 = 位置表 1024×(u32 扇区偏移, u32 扇区数)（8192B）+ 修改时间表 1024×u32（4096B），
 * 共 12288B；块记录位于 扇区偏移×4096 处：u32 数据长度（含压缩字节）、u8 压缩类型、其余为压缩负载。
 * 位置索引 index = (x&31)|((z&31)<<5)；偏移 0 表示该列不存在。
 * 压缩类型：1=gzip，2=zlib，3=不压缩，其余记 WARNING 语义按跳过处理（抛 IOException 由调用方吞掉）。</p>
 *
 * <p>本类只在后台线程被调用；{@link #listChunks(Path)} 内部吞异常返回可能不完整的列表，
 * {@link #readChunkNbt(Path, ChunkLocation)} 按蓝图声明 throws IOException，由调用方（BackfillEngine）
 * 吞掉记 FINE。</p>
 */
public final class RegionFileReader {

    private static final Logger LOG = Logger.getLogger("queqiao_map_web.mapstore");

    /** 扇区大小（字节） */
    static final int SECTOR = 4096;
    /** region 内列出的块数（32×32） */
    static final int CHUNK_COUNT = 1024;
    /** 位置表字节数（1024×8） */
    static final int LOCATION_TABLE_BYTES = CHUNK_COUNT * 8;
    /** 头部总字节数（位置表 + 修改时间表） */
    static final int HEADER_BYTES = LOCATION_TABLE_BYTES + CHUNK_COUNT * 4;

    /** r.&lt;rx&gt;.&lt;rz&gt;.mca 文件名模式，用于把列内 0..31 坐标折算成绝对块坐标 */
    private static final Pattern REGION_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    /** 压缩类型：gzip */
    static final int COMPRESSION_GZIP = 1;
    /** 压缩类型：zlib */
    static final int COMPRESSION_ZLIB = 2;
    /** 压缩类型：不压缩 */
    static final int COMPRESSION_NONE = 3;

    /**
     * 一个存在的块的定位信息。
     *
     * @param x            绝对块 X（由文件名 r.rx.rz 折算；文件名不符合模式时为列内 0..31）
     * @param z            绝对块 Z（同上）
     * @param sectorOffset 块记录起始扇区号
     * @param sectorCount  块占用扇区数
     */
    public record ChunkLocation(int x, int z, int sectorOffset, int sectorCount) {
    }

    private RegionFileReader() {
    }

    /**
     * 列出 region 文件中所有存在的块（位置表偏移非 0 且大小非 0）。
     * 任何 IO 异常吞掉记 FINE，返回已读到的部分（异常前）或空列表。
     */
    public static List<ChunkLocation> listChunks(Path regionFile) {
        List<ChunkLocation> out = new ArrayList<>();
        String fileName = regionFile.getFileName() == null ? "" : regionFile.getFileName().toString();
        Matcher m = REGION_NAME.matcher(fileName);
        int baseX = m.matches() ? Integer.parseInt(m.group(1)) * 32 : 0;
        int baseZ = m.matches() ? Integer.parseInt(m.group(2)) * 32 : 0;
        boolean nameParsed = m.matches();
        if (!nameParsed) {
            LOG.log(Level.FINE, "region 文件名不符合 r.<rx>.<rz>.mca，坐标按列内值返回: {0}", fileName);
        }
        try (FileChannel ch = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES); // 大端（ByteBuffer 默认）
            long pos = 0;
            while (header.hasRemaining()) {
                int n = ch.read(header, pos);
                if (n <= 0) {
                    break;
                }
                pos += n;
            }
            if (header.position() < LOCATION_TABLE_BYTES) {
                LOG.log(Level.FINE, "region 头部不足位置表长度，跳过: {0}", regionFile);
                return out;
            }
            long fileSize = ch.size();
            for (int i = 0; i < CHUNK_COUNT; i++) {
                int offset = header.getInt(i * 8);
                int size = header.getInt(i * 8 + 4);
                if (offset == 0 || size == 0) {
                    continue;
                }
                // 越界裁剪：只按位置表如实返回，读取阶段再失败吞掉
                if ((long) offset * SECTOR >= fileSize) {
                    LOG.log(Level.FINE, "region 位置表偏移越过文件末尾: {0} index={1}", new Object[]{regionFile, i});
                    continue;
                }
                int lx = i & 31;
                int lz = (i >> 5) & 31;
                out.add(new ChunkLocation(baseX + lx, baseZ + lz, offset, size));
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "读取 region 位置表失败: " + regionFile, e);
        }
        return out;
    }

    /**
     * 读取指定块的 NBT 原始字节（已按压缩类型解压）。
     * 按蓝图声明 throws IOException：压缩类型非法、文件损坏、长度不足等都抛出，由调用方吞掉记 FINE。
     */
    public static byte[] readChunkNbt(Path regionFile, ChunkLocation loc) throws IOException {
        try (FileChannel ch = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            long start = (long) loc.sectorOffset() * SECTOR;
            ByteBuffer head = ByteBuffer.allocate(5);
            int read = 0;
            while (head.hasRemaining()) {
                int n = ch.read(head, start + read);
                if (n <= 0) {
                    throw new IOException("块记录头读取不完整: " + regionFile + " chunk=" + loc.x() + "," + loc.z());
                }
                read += n;
            }
            head.flip();
            int length = head.getInt();
            int compression = head.get() & 0xFF;
            if (length <= 1) {
                throw new IOException("块数据长度非法: " + length + " (" + regionFile + ")");
            }
            int maxSectors = Math.max(1, loc.sectorCount());
            if (length > maxSectors * SECTOR) {
                throw new IOException("块数据长度超过扇区配额: " + length + " > " + (maxSectors * SECTOR)
                        + " (" + regionFile + ")");
            }
            ByteBuffer body = ByteBuffer.allocate(length - 1);
            int off = 5;
            while (body.hasRemaining()) {
                int n = ch.read(body, start + off);
                if (n <= 0) {
                    throw new IOException("块数据读取不完整: " + regionFile + " chunk=" + loc.x() + "," + loc.z());
                }
                off += n;
            }
            byte[] compressed = body.array();
            return switch (compression) {
                case COMPRESSION_GZIP -> decompress(new GZIPInputStream(new java.io.ByteArrayInputStream(compressed)));
                case COMPRESSION_ZLIB -> decompress(new InflaterInputStream(new java.io.ByteArrayInputStream(compressed)));
                case COMPRESSION_NONE -> compressed;
                default -> throw new IOException("未知压缩类型: " + compression + " (" + regionFile + ")");
            };
        }
    }

    /** 把流读完并返回字节；上限防损坏文件导致 OOM */
    private static byte[] decompress(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > 64 * 1024 * 1024) {
                throw new IOException("解压结果超过 64MiB 上限");
            }
        }
        return bos.toByteArray();
    }
}
