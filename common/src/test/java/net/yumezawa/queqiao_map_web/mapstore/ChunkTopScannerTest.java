package net.yumezawa.queqiao_map_web.mapstore;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** REQ-MS-003：现代对齐位打包 + 1.20.2 palettes + 旧式补齐/截断兜底 + 空气列 null + 最低高度跳过 */
class ChunkTopScannerTest {

    /** 4096 条目：ly<8 → vLow，ly>=8 → vHigh */
    private static int[] splitByLayer(int vLow, int vHigh) {
        int[] v = new int[4096];
        for (int i = 0; i < 4096; i++) {
            v[i] = (i >> 8) < 8 ? vLow : vHigh;
        }
        return v;
    }

    /** 4096 条目：lz==0 行（每层前 16 个）→ 1，其余 0 */
    private static int[] firstZRow(int value) {
        int[] v = new int[4096];
        for (int i = 0; i < 4096; i++) {
            if (((i >> 4) & 15) == 0) {
                v[i] = value;
            }
        }
        return v;
    }

    @Test
    @Tag("REQ-MS-003")
    void modernAlignedSectionsTopState() {
        // Y=0：下层 stone、上层 water → 顶面 water
        Map<String, Object> sec0 = MapFixtures.section1201(0,
                List.of("minecraft:air", "minecraft:stone", "minecraft:water"),
                MapFixtures.packAligned(splitByLayer(1, 2), 4));
        // Y=1：仅 lz=0 行铺 grass_block → 该 16 列顶面改判 grass_block
        Map<String, Object> sec1 = MapFixtures.section1201(1,
                List.of("minecraft:air", "minecraft:grass_block"),
                MapFixtures.packAligned(firstZRow(1), 4));
        Map<String, Object> root = MapFixtures.rootChunk1201(List.of(sec0, sec1));

        String[] top = ChunkTopScanner.scanTopStates(root, -64);
        assertEquals(256, top.length);
        for (int lx = 0; lx < 16; lx++) {
            assertEquals("minecraft:grass_block", top[lx], "第 lz=0 行应为更高 section 的 grass_block");
        }
        for (int i = 16; i < 256; i++) {
            assertEquals("minecraft:water", top[i], "其余列应为最顶非空气 water（water 不是空气）");
        }
    }

    @Test
    @Tag("REQ-MS-003")
    void modern1202PalettesFormat() {
        // 引用式：section → palette_ref=7 → 根 palettes 里的 [air, sand]
        Map<String, Object> refSec = MapFixtures.section1202Ref(0, 7, MapFixtures.packAll(1, 4));
        List<Map<String, Object>> palettes = List.of(MapFixtures.rootPalette(7, "minecraft:air", "minecraft:sand"));
        // 内联式：Y=1 block_states{palette,data}，lz=0 行 grass_block
        Map<String, Object> inlineSec = new LinkedHashMap<>();
        inlineSec.put("Y", (byte) 1);
        Map<String, Object> bs = new LinkedHashMap<>();
        bs.put("palette", List.of("minecraft:air", "minecraft:grass_block"));
        bs.put("data", MapFixtures.packAligned(firstZRow(1), 4));
        inlineSec.put("block_states", bs);
        Map<String, Object> root = MapFixtures.rootChunk1202(List.of(inlineSec, refSec), palettes);

        String[] top = ChunkTopScanner.scanTopStates(root, -64);
        for (int lx = 0; lx < 16; lx++) {
            assertEquals("minecraft:grass_block", top[lx]);
        }
        for (int i = 16; i < 256; i++) {
            assertEquals("minecraft:sand", top[i], "引用根 palette 的 sand 应可解码");
        }
    }

    @Test
    @Tag("REQ-MS-003")
    void paddedPackingFallbackAndAirNull() {
        List<String> pal = List.of("minecraft:air", "minecraft:stone");
        // 上层（ly>=8）为 stone 的 4096 条目
        int[] values = splitByLayer(0, 1);
        long[] aligned = MapFixtures.packAligned(values, 4); // 256 longs
        // ① 旧式补齐：longs 更长（尾部多一个 0 填充 long）→ 解码结果一致
        long[] padded = Arrays.copyOf(aligned, aligned.length + 1);
        String[] topPadded = ChunkTopScanner.scanTopStates(
                MapFixtures.rootChunk1201(List.of(MapFixtures.section1201(0, pal, padded))), -64);
        for (int i = 0; i < 256; i++) {
            assertEquals("minecraft:stone", topPadded[i], "补齐长数组应正常解码");
        }
        // ② 截断（不足以覆盖 4096 条目）：越界条目按空气 → 上层 stone 读不到 → 全列 null，且不抛
        long[] truncated = Arrays.copyOf(aligned, 128);
        String[] topTrunc = ChunkTopScanner.scanTopStates(
                MapFixtures.rootChunk1201(List.of(MapFixtures.section1201(0, pal, truncated))), -64);
        for (int i = 0; i < 256; i++) {
            assertNull(topTrunc[i], "越界数据按空气处理，整列无地面应为 null");
        }
        // ③ 全空气 palette → null
        String[] topAir = ChunkTopScanner.scanTopStates(
                MapFixtures.rootChunk1201(List.of(MapFixtures.section1201(0, List.of("minecraft:air", "minecraft:cave_air"),
                        MapFixtures.packAll(1, 4)))), -64);
        for (int i = 0; i < 256; i++) {
            assertNull(topAir[i], "cave_air 也是空气");
        }
    }

    @Test
    @Tag("REQ-MS-003")
    void minBuildHeightSkip() {
        List<String> pal = List.of("minecraft:air", "minecraft:stone");
        // 仅 ly<5 铺 stone：Y=-4 → blockY=-64..-60
        int[] values = new int[4096];
        Arrays.fill(values, 0, 5 * 256, 1);
        Map<String, Object> secMinus4 = MapFixtures.section1201(-4, pal, MapFixtures.packAligned(values, 4));
        // minBuildHeight=-60：ly=4（y=-60）保留 → stone
        String[] keep = ChunkTopScanner.scanTopStates(MapFixtures.rootChunk1201(List.of(secMinus4)), -60);
        for (int i = 0; i < 256; i++) {
            assertEquals("minecraft:stone", keep[i]);
        }
        // minBuildHeight=-59：ly=4（y=-60）也低于下限 → 全列 null
        String[] skip = ChunkTopScanner.scanTopStates(MapFixtures.rootChunk1201(List.of(secMinus4)), -59);
        for (int i = 0; i < 256; i++) {
            assertNull(skip[i], "低于 minBuildHeight 的方块应忽略");
        }
        // 整层跳过：Y=-5（最高 y=-65）< -60 → 不进入扫描
        Map<String, Object> secMinus5 = MapFixtures.section1201(-5, pal, MapFixtures.packAll(1, 4));
        String[] full = ChunkTopScanner.scanTopStates(MapFixtures.rootChunk1201(List.of(secMinus5)), -60);
        for (int i = 0; i < 256; i++) {
            assertNull(full[i], "整层低于下限应整体跳过");
        }
    }
}
