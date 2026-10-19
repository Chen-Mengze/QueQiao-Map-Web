package net.yumezawa.queqiao_map_web.testsupport;

import com.github.theword.queqiao.tool.event.base.BaseEvent;
import net.yumezawa.queqiao_map_web.sender.EventSink;

import java.util.ArrayList;
import java.util.List;

/**
 * 手写记录发送器（不用 mock 库）：记录每一次进入 send 的事件
 * （send 返回 false 的「静默丢弃」尝试同样被记录，便于断言零流量/失败退回），
 * 并可控制 isReady 与 send 的返回值。
 */
public final class RecordingEventSink implements EventSink {

    /** 所有进入 send() 的事件，按到达顺序 */
    public final List<BaseEvent> sent = new ArrayList<>();

    private final boolean ready;
    private final boolean sendOk;

    public RecordingEventSink(boolean ready, boolean sendOk) {
        this.ready = ready;
        this.sendOk = sendOk;
    }

    @Override
    public boolean send(BaseEvent event) {
        sent.add(event);
        return sendOk;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public String name() {
        return "recording";
    }

    /** 按 sub_type 看事件序列，供「按序出现」断言 */
    public List<String> subTypes() {
        List<String> out = new ArrayList<>(sent.size());
        for (BaseEvent e : sent) out.add(e.getSubType());
        return out;
    }
}
