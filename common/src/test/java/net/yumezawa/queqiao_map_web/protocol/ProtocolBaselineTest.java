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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-PROTO-004：协议字段基线只增不减（INTEGRATION「字段只增不改不删」的回归防线）。
 * 基线 = BaseEvent 继承基字段 + 各事件自有载荷字段，必须 ⊆ 当前 Gson 键集。
 */
class ProtocolBaselineTest {

    @BeforeAll
    static void initQueqiaoStatics() {
        // serverName/serverVersion/serverType 需哑 config + 反射哑 static，Gson 才不会省略基字段
        QueqiaoToolBootstrap.ensureDummyConfig();
    }

    private static final Gson GSON = new Gson();

    /** BaseEvent 继承来的基字段（鹊桥协议自带）
     * 注意：BaseEvent 的字段名本身就是 snake_case（鹊桥线上协议字段原形，Gson 反射直出即此形态）；
     * 「单单词 camelCase」约定只约束我们的载荷 DTO（见 DTO 键集用例）。 */
    private static final Set<String> EVENT_BASE = Set.of(
            "event_name", "post_type", "sub_type", "timestamp", "server_name", "server_version", "server_type");

    @Test
    @Tag("REQ-PROTO-004")
    @DisplayName("协议基线核对：四事件 Gson JSON 键集含全部基线字段（BaseEvent 基字段 + 载荷字段），只增不减")
    void jsonFieldSetOnlyGrows() {
        assertBaseline(Set.of("players"),
                GSON.toJson(new PlayerMoveEvent(List.of(
                        new PlayerPosModel("u", "n", 1, 2, 3, 0f, "minecraft:overworld")))));
        assertBaseline(Set.of("id", "colors"),
                GSON.toJson(new MapPaletteEvent("vanilla-1.20.1-a", List.of("#000000"))));
        assertBaseline(Set.of("dimension", "tiles"),
                GSON.toJson(new MapTileEvent("minecraft:overworld", List.of(
                        new TilePayloadModel(0, 0, 1, "zlib-pal8", "0".repeat(40), "AA")))));
        assertBaseline(Set.of("dimensions"),
                GSON.toJson(new MapDimensionsEvent(List.of("minecraft:overworld"))));
    }

    private static void assertBaseline(Set<String> payloadFields, String json) {
        Set<String> baseline = new HashSet<>(EVENT_BASE);
        baseline.addAll(payloadFields);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        Set<String> current = obj.keySet();
        assertTrue(current.containsAll(baseline),
                "基线字段不得收缩：基线=" + baseline + " 当前=" + current);
    }
}
