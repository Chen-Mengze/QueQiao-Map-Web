package net.yumezawa.queqiao_map_web.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享 256 色调色板：首次刷写瓦片前随 EvtMapPalette 发送一次，随后冻结。
 * 下标 0 固定保留为「未设置」（批量区域里未加载/非脏区块占位）。
 * 冻结后出现的新颜色回退到最近色（RGB 欧氏距离）。
 */
public final class RndPalette {

    public static final int MAX = 256;
    public static final int UNSET = 0;

    private final List<String> colors = new ArrayList<>();
    private final Map<Integer, Integer> indexByRgb = new HashMap<>();
    private final List<int[]> rgbList = new ArrayList<>();
    private boolean frozen;

    public RndPalette() {
        colors.add("#000000"); // 下标 0 = 未设置
        rgbList.add(new int[]{0, 0, 0});
    }

    /** rgb = 0xRRGGBB；返回调色板下标（1..255） */
    public synchronized int indexOf(int rgb) {
        if (rgb == 0) return UNSET;
        Integer known = indexByRgb.get(rgb);
        if (known != null) return known;
        if (!frozen && colors.size() < MAX) {
            int idx = colors.size();
            colors.add(toHex(rgb));
            rgbList.add(new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF});
            indexByRgb.put(rgb, idx);
            return idx;
        }
        return nearest(rgb);
    }

    private int nearest(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int best = UNSET, bestDist = Integer.MAX_VALUE;
        for (int i = 1; i < rgbList.size(); i++) {
            int[] c = rgbList.get(i);
            int dr = c[0] - r, dg = c[1] - g, db = c[2] - b;
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /** 随 EvtMapPalette 发送成功后调用：冻结，之后的颜色一律就近取整 */
    public synchronized void freeze() {
        frozen = true;
    }

    public synchronized List<String> colors() {
        return new ArrayList<>(colors);
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    private static String toHex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }
}
