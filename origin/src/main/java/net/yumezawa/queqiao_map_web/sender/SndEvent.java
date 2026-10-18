package net.yumezawa.queqiao_map_web.sender;

import com.github.theword.queqiao.tool.event.base.BaseEvent;

/**
 * 发送器抽象。send 返回 false = 通道未就绪或失败，调用方静默丢弃 / 退回队列，
 * 绝不在发送路径抛异常打断服务端。
 */
public interface SndEvent {

    boolean send(BaseEvent event);

    /** 通道是否可用（QueQiao 需 GlobalContext 已 init） */
    boolean isReady();

    String name();
}
