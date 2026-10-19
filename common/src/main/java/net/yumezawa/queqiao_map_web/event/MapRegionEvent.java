package net.yumezawa.queqiao_map_web.event;

import com.github.theword.queqiao.tool.event.base.BaseEvent;
import net.yumezawa.queqiao_map_web.model.RegionPayloadModel;

import java.util.List;

/**
 * 区域快照事件（REQ-PROTO-005）：一次携带至多 regionStreamMaxRegionsPerEvent 个区域。
 * 顺序铁律：必须先有任何 map_palette 事件（RegionStreamer 经 TileQueue.publishPalette 保证）。
 * dimension 为 registry key（与 map_tile 同源）。
 */
public final class MapRegionEvent extends BaseEvent {

    private final String dimension;
    private final List<RegionPayloadModel> regions;

    public MapRegionEvent(String dimension, List<RegionPayloadModel> regions) {
        super("MapRegionEvent", "notice", "map_region");
        this.dimension = dimension;
        this.regions = regions;
    }

    public String getDimension() {
        return dimension;
    }

    public List<RegionPayloadModel> getRegions() {
        return regions;
    }
}
