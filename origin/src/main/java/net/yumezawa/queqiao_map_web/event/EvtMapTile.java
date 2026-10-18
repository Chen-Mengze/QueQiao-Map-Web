package net.yumezawa.queqiao_map_web.event;

import com.github.theword.queqiao.tool.event.base.BaseEvent;
import net.yumezawa.queqiao_map_web.model.MdlTilePayload;

import java.util.List;

/** 地图瓦片批量事件；dimension 一律 registry key */
public final class EvtMapTile extends BaseEvent {

    private final String dimension;
    private final List<MdlTilePayload> tiles;

    public EvtMapTile(String dimension, List<MdlTilePayload> tiles) {
        super("EvtMapTile", "notice", "map_tile");
        this.dimension = dimension;
        this.tiles = tiles;
    }

    public String getDimension() {
        return dimension;
    }

    public List<MdlTilePayload> getTiles() {
        return tiles;
    }
}
