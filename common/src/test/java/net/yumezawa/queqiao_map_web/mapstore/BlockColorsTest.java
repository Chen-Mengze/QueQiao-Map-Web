package net.yumezawa.queqiao_map_web.mapstore;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-MS-004：内置方块颜色表（vanilla MapColor 实值）与灰色兜底 */
class BlockColorsTest {

    @Test
    @Tag("REQ-MS-004")
    void knownVanillaValuesAndFallback() {
        // vanilla 1.20.1 MapColor 实值抽查
        assertEquals(0x7fb238, BlockColors.colorOf("minecraft:grass_block"));
        assertEquals(0x707070, BlockColors.colorOf("minecraft:stone"));
        assertEquals(0x4040ff, BlockColors.colorOf("minecraft:water"));
        assertEquals(0x976d4d, BlockColors.colorOf("minecraft:dirt"));
        assertEquals(0xf7e9a3, BlockColors.colorOf("minecraft:sand"));
        assertEquals(0x191919, BlockColors.colorOf("minecraft:obsidian"));
        // 不带命名空间按 minecraft: 补齐
        assertEquals(0x7fb238, BlockColors.colorOf("grass_block"));
        // [Properties] 后缀剥离后查基名
        assertEquals(0x7fb238, BlockColors.colorOf("minecraft:grass_block[snowy=true]"));
        // 未收录 / null / 空 → 0x808080
        assertEquals(0x808080, BlockColors.colorOf("minecraft:definitely_not_a_block"));
        assertEquals(0x808080, BlockColors.colorOf(null));
        assertEquals(0x808080, BlockColors.colorOf(""));
        // 内置表 ≥120 条（实际由 1.20.1 MapColor 展开，远多于下限）
        assertTrue(BlockColors.tableSize() >= 120, "内置颜色表条目不足: " + BlockColors.tableSize());
    }
}
