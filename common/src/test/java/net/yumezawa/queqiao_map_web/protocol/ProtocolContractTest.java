package net.yumezawa.queqiao_map_web.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.yumezawa.queqiao_map_web.event.MapDimensionsEvent;
import net.yumezawa.queqiao_map_web.event.MapPaletteEvent;
import net.yumezawa.queqiao_map_web.event.MapTileEvent;
import net.yumezawa.queqiao_map_web.event.PlayerMoveEvent;
import net.yumezawa.queqiao_map_web.model.PlayerPosModel;
import net.yumezawa.queqiao_map_web.model.TilePayloadModel;
import net.yumezawa.queqiao_map_web.testsupport.QueqiaoToolBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** REQ-PROTO-*：四事件字面量与 DTO JSON 字段名契约（Gson 反射直出，单单词 camelCase）。 */
class ProtocolContractTest {

    @BeforeAll
    static void initQueqiaoStatics() {
        // 事件构造器经 BaseEvent 硬依赖 GlobalContext.getConfig()（鹊桥库行为）
        QueqiaoToolBootstrap.ensureDummyConfig();
    }

    /** BaseEvent 继承来的基字段（鹊桥协议自带；Gson 直出事件 JSON 时会出现）
     * 注意：BaseEvent 的字段名本身就是 snake_case（鹊桥线上协议字段原形，Gson 反射直出即此形态）；
     * 「单单词 camelCase」约定只约束我们的载荷 DTO（见 DTO 键集用例）。 */
    private static final Set<String> EVENT_BASE = Set.of(
            "event_name", "post_type", "sub_type", "timestamp", "server_name", "server_version", "server_type");

    private final Gson gson = new Gson();

    /** 鹊桥自动补齐、绝不允许出现在我们 DTO 里的字段（INTEGRATION §1） */
    private static final List<String> FORBIDDEN = List.of(
            "server_name", "serverName", "server_version", "serverVersion",
            "server_type", "serverType", "timestamp");

    @Test
    @Tag("REQ-PROTO-001")
    @DisplayName("四事件：eventName=类名、postType=notice、sub_type 字面量 player_move/map_palette/map_tile/map_dimensions")
    void eventNameAndSubTypeLiterals() {
        PlayerMoveEvent pm = new PlayerMoveEvent(List.of());
        assertEquals("PlayerMoveEvent", pm.getEventName());
        assertEquals("notice", pm.getPostType());
        assertEquals("player_move", pm.getSubType());

        MapPaletteEvent mp = new MapPaletteEvent("p", List.of());
        assertEquals("MapPaletteEvent", mp.getEventName());
        assertEquals("map_palette", mp.getSubType());

        MapTileEvent mt = new MapTileEvent("minecraft:overworld", List.of());
        assertEquals("MapTileEvent", mt.getEventName());
        assertEquals("map_tile", mt.getSubType());

        MapDimensionsEvent md = new MapDimensionsEvent(List.of());
        assertEquals("MapDimensionsEvent", md.getEventName());
        assertEquals("map_dimensions", md.getSubType());
    }

    @Test
    @Tag("REQ-PROTO-002")
    @DisplayName("PlayerPosModel Gson 直出 JSON 字段名恰为 uuid/nickname/x/y/z/yaw/dimension，无鹊桥自动字段")
    void playerModelJsonFieldNames() {
        String json = gson.toJson(new PlayerPosModel(
                "8b8d5a6e", "Yumezawa", 128.5, 64.0, -20.25, 90.0f, "minecraft:overworld"));
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(Set.of("uuid", "nickname", "x", "y", "z", "yaw", "dimension"), obj.keySet());
        for (String f : FORBIDDEN) assertFalse(obj.has(f), "不得含鹊桥自动字段 " + f);
    }

    @Test
    @Tag("REQ-PROTO-002")
    @DisplayName("TilePayloadModel Gson 直出 JSON 字段名恰为 cx/cz/size/format/sha1/b64")
    void tileModelJsonFieldNames() {
        String json = gson.toJson(new TilePayloadModel(3, -2, 8, "zlib-pal8",
                "0123456789abcdef0123456789abcdef01234567", "AAAA"));
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(Set.of("cx", "cz", "size", "format", "sha1", "b64"), obj.keySet());
        for (String f : FORBIDDEN) assertFalse(obj.has(f));
    }

    @Test
    @Tag("REQ-PROTO-002")
    @DisplayName("MapPaletteEvent/MapDimensionsEvent JSON = BaseEvent 基字段 + id,colors / dimensions，无其它我们的字段")
    void eventPayloadJsonShape() {
        JsonObject pal = JsonParser.parseString(
                gson.toJson(new MapPaletteEvent("vanilla-1.20.1-a", List.of("#000000")))).getAsJsonObject();
        Set<String> allowedPalette = new HashSet<>(EVENT_BASE);
        allowedPalette.add("id");
        allowedPalette.add("colors");
        assertEquals(allowedPalette, pal.keySet(), "调色板事件键集 = 基字段 + 载荷字段");

        JsonObject dim = JsonParser.parseString(
                gson.toJson(new MapDimensionsEvent(List.of("minecraft:overworld")))).getAsJsonObject();
        Set<String> allowedDims = new HashSet<>(EVENT_BASE);
        allowedDims.add("dimensions");
        assertEquals(allowedDims, dim.keySet(), "维度事件键集 = 基字段 + dimensions");
    }

    @Test
    @Tag("REQ-PROTO-003")
    @DisplayName("dimension 取 registry key 原样透传：minecraft:the_nether 写入即读出并原样进 JSON")
    void dimensionRegistryKeyPassthrough() {
        PlayerPosModel m = new PlayerPosModel("u", "n", 0, 0, 0, 0f, "minecraft:the_nether");
        assertEquals("minecraft:the_nether", m.getDimension());
        assertEquals("minecraft:the_nether", JsonParser.parseString(gson.toJson(m))
                .getAsJsonObject().get("dimension").getAsString());
        assertEquals("minecraft:the_end", new MapTileEvent("minecraft:the_end", List.of()).getDimension());
        List<String> dims = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
        assertEquals(dims, new MapDimensionsEvent(dims).getDimensions());
    }
}
