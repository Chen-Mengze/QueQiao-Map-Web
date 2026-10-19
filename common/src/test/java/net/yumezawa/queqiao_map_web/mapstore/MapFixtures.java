package net.yumezawa.queqiao_map_web.mapstore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 测试夹具工厂（仅测试源码）：手写 NBT 字节、合成 .mca 区域文件、位打包 BlockStates。
 * 全部离线合成，不依赖 MC/鹊桥。
 */
final class MapFixtures {

    private MapFixtures() {
    }

    // ------------------------------------------------------------------
    // NBT 写端（与 NbtReader 对称，用于造夹具）
    // ------------------------------------------------------------------

    /** 生成完整 NBT 文档字节（根 TAG_Compound + 空名） */
    static byte[] nbtRoot(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(10);
        out.writeUTF("");
        writeCompoundBody(out, root);
        out.flush();
        return bos.toByteArray();
    }

    private static void writeCompoundBody(DataOutputStream out, Map<String, Object> map) throws IOException {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            writeTag(out, e.getKey(), e.getValue());
        }
        out.writeByte(0); // TAG_End
    }

    private static void writeTag(DataOutputStream out, String name, Object v) throws IOException {
        switch (typeCode(v)) {
            case 10 -> {
                out.writeByte(10);
                out.writeUTF(name);
                writeCompoundBody(out, asMap(v));
            }
            case 9 -> {
                out.writeByte(9);
                out.writeUTF(name);
                List<?> list = (List<?>) v;
                if (list.isEmpty()) {
                    out.writeByte(0);
                    out.writeInt(0);
                } else {
                    out.writeByte(typeCode(list.get(0)));
                    out.writeInt(list.size());
                    for (Object el : list) {
                        writePayload(out, el);
                    }
                }
            }
            default -> {
                out.writeByte(typeCode(v));
                out.writeUTF(name);
                writePayload(out, v);
            }
        }
    }

    private static void writePayload(DataOutputStream out, Object v) throws IOException {
        switch (typeCode(v)) {
            case 1 -> out.writeByte((Byte) v);
            case 2 -> out.writeShort((Short) v);
            case 3 -> out.writeInt((Integer) v);
            case 4 -> out.writeLong((Long) v);
            case 5 -> out.writeFloat((Float) v);
            case 6 -> out.writeDouble((Double) v);
            case 7 -> {
                byte[] a = (byte[]) v;
                out.writeInt(a.length);
                out.write(a);
            }
            case 8 -> out.writeUTF((String) v);
            case 9 -> {
                List<?> list = (List<?>) v;
                out.writeByte(list.isEmpty() ? 0 : typeCode(list.get(0)));
                out.writeInt(list.size());
                for (Object el : list) {
                    writePayload(out, el);
                }
            }
            case 10 -> writeCompoundBody(out, asMap(v));
            case 11 -> {
                int[] a = (int[]) v;
                out.writeInt(a.length);
                for (int x : a) {
                    out.writeInt(x);
                }
            }
            case 12 -> {
                long[] a = (long[]) v;
                out.writeInt(a.length);
                for (long x : a) {
                    out.writeLong(x);
                }
            }
            default -> throw new IllegalArgumentException("不支持的夹具类型: " + v);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return (Map<String, Object>) v;
    }

    private static int typeCode(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof Double) return 6;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        throw new IllegalArgumentException("不支持的夹具类型: " + v);
    }

    // ------------------------------------------------------------------
    // 块 NBT（1.20.1 sections 格式 / 1.20.2 palettes 格式）
    // ------------------------------------------------------------------

    /** 1.20.1 section：Y + BlockStatePalette(Map{Name}) + BlockStates(long[]) */
    static Map<String, Object> section1201(int y, List<String> palette, long[] states) {
        Map<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) y);
        List<Map<String, Object>> pal = new ArrayList<>();
        for (String p : palette) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("Name", p);
            pal.add(m);
        }
        sec.put("BlockStatePalette", pal);
        sec.put("BlockStates", states);
        return sec;
    }

    /** 1.20.2 section：Y + block_states{palette_ref, data}（引用根 palettes） */
    static Map<String, Object> section1202Ref(int y, int paletteRef, long[] data) {
        Map<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) y);
        Map<String, Object> bs = new LinkedHashMap<>();
        bs.put("palette_ref", paletteRef);
        bs.put("data", data);
        sec.put("block_states", bs);
        return sec;
    }

    /** 1.20.2 根 palette 项：{id, palette:[裸 String...]} */
    static Map<String, Object> rootPalette(int id, String... names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("palette", List.of((Object[]) names));
        return m;
    }

    /** 根 chunk Compound（1.20.1）：DataVersion + sections */
    static Map<String, Object> rootChunk1201(List<Map<String, Object>> sections) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 3465);
        root.put("sections", new ArrayList<Object>(sections));
        return root;
    }

    /** 根 chunk Compound（1.20.2）：DataVersion + palettes + sections */
    static Map<String, Object> rootChunk1202(List<Map<String, Object>> sections, List<Map<String, Object>> palettes) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 3700);
        root.put("palettes", new ArrayList<Object>(palettes));
        root.put("sections", new ArrayList<Object>(sections));
        return root;
    }

    /** 4096 个条目全部取 palette 下标 value 的现代对齐打包 */
    static long[] packAll(int value, int bits) {
        int[] values = new int[4096];
        java.util.Arrays.fill(values, value);
        return packAligned(values, bits);
    }

    /** 现代对齐位打包：条目 i 占位 [i*bits, i*bits+bits) */
    static long[] packAligned(int[] values, int bits) {
        long[] words = new long[(values.length * bits + 63) >>> 6];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < values.length; i++) {
            int total = i * bits;
            int w = total >> 6;
            int b = total & 63;
            words[w] |= (values[i] & mask) << b;
            if (b + bits > 64) {
                words[w + 1] |= (values[i] & mask) >>> (64 - b);
            }
        }
        return words;
    }

    // ------------------------------------------------------------------
    // 合成 .mca
    // ------------------------------------------------------------------

    /** 一个块的夹具规格：列内坐标（0..31）、压缩类型、NBT 原始字节 */
    record ChunkSpec(int lx, int lz, int compression, byte[] nbtBytes) {
    }

    /**
     * 写一个最小合法 region 文件：位置表 1024×8 + mtime 表 1024×4（共 3 扇区头部），
     * 块记录自扇区 3 起紧接头部排列——与真实 Anvil 区域文件一致。
     *
     * @param rx rz 用于文件名 r.&lt;rx&gt;.&lt;rz&gt;.mca
     */
    static void writeRegion(Path dir, int rx, int rz, List<ChunkSpec> chunks) throws IOException {
        Files.createDirectories(dir);
        ByteBuffer buf = ByteBuffer.allocate(12288); // 12288 = 3 扇区
        List<byte[]> payloads = new ArrayList<>();
        int sector = 3; // 真实 Anvil：首块记录紧接 3 扇区头部
        for (ChunkSpec c : chunks) {
            byte[] compressed = compress(c.compression(), c.nbtBytes());
            int recSize = 5 + compressed.length;
            int sectors = (recSize + 4095) / 4096;
            ByteBuffer rec = ByteBuffer.allocate(sectors * 4096);
            rec.putInt(compressed.length + 1);
            rec.put((byte) c.compression());
            rec.put(compressed);
            payloads.add(rec.array());
            int index = (c.lx() & 31) | ((c.lz() & 31) << 5);
            buf.putInt(index * 8, sector);
            buf.putInt(index * 8 + 4, sectors);
            sector += sectors;
        }
        byte[] headerBytes = new byte[12288];
        System.arraycopy(buf.array(), 0, headerBytes, 0, 12288);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(headerBytes);
        for (byte[] p : payloads) {
            bos.write(p);
        }
        Files.write(dir.resolve("r." + rx + "." + rz + ".mca"), bos.toByteArray());
    }

    private static byte[] compress(int compression, byte[] raw) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        switch (compression) {
            case 1 -> {
                try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
                    gos.write(raw);
                }
            }
            case 2 -> {
                try (DeflaterOutputStream dos = new DeflaterOutputStream(bos)) {
                    dos.write(raw);
                }
            }
            case 3 -> bos.write(raw);
            default -> {
                // 非法压缩类型夹具：负载原样但类型非法
                bos.write(raw);
            }
        }
        return bos.toByteArray();
    }
}
