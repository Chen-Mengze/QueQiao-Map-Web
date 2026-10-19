package net.yumezawa.queqiao_map_web.mapstore;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-MS-001：合成 .mca 的区域索引读取与容错 */
class RegionFileReaderTest {

    @Test
    @Tag("REQ-MS-001")
    void listAndReadSyntheticMcaRoundtrip(@TempDir Path dir) throws IOException {
        Map<String, Object> c1 = new LinkedHashMap<>();
        c1.put("x", 3);
        byte[] nbt1 = MapFixtures.nbtRoot(c1);
        Map<String, Object> c2 = new LinkedHashMap<>();
        c2.put("x", 10);
        byte[] nbt2 = MapFixtures.nbtRoot(c2);
        Map<String, Object> c3 = new LinkedHashMap<>();
        c3.put("x", 20);
        byte[] nbt3 = MapFixtures.nbtRoot(c3);
        // 压缩类型：2=zlib、1=gzip、9=非法（应读取时抛 IOException）
        MapFixtures.writeRegion(dir, 0, 0, List.of(
                new MapFixtures.ChunkSpec(3, 5, 2, nbt1),
                new MapFixtures.ChunkSpec(10, 22, 1, nbt2),
                new MapFixtures.ChunkSpec(20, 31, 9, nbt3)));
        Path mca = dir.resolve("r.0.0.mca");
        assertTrue(Files.exists(mca));

        List<RegionFileReader.ChunkLocation> locs = RegionFileReader.listChunks(mca);
        assertEquals(3, locs.size(), "位置表应列出 3 个非空槽位");
        // 按索引升序：(3,5)=163 → (10,22)=714 → (20,31)=1012
        assertEquals(3, locs.get(0).x());
        assertEquals(5, locs.get(0).z());
        assertEquals(10, locs.get(1).x());
        assertEquals(22, locs.get(1).z());
        assertEquals(20, locs.get(2).x());
        assertEquals(31, locs.get(2).z());
        for (RegionFileReader.ChunkLocation loc : locs) {
            assertTrue(loc.sectorOffset() >= 3, "块记录不应侵占 3 扇区头部（首块起于扇区 3）");
            assertTrue(loc.sectorCount() >= 1);
        }

        assertArrayEquals(nbt1, RegionFileReader.readChunkNbt(mca, locs.get(0)), "zlib 解压应还原 NBT 原文");
        assertArrayEquals(nbt2, RegionFileReader.readChunkNbt(mca, locs.get(1)), "gzip 解压应还原 NBT 原文");
        RegionFileReader.ChunkLocation bad = locs.get(2);
        assertThrows(IOException.class, () -> RegionFileReader.readChunkNbt(mca, bad), "非法压缩类型应抛 IOException");
    }

    @Test
    @Tag("REQ-MS-001")
    void truncatedHeaderYieldsEmptyList(@TempDir Path dir) throws IOException {
        // 不足 12288B 头部：吞掉异常返回空清单（截断的部分位置表全 0 → 无有效槽位）
        Path broken = dir.resolve("r.7.7.mca");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] zeros = new byte[1024];
        for (int i = 0; i < 5; i++) {
            bos.write(zeros);
        }
        Files.write(broken, bos.toByteArray());
        assertTrue(RegionFileReader.listChunks(broken).isEmpty());
        // 文件不存在：同样空清单不抛异常
        assertTrue(RegionFileReader.listChunks(dir.resolve("r.9.9.mca")).isEmpty());
    }

    @Test
    @Tag("REQ-MS-001")
    void readChunkBeyondEofThrows(@TempDir Path dir) throws IOException {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("x", 1);
        MapFixtures.writeRegion(dir, 0, 0, List.of(new MapFixtures.ChunkSpec(1, 1, 2, MapFixtures.nbtRoot(c))));
        Path mca = dir.resolve("r.0.0.mca");
        RegionFileReader.ChunkLocation ghost = new RegionFileReader.ChunkLocation(2, 2, 9999, 1);
        assertThrows(IOException.class, () -> RegionFileReader.readChunkNbt(mca, ghost), "越界偏移应抛 IOException");
        // 空清单路径也保证不抛（防御性）
        assertEquals(1, RegionFileReader.listChunks(mca).size());
    }
}
