package net.yumezawa.queqiao_map_web.event;

import com.github.theword.queqiao.tool.event.base.BaseEvent;
import net.yumezawa.queqiao_map_web.model.TilePayloadModel;

import java.util.List;

/** 地图瓦片批量事件；dimension 一律 registry key */
public final class MapTileEvent extends BaseEvent {

    private final String dimension;
    private final List<TilePayloadModel> tiles;

    public MapTileEvent(String dimension, List<TilePayloadModel> tiles) {
        super("MapTileEvent", "notice", "map_tile");
        this.dimension = dimension;
        this.tiles = tiles;
    }

    public String getDimension() {
        return dimension;
    }

    public List<TilePayloadModel> getTiles() {
        return tiles;
    }
}
