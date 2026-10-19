package net.yumezawa.queqiao_map_web.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 平台适配接口（本目录不含任何 net.minecraft.* 类）。
 * origin 主结构与 MC 的唯一边界；由 fabric/origin、forge/origin、neoforge/origin 各实现一份。
 */
public interface PlatformAdapter {

    final class PlayerSnapshot {
        public String uuid, name;
        public double x, y, z;
        public float yaw;
        /** registry key，如 minecraft:the_nether。绝不用世界文件夹名 */
        public String dimension;
    }

    final class ChunkPos {
        public final int x, z;

        public ChunkPos(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ChunkPos other)) return false;
            return x == other.x && z == other.z;
        }

        @Override
        public int hashCode() {
            return 31 * x + z;
        }

        @Override
        public String toString() {
            return x + "," + z;
        }
    }

    interface ChunkDirtyListener {
        void onChunkDirty(String dimension, ChunkPos pos);
    }

    /** 注册主线程 tick 回调；consumer 收到的快照列表已含 dimension */
    void onTick(Consumer<List<PlayerSnapshot>> consumer);

    /** 注册脏区块回调（区块加载 / 方块改动）；实现负责去重前的原始事件转发 */
    void onChunkDirty(ChunkDirtyListener cb);

    /**
     * 在【主线程】采样一个区块的顶层方块，返回 16*16*3 的 RGB24 字节（行优先，左上原点）；
     * 区块未加载返回 null。只许从内存取（区块加载/改动时机），禁止读 region 文件。
     * 量化 / 压缩 / 打包由 origin 的 TopBlockRenderer 在后台线程完成。
     */
    byte[] renderChunk(String dimension, ChunkPos pos);

    /** 全部维度，registry key */
    List<String> listDimensions();

    /**
     * 世界存档根目录（world/ 本体，含 region/ 与 dimension/）；
     * 返回 null = 平台未提供（地图库/回填/区域流整体关闭，REQ-LIFE-005）。
     * 维度 → mca 目录换算由 common 的 MapStore 完成（DESIGN_MAPSTORE §2.6）。
     */
    Path worldSaveRoot();

    /** 后台线程执行（量化/压缩/发送等重活） */
    void runAsync(Runnable task);

    /** ServerTypeConstant 之一：FABRIC / FORGE / NEOFORGE */
    String platformName();
}
