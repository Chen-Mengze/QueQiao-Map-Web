package net.yumezawa.queqiao_map_web.tile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** REQ-PAL-*：共享 256 色调色板（下标 0 保留未设置；发出后冻结）。 */
class PaletteTest {

    @Test
    @Tag("REQ-PAL-001")
    @DisplayName("下标 0 保留为未设置：indexOf(0)=0 且 colors[0]=\"#000000\"")
    void indexZeroReservedAsUnset() {
        Palette p = new Palette();
        assertEquals(0, p.indexOf(0));
        assertEquals("#000000", p.colors().get(0));
        assertEquals(1, p.colors().size(), "新调色板只含下标 0");
        assertEquals(0, p.indexOf(0x000000), "纯黑同样视作未设置（rgb==0）");
    }

    @Test
    @Tag("REQ-PAL-001")
    @DisplayName("调色板上限 256：加满 255 色得到下标 1..255；满后新色就近取整、尺寸不增")
    void capacityCappedAt256() {
        Palette p = new Palette();
        for (int i = 1; i <= 255; i++) {
            int rgb = (i << 8) | i; // 0x000101..0xff00ff，255 个互不相同的非零色
            assertEquals(i, p.indexOf(rgb), "第 " + i + " 个新色应落在下标 " + i);
        }
        assertEquals(Palette.MAX, p.colors().size());
        int extra = p.indexOf(0x010101);
        assertTrue(extra >= 1 && extra <= 255, "满后新色回退到既有最近下标");
        assertEquals(Palette.MAX, p.colors().size(), "满后不得再增长");
        assertEquals(extra, p.indexOf(0x010101), "同色恒同下标（去重复用）");
    }

    @Test
    @Tag("REQ-PAL-002")
    @DisplayName("发出后冻结：colors() 内容不再改变；新色不再入板、就近取既有色")
    void freezeKeepsSentContentFrozen() {
        Palette p = new Palette();
        int red = p.indexOf(0xff0000);
        List<String> snapshot = p.colors();
        p.freeze();
        assertTrue(p.isFrozen());
        assertEquals(red, p.indexOf(0xfe0101), "冻结后近色就近取既有色（红）");
        p.indexOf(0x0000ff); // 远色也不得新增条目
        assertEquals(snapshot, p.colors(), "冻结后已发调色板内容永久不变");
        assertEquals(2, p.colors().size());
    }

    @Test
    @Tag("REQ-PAL-002")
    @DisplayName("colors() 返回快照拷贝：外部改动无法篡改已发内容")
    void colorsReturnsDefensiveCopy() {
        Palette p = new Palette();
        p.indexOf(0x112233);
        List<String> copy = p.colors();
        copy.set(1, "#ffffff");
        copy.add("#123456");
        assertEquals(2, p.colors().size());
        assertEquals("#112233", p.colors().get(1));
    }
}
