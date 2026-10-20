package net.yumezawa.queqiao_map_web.sender;

import com.github.theword.queqiao.tool.GlobalContext;
import com.github.theword.queqiao.tool.event.base.BaseEvent;

/**
 * 首选发送器：直连鹊桥 GlobalContext.sendEvent。
 * 【全工程唯一】允许 import com.github.theword.queqiao.tool.GlobalContext 的文件。
 * 编译期按坐标 com.github.theword.queqiao:queqiao-tool 以 compileOnly 引入（见 AGENT.md §3），绝不 shade。
 */
public final class QueQiaoSink implements EventSink {

    private static final long PROBE_INTERVAL_MS = 2000;
    // 主线程与后台 flush 线程都会调用 isReady()：volatile 保证探针结果跨线程可见
    // （两线程同时越过节流各探一次属良性）
    private volatile long lastProbeMs;
    private volatile boolean ready;

    @Override
    public boolean send(BaseEvent event) {
        if (!isReady()) return false;
        try {
            GlobalContext.sendEvent(event);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isReady() {
        long now = System.currentTimeMillis();
        if (now - lastProbeMs < PROBE_INTERVAL_MS) return ready;
        lastProbeMs = now;
        try {
            // getConfig() 在 GlobalContext.init 之前为 null：以此判定鹊桥已就绪
            ready = GlobalContext.getConfig() != null;
        } catch (Throwable t) {
            ready = false;
        }
        return ready;
    }

    @Override
    public String name() {
        return "queqiao";
    }
}
