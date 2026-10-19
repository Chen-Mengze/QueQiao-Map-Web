package net.yumezawa.queqiao_map_web.event;

import com.github.theword.queqiao.tool.event.base.BaseEvent;

import java.util.List;

/** 维度清单事件：连接后发一次 + 清单变化时重发；元素一律 registry key */
public final class MapDimensionsEvent extends BaseEvent {

    private final List<String> dimensions;

    public MapDimensionsEvent(List<String> dimensions) {
        super("MapDimensionsEvent", "notice", "map_dimensions");
        this.dimensions = dimensions;
    }

    public List<String> getDimensions() {
        return dimensions;
    }
}
