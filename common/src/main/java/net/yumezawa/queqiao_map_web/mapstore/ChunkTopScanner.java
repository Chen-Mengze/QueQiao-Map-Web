package net.yumezawa.queqiao_map_web.mapstore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 块 NBT → 每列最高非空气方块状态名（蓝图 §2.3）。
 *
 * <p>支持三种 section 编码：
 * ① 1.20.1 及以前：section 内 "BlockStatePalette"（List&lt;Map&gt;，元素含 "Name"）+ "BlockStates"（long[]）；
 * ② 1.20.2 内联：section 内 "block_states" Compound → "palette"（List，元素为 Map{Name} 或裸 String）+ "data"（long[]）；
 * ③ 1.20.2 根引用：根 Compound 顶层 "palettes"（List，元素 {"id":…, "palette":[…]}），
 *    section 的 "block_states" 用 "palette_ref"/"paletteRef"/"ref" 引用 id。</p>
 *
 * <p>位打包：bits = max(4, ceil(log2(paletteSize)))；现代对齐打包取值
 * {@code (longs[total>>6] >>> bitOffset) | (longs[(total>>6)+1] << (64-bitOffset))}（total = 索引×bits）；
 * 若 entryCount×bits &gt; longs.length×64 判定为旧式补齐打包兜底，逐条仍从 i×bits/64 起按位取
 * （两种编码的读取公式一致，差异只在写入端是否补零）；越界返回 -1 按空气处理。</p>
 *
 * <p>输出 String[256]，行主序 index = lz×16+lx；无地面列（整列空气）= null；水柱顶即水；
 * Y &lt; minBuildHeight 的方块跳过。异常吞掉记 FINE，返回已填好的部分。</p>
 */
public final class ChunkTopScanner {

    private static final Logger LOG = Logger.getLogger("queqiao_map_web.mapstore");

