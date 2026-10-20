package net.yumezawa.queqiao_map_web.event;

import com.github.theword.queqiao.tool.event.base.BaseEvent;

import java.util.List;

/**
 * 共享调色板事件：必须先于任何 map_tile / map_region 到达；首发后调色板冻结。
 * 已发布的快照按 palette_republish_interval_ms 周期重发同一 id+colors（REQ-PAL-003 晚接入自愈）。
 */
public final class MapPaletteEvent extends BaseEvent {

    private final String id;
    private final List<String> colors;

    public MapPaletteEvent(String id, List<String> colors) {
        super("MapPaletteEvent", "notice", "map_palette");
        this.id = id;
        this.colors = colors;
    }

    public String getId() {
        return id;
    }

    public List<String> getColors() {
        return colors;
    }
}
