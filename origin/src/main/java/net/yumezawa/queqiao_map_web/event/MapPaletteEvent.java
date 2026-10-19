package net.yumezawa.queqiao_map_web.event;

import com.github.theword.queqiao.tool.event.base.BaseEvent;

import java.util.List;

/** 共享调色板事件：连接后只发一次，且必须先于任何 MapTileEvent 到达 */
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