    /** 视为空气的状态名（含不带命名空间的容错写法） */
    private static final Set<String> AIR = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "air", "cave_air", "void_air");

    /** 每层 section 的方块数（16×16×16） */
    private static final int BLOCKS_PER_SECTION = 4096;

    private ChunkTopScanner() {
    }

    /**
     * 扫描块 NBT，返回 256 列的顶面状态名。
     *
     * @param chunkNbt       {@link NbtReader#readCompound} 产出的根 Compound
     * @param minBuildHeight 该维度最低建筑高度（低于它的方块忽略；overworld 1.18+ 为 -64）
     * @return 长度 256 的数组，index = lz×16+lx；null 元素 = 该列无地面
     */
    public static String[] scanTopStates(Map<String, Object> chunkNbt, int minBuildHeight) {
        String[] top = new String[256];
        try {
            Object sectionsObj = chunkNbt.get("sections");
            if (!(sectionsObj instanceof List<?> sections)) {
                return top;
            }
            // 根 palettes（1.20.2 引用式）：id → palette 列表
            List<?> rootPalettes = chunkNbt.get("palettes") instanceof List<?> rp ? rp : List.of();
            List<Section> parsed = new ArrayList<>();
            for (Object s : sections) {
                if (!(s instanceof Map<?, ?> section)) {
                    continue;
                }
                Object yObj = section.get("Y");
                if (!(yObj instanceof Number yNum)) {
                    continue;
                }
                int sectionY = yNum.intValue(); // 1.18+ 为绝对 section 坐标
                if (sectionY * 16L + 15 < minBuildHeight) {
                    continue; // 整层低于最低建筑高度
                }
                PaletteData pd = extractPaletteData(section, rootPalettes);
                if (pd == null) {
                    continue;
                }
                parsed.add(new Section(sectionY, pd));
            }
            parsed.sort(Comparator.comparingInt((Section s) -> s.y).reversed());
            for (Section s : parsed) {
                scanSection(s, top, minBuildHeight);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "扫描块顶面失败，返回部分结果", e);
        }
        return top;
    }

    /** palette 名称表 + 位打包数据 */
    private record PaletteData(List<String> names, long[] data) {
    }

    private record Section(int y, PaletteData pd) {
    }

    /** 从一个 section 提取 palette 与位打包数据，兼容三种编码 */
    private static PaletteData extractPaletteData(Map<?, ?> section, List<?> rootPalettes) {
        // ① 1.20.1 及以前
        if (section.get("BlockStatePalette") instanceof List<?> palette
                && section.get("BlockStates") instanceof long[] states) {
            return new PaletteData(namesOf(palette), states);
        }
        // ② / ③ 1.20.2：block_states 内联或引用根 palettes
        if (section.get("block_states") instanceof Map<?, ?> bs) {
            long[] data = bs.get("data") instanceof long[] d ? d : null;
            if (data == null) {
                return null;
            }
            if (bs.get("palette") instanceof List<?> palette) {
                return new PaletteData(namesOf(palette), data);
            }
            Object ref = bs.get("palette_ref") != null ? bs.get("palette_ref")
                    : bs.get("paletteRef") != null ? bs.get("paletteRef") : bs.get("ref");
            if (ref instanceof Number refNum) {
                for (Object p : rootPalettes) {
                    if (p instanceof Map<?, ?> pm && pm.get("id") instanceof Number id
                            && id.intValue() == refNum.intValue()
                            && pm.get("palette") instanceof List<?> palette) {
                        return new PaletteData(namesOf(palette), data);
                    }
                }
            }
        }
        return null;
    }

    /** palette 元素 → 状态名（Map 取 "Name"，缺省 air；裸 String 直接用） */
    private static List<String> namesOf(List<?> palette) {
        List<String> names = new ArrayList<>(palette.size());
        for (Object entry : palette) {
            if (entry instanceof Map<?, ?> em) {
                Object name = em.get("Name");
                names.add(name instanceof String str ? str : "minecraft:air");
            } else if (entry instanceof String str) {
                names.add(str);
            } else {
                names.add("minecraft:air");
            }
        }
        return names;
    }

    /** 扫一层 section：自顶向下，每列只取第一个非空气 */
    private static void scanSection(Section s, String[] top, int minBuildHeight) {
        List<String> names = s.pd.names();
        long[] data = s.pd.data();
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(names.size() - 1));
        // 旧式补齐打包判定（读取公式一致，仅做记录与越界兜底）
        boolean paddedFallback = (long) BLOCKS_PER_SECTION * bits > (long) data.length * 64;
        if (paddedFallback) {
            LOG.log(Level.FINE, "BlockStates 长度不足按对齐打包解释，按旧式补齐兜底: bits={0} longs={1}",
                    new Object[]{bits, data.length});
        }
        // 自上而下（i 递减）：每列先遇到最高方块，第一个非空气即顶面
        for (int i = BLOCKS_PER_SECTION - 1; i >= 0; i--) {
            int ly = i >> 8;          // 层内 y（blockStates 顺序 = (y*16+z)*16+x）
            int lz = (i >> 4) & 15;
            int lx = i & 15;
            int col = lz * 16 + lx;
            if (top[col] != null) {
                continue; // 更高处已有地面
            }
            int blockY = s.y * 16 + ly;
            if (blockY < minBuildHeight) {
                continue;
            }
            int idx = readPackedEntry(data, i, bits);
            if (idx < 0 || idx >= names.size()) {
                continue; // 越界/未收录索引按空气处理
            }
            String name = names.get(idx);
            if (AIR.contains(name)) {
                continue;
            }
            top[col] = name;
        }
    }

    /** 位打包取值：现代对齐与旧式补齐读取公式一致；越界返回 -1 */
    private static int readPackedEntry(long[] longs, int entry, int bits) {
        int total = entry * bits;
        int word = total >> 6;
        int bitOffset = total & 63;
        if (word >= longs.length) {
            return -1;
        }
        long v = longs[word] >>> bitOffset;
        if (bitOffset != 0 && word + 1 < longs.length) {
            v |= longs[word + 1] << (64 - bitOffset);
        }
        return (int) (v & ((1L << bits) - 1));
    }
}
