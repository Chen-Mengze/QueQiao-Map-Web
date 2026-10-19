package net.yumezawa.queqiao_map_web.mapstore;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-MS-005：qms 文件格式、xor32 重算、维度映射、markSent 与增量 mtime 判定 */
class MapStoreTest {

    /** 独立实现的 xor32（与 MapStore 对照验证，不复用其代码） */
    private static int xorOf(byte[] p) {
        int x = 0;
        for (int i = 0; i + 3 < p.length; i += 4) {
            x ^= ((p[i] & 0xFF) << 24) | ((p[i + 1] & 0xFF) << 16) | ((p[i + 2] & 0xFF) << 8) | (p[i + 3] & 0xFF);
        }
        return x;
    }

    private static byte[] patternPayload() {
        byte[] p = new byte[MapStore.PAYLOAD_BYTES];
        for (int i = 0; i < p.length; i++) {
            p[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return p;
    }

    private static byte[] chunk768(int r, int g, int b) {
        byte[] c = new byte[768];
        for (int i = 0; i < 768; i += 3) {
            c[i] = (byte) r;
            c[i + 1] = (byte) g;
            c[i + 2] = (byte) b;
        }
        return c;
    }

    @Test
    @Tag("REQ-MS-005")
    void putRegionReadRoundtripAndXorRecompute(@TempDir Path world) throws IOException {
        MapStore store = new MapStore(world);
        byte[] payload = patternPayload();
        long before = System.currentTimeMillis();
        store.putRegion("minecraft:overworld", 0, 0, payload);
        long after = System.currentTimeMillis();

        Path f = world.resolve("queqiao_map_web").resolve("store").resolve("overworld").resolve("r.0.0.qms");
        assertTrue(Files.exists(f), "qms 文件应落在 store/overworld/r.0.0.qms");
        byte[] raw = Files.readAllBytes(f);
        assertEquals("QMS1", new String(raw, 0, 4, StandardCharsets.US_ASCII), "魔数应为 QMS1");

        byte[] back = store.readRegion("minecraft:overworld", 0, 0);
        assertNotNull(back);
        assertEquals(MapStore.PAYLOAD_BYTES, back.length);
        assertArrayEquals(payload, back, "读写往返应逐字节一致");

        MapStore.Header h = store.header("minecraft:overworld", 0, 0);
        assertNotNull(h);
        assertEquals(512, h.side());
        assertEquals(1, h.formatVer());
        assertEquals(xorOf(payload), h.xor32(), "头 xor32 应等于负载按 4B 大端分组 XOR");
        assertEquals(0, h.lastSentXor(), "新写入 lastSentXor 归零");
        assertTrue(h.mtimeMillis() >= before && h.mtimeMillis() <= after, "mtime 应记录写入时刻");
        assertTrue(h.payloadLen() > 0 && h.payloadLen() <= raw.length - 27, "payloadLen 为 zlib 压缩段长度");

        // 改 1 字节 → xor32 必须重算
        payload[0] ^= 0x55;
        store.putRegion("minecraft:overworld", 0, 0, payload);
        MapStore.Header h2 = store.header("minecraft:overworld", 0, 0);
        assertNotNull(h2);
        assertEquals(xorOf(payload), h2.xor32());
        assertFalse(h2.xor32() == h.xor32() && !java.util.Arrays.equals(payload, patternPayload()));
        assertArrayEquals(payload, store.readRegion("minecraft:overworld", 0, 0));

        // 缺失区域 → null
        assertNull(store.readRegion("minecraft:overworld", 5, 5));
        assertNull(store.header("minecraft:overworld", 5, 5));
        // 非法长度 → 静默跳过不建文件
        store.putRegion("minecraft:overworld", 6, 6, new byte[10]);
        assertNull(store.header("minecraft:overworld", 6, 6));
    }

    @Test
    @Tag("REQ-MS-005")
    void putChunkPatchAndDimMapping(@TempDir Path world) throws IOException {
        MapStore store = new MapStore(world);
        store.putChunk("quchao:test_dim", -1, 17, chunk768(0x11, 0x22, 0x33));
        Path f = world.resolve("queqiao_map_web").resolve("store").resolve("quchao_test_dim").resolve("r.-1.0.qms");
        assertTrue(Files.exists(f), "命名空间维度目录应为 ns_path，负坐标取 floorDiv");

        byte[] grid = store.readRegion("quchao:test_dim", -1, 0);
        assertNotNull(grid);
        int colOrigin = ((-1 & 31)) * 16; // =496
        int rowOrigin = (17 & 31) * 16;   // =272
        // putChunk 补丁 = 16 行 × 16 像素（每方块一像素），非 16×16 色块
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int p = (((rowOrigin + lz) * 512) + colOrigin + lx) * 3;
                assertEquals(0x11, grid[p] & 0xFF);
                assertEquals(0x22, grid[p + 1] & 0xFF);
                assertEquals(0x33, grid[p + 2] & 0xFF);
            }
        }
        assertEquals(0, grid[0]);
        assertEquals(0, grid[1]);
        assertEquals(0, grid[2]);

        // 非法长度：不建区域
        store.putChunk("quchao:test_dim", 5, 5, new byte[767]);
        assertNull(store.header("quchao:test_dim", 0, 0));

        // overworld 注册键与裸名都映射到 overworld 目录
        store.putChunk("minecraft:overworld", 0, 0, chunk768(0xFF, 0, 0));
        assertTrue(Files.exists(world.resolve("queqiao_map_web").resolve("store").resolve("overworld").resolve("r.0.0.qms")));

        List<String> dims = store.listDimensions();
        assertTrue(dims.contains("quchao:test_dim"), "目录名逆映射应还原注册键: " + dims);
        assertTrue(dims.contains("minecraft:overworld"));

        List<MapStore.RegionInfo> regions = store.listRegions("quchao:test_dim");
        assertEquals(1, regions.size());
        assertEquals(-1, regions.get(0).rx());
        assertEquals(0, regions.get(0).rz());
        MapStore.Header h = store.header("quchao:test_dim", -1, 0);
        assertEquals(h.xor32(), regions.get(0).xor32());
        assertEquals(0, regions.get(0).lastSentXor());

        // 增量判定：mca mtime vs store 头 mtime
        Path regionDir = world.resolve("region");
        MapFixtures.writeRegion(regionDir, 0, 0, List.of());
        Path mca = regionDir.resolve("r.0.0.mca");
        long hMtime = store.header("minecraft:overworld", 0, 0).mtimeMillis();
        Files.setLastModifiedTime(mca, FileTime.fromMillis(hMtime - 60_000));
        assertFalse(store.isRegionFileNewer("minecraft:overworld", 0, 0, mca), "mca 不比 store 新 → 跳过");
        Files.setLastModifiedTime(mca, FileTime.fromMillis(hMtime + 60_000));
        assertTrue(store.isRegionFileNewer("minecraft:overworld", 0, 0, mca), "mca 更新 → 需处理");
        // store 无该区域 → true（必须处理）
        assertTrue(store.isRegionFileNewer("minecraft:overworld", 9, 9, mca));
        // mca stat 失败 → false（下轮再查）
        assertFalse(store.isRegionFileNewer("minecraft:overworld", 0, 0, regionDir.resolve("r.404.404.mca")));
    }

    @Test
    @Tag("REQ-MS-005")
    void markSentRewritesLastSentXorOnly(@TempDir Path world) {
        MapStore store = new MapStore(world);
        byte[] payload = patternPayload();
        store.putRegion("minecraft:overworld", 3, -2, payload);
        MapStore.Header h1 = store.header("minecraft:overworld", 3, -2);
        assertNotNull(h1);
        store.markSent("minecraft:overworld", 3, -2, 0x1234abcd);
        MapStore.Header h2 = store.header("minecraft:overworld", 3, -2);
        assertNotNull(h2);
        assertEquals(0x1234abcd, h2.lastSentXor());
        assertEquals(h1.xor32(), h2.xor32(), "markSent 不应改变 xor32");
        assertEquals(h1.mtimeMillis(), h2.mtimeMillis(), "markSent 原地改写，mtime 保持");
        assertArrayEquals(payload, store.readRegion("minecraft:overworld", 3, -2), "负载不受影响");
        // 目标不存在：吞异常、不建文件
        store.markSent("minecraft:overworld", 7, 7, 1);
        assertNull(store.header("minecraft:overworld", 7, 7));
    }
}
