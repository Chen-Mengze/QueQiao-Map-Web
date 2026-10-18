package net.yumezawa.queqiao_map_web.sender;

import com.github.theword.queqiao.tool.event.base.BaseEvent;

import java.util.logging.Logger;

/**
 * 兜底发送器：无鹊桥时直连自建 WS（standalone_ws_url）。
 * v1 骨架：未实现 WS 前 send 一律返回 false（发送失败静默丢弃铁律），
 * 队列会自然积压到 pending_cap 后丢弃，不影响服务端。
 */
public final class StandaloneSink implements EventSink {

    private static final Logger LOG = Logger.getLogger("queqiao_map_web.sink");

    private final String wsUrl;
    private boolean warned;

    public StandaloneSink(String wsUrl) {
        this.wsUrl = wsUrl == null ? "" : wsUrl.trim();
    }

    @Override
    public boolean send(BaseEvent event) {
        if (wsUrl.isEmpty()) return false;
        if (!warned) {
            warned = true;
            LOG.info("standalone WS 发送尚未实现（v1 骨架），事件按铁律静默丢弃: " + wsUrl);
        }
        // TODO(M2): java.net.http.WebSocket 连 wsUrl，BaseEvent → 协议 JSON
        return false;
    }

    @Override
    public boolean isReady() {
        return false; // v1：WS 未实现前永远未就绪
    }

    @Override
    public String name() {
        return "standalone";
    }
}
