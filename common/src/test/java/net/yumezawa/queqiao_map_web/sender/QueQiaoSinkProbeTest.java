package net.yumezawa.queqiao_map_web.sender;

import net.yumezawa.queqiao_map_web.event.MapDimensionsEvent;
import net.yumezawa.queqiao_map_web.testsupport.QueqiaoToolBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-SEND-002：QueQiaoSink 未就绪探针。探针口径：GlobalContext.getConfig()==null
 * 即鹊桥尚未 init（QueQiaoSink.isReady）；探针结果按 PROBE_INTERVAL_MS=2000ms 节流缓存。
 * 注意 BaseEvent 构造事件要求 config 非空：「未就绪」场景先构造事件、再清 config。
 */
class QueQiaoSinkProbeTest {

    @BeforeAll
    static void initQueqiaoStatics() {
        QueqiaoToolBootstrap.ensureDummyConfig();
    }

    @BeforeEach
    void ensureConfig() {
        QueqiaoToolBootstrap.ensureDummyConfig();
    }

    @AfterEach
    void restoreConfig() {
        // GlobalContext 是 JVM 全局静态：本类用例后恢复哑配置，避免跨类泄漏
        QueqiaoToolBootstrap.ensureDummyConfig();
    }

    @Test
    @Tag("REQ-SEND-002")
    @DisplayName("未就绪探针：getConfig()==null → 首探 false，2000ms 节流内再探仍 false；send 短路返回 false 不抛异常")
    void probeIsFalseBeforeQueqiaoReady() {
        // 先在 config 非空时构造事件（BaseEvent 硬依赖），再模拟「鹊桥未 init」窗口
        MapDimensionsEvent event = new MapDimensionsEvent(List.of("minecraft:overworld"));
        QueqiaoToolBootstrap.clearConfig();

        QueQiaoSink sink = new QueQiaoSink();
        assertFalse(sink.isReady(), "GlobalContext.init 之前探针必须 false");
        assertFalse(sink.isReady(), "节流窗口内复用缓存 false，仍未就绪");
        assertDoesNotThrow(() -> assertFalse(sink.send(event), "未就绪 send 必须短路 false"),
                "未就绪 send 不得抛异常");
        assertDoesNotThrow(() -> assertFalse(sink.send(event), "再次未就绪 send 仍 false"),
                "重复未就绪 send 不得抛异常");
        assertEquals("queqiao", sink.name());
    }

    @Test
    @Tag("REQ-SEND-002")
    @Tag("REQ-LIFE-004")
    @DisplayName("探针就绪但发送通道异常（websocketManager 未建立，sendEvent 抛出）：send 捕获后返回 false 不抛")
    void sendReturnsFalseWhenChannelThrows() {
        QueqiaoToolBootstrap.ensureDummyConfig();
        MapDimensionsEvent event = new MapDimensionsEvent(List.of("minecraft:overworld"));
        QueQiaoSink sink = new QueQiaoSink();
        assertTrue(sink.isReady(), "哑配置存在时探针判定就绪");
        assertDoesNotThrow(() -> assertFalse(sink.send(event), "sendEvent 抛出时 send 捕获返回 false"),
                "鹊桥通道异常时 send 不得向外抛异常");
    }
}
