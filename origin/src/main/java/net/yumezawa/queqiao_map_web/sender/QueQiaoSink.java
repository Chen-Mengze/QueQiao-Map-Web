package net.yumezawa.queqiao_map_web.sender;

import com.github.theword.queqiao.tool.GlobalContext;
import com.github.theword.queqiao.tool.event.base.BaseEvent;

/**
 * 首选发送器：直连鹊桥 GlobalContext.sendEvent。
 * 【全工程唯一】允许 import com.github.theword.queqiao.tool.GlobalContext 的文件。
 * 运行期该类由鹊桥本体 jar 提供（编译期 compileOnly libs/queqiao-tool-0.6.8.jar）。
 */
public final class QueQiaoSink implements EventSink {

    private static final long PROBE_INTERVAL_MS = 2000;
    private long lastProbeMs;
    private boolean ready;

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
