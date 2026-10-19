package net.yumezawa.queqiao_map_web.mapstore;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-MS-006：回填上色、增量跳过与令牌桶节流 */
class BackfillEngineTest {

    private static boolean pixelIs(byte[] grid, int px, int py, int rgb) {
        int p = (py * MapStore.SIDE + px) * 3;
        return (grid[p] & 0xFF) == (rgb >> 16) && ((grid[p + 1] & 0xFF) == ((rgb >> 8) & 0xFF))
                && (grid[p + 2] & 0xFF) == (rgb & 0xFF);
    }

    private static byte[] chunkNbt(String block) throws IOException {
        return MapFixtures.nbtRoot(MapFixtures.rootChunk1201(List.of(
                MapFixtures.section1201(0, List.of("minecraft:air", block), MapFixtures.packAll(1, 4)))));
    }

    /** 建维度目录后删除 qms，让首轮扫描以 header==null 进入 */
    private static void prepareEmptyDim(MapStore store) throws IOException {
        store.putChunk("minecraft:overworld", 0, 0, new byte[768]);
        Files.delete(store.regionPath("minecraft:overworld", 0, 0));
    }

    @Test
    @Tag("REQ-MS-006")
    void backfillPaintsRegionAndSkipsUnchanged(@TempDir Path world) throws IOException {
        Path regionDir = world.resolve("region");
        MapFixtures.writeRegion(regionDir, 0, 0, List.of(
                new MapFixtures.ChunkSpec(0, 0, 2, chunkNbt("minecraft:grass_block")),
                new MapFixtures.ChunkSpec(1, 0, 2, chunkNbt("minecraft:stone"))));
        Path mca = regionDir.resolve("r.0.0.mca");

        MapStore store = new MapStore(world);
        prepareEmptyDim(store);
        BackfillEngine engine = new BackfillEngine(store, world, 4096);
        engine.runScanCycle();

        byte[] grid = store.readRegion("minecraft:overworld", 0, 0);
        assertNotNull(grid, "首轮扫描应写入区域");
        int grass = BlockColors.colorOf("minecraft:grass_block");
        int stone = BlockColors.colorOf("minecraft:stone");
        assertTrue(pixelIs(grid, 8, 8, grass), "chunk(0,0) 应上 grass_block 色");
        assertTrue(pixelIs(grid, 24, 8, stone), "chunk(1,0) 应上 stone 色");
        assertTrue(pixelIs(grid, 400, 400, 0x000000), "无块区域保持 0");

        // 改写 mca（diamond）但把 mtime 压回头部时刻之前 → 增量跳过
        long hMtime = store.header("minecraft:overworld", 0, 0).mtimeMillis();
        MapFixtures.writeRegion(regionDir, 0, 0, List.of(
                new MapFixtures.ChunkSpec(0, 0, 2, chunkNbt("minecraft:diamond_block")),
                new MapFixtures.ChunkSpec(1, 0, 2, chunkNbt("minecraft:stone"))));
        Files.setLastModifiedTime(mca, FileTime.fromMillis(hMtime - 60_000));
        engine.runScanCycle();
        grid = store.readRegion("minecraft:overworld", 0, 0);
        assertTrue(pixelIs(grid, 8, 8, grass), "mca 未更新 → 应跳过，保持旧色");

        // mtime 比 store 头新 → 重扫，diamond 生效
        Files.setLastModifiedTime(mca, FileTime.fromMillis(hMtime + 120_000));
        engine.runScanCycle();
        grid = store.readRegion("minecraft:overworld", 0, 0);
        assertTrue(pixelIs(grid, 8, 8, BlockColors.colorOf("minecraft:diamond_block")), "mca 更新 → 重扫上色");
    }

    @Test
    @Tag("REQ-MS-006")
    void throttleSlowsPerChunk(@TempDir Path world) throws IOException {
        Path regionDir = world.resolve("region");
        MapFixtures.writeRegion(regionDir, 0, 0, List.of(
                new MapFixtures.ChunkSpec(0, 0, 2, chunkNbt("minecraft:stone")),
                new MapFixtures.ChunkSpec(1, 0, 2, chunkNbt("minecraft:stone"))));

        MapStore store = new MapStore(world);
        prepareEmptyDim(store);
        // 1 块/秒：初始满桶 1 枚 → 第一块免费，第二块约等 1s
        BackfillEngine engine = new BackfillEngine(store, world, 1);
        long t0 = System.currentTimeMillis();
        engine.runScanCycle();
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed >= 800, "节流应生效，实测仅 " + elapsed + "ms");
        assertTrue(elapsed < 15_000, "不应异常缓慢: " + elapsed + "ms");
        assertNotNull(store.readRegion("minecraft:overworld", 0, 0));
    }

    @Test
    @Tag("REQ-MS-006")
    void startStopIsSafe(@TempDir Path world) throws InterruptedException {
        MapStore store = new MapStore(world);
        BackfillEngine engine = new BackfillEngine(store, world, 512, 100L);
        engine.start();
        engine.start(); // 幂等
        Thread.sleep(150);
        engine.stop();
        engine.stop(); // 幂等
        // 无 store 维度时 runScanCycle 空转无异常
        engine.runScanCycle();
        assertEquals(0, store.listDimensions().size());
    }
}
