package net.yumezawa.queqiao_map_web.testsupport;

import net.yumezawa.queqiao_map_web.platform.PlatformAdapter;

import java.util.List;
import java.util.function.Consumer;

/**
 * 手写假平台适配器（不用 mock 库）：记录注册与调用次数。
 * runAsync 同步执行在调用线程上，让 TileQueue 的「采样→打包→发送」在一次
 * onTick 内同步完成，事件顺序断言因此是确定性的。
 */
public final class FakePlatformAdapter implements PlatformAdapter {

    /** onTick 注册的回调（null = 未注册；enabled=false 时用于断言「零装配」） */
    public Consumer<List<PlayerSnapshot>> tickConsumer;
    /** onChunkDirty 注册的回调（null = 未注册） */
    public ChunkDirtyListener dirtyListener;

    /** renderChunk 调用次数（每 tick 上限 / 零渲染断言用） */
    public int renderCalls;
    /** runAsync 调用次数（零流量断言用） */
    public int runAsyncCalls;
    /** listDimensions 调用次数 */
    public int listDimensionsCalls;

    /** listDimensions 的返回值（registry key 风格） */
    public List<String> dimensions = List.of("minecraft:overworld");

    private byte[] solidRgb24;

    /** 让 renderChunk 恒返回该纯色 RGB24；传 null 模拟「区块未加载」 */
    public void solidChunk(Integer rgb) {
        if (rgb == null) {
            solidRgb24 = null;
            return;
        }
        solidRgb24 = new byte[16 * 16 * 3];
        for (int i = 0; i < solidRgb24.length; i += 3) {
            solidRgb24[i] = (byte) ((rgb >> 16) & 0xFF);
            solidRgb24[i + 1] = (byte) ((rgb >> 8) & 0xFF);
            solidRgb24[i + 2] = (byte) (rgb & 0xFF);
        }
    }

    @Override
    public void onTick(Consumer<List<PlayerSnapshot>> consumer) {
        tickConsumer = consumer;
    }

    @Override
    public void onChunkDirty(ChunkDirtyListener cb) {
        dirtyListener = cb;
    }

    @Override
    public byte[] renderChunk(String dimension, ChunkPos pos) {
        renderCalls++;
        return solidRgb24;
    }

    @Override
    public List<String> listDimensions() {
        listDimensionsCalls++;
        return dimensions;
    }

    @Override
    public void runAsync(Runnable task) {
        // 同步执行：测试线程内立刻完成量化/压缩/发送，无需等待与轮询
        runAsyncCalls++;
        task.run();
    }

    @Override
    public String platformName() {
        return "TEST";
    }

    /** 便捷造玩家快照 */
    public static PlayerSnapshot snapshot(String uuid, String name, double x, double y, double z,
                                          float yaw, String dimension) {
        PlayerSnapshot s = new PlayerSnapshot();
        s.uuid = uuid;
        s.name = name;
        s.x = x;
        s.y = y;
        s.z = z;
        s.yaw = yaw;
        s.dimension = dimension;
        return s;
    }
}
