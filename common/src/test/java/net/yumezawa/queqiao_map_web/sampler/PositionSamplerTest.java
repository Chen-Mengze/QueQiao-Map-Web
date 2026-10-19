package net.yumezawa.queqiao_map_web.sampler;

import net.yumezawa.queqiao_map_web.config.MapExtConfig;
import net.yumezawa.queqiao_map_web.model.PlayerPosModel;
import net.yumezawa.queqiao_map_web.platform.PlatformAdapter.PlayerSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** REQ-SAMP-*：位置差分采样节流（移动 ~2.5Hz / 静止心跳 ~0.67Hz / 位移阈值 1.5 格）。 */
class PositionSamplerTest {

    @TempDir
    Path dir;

    /** 缺文件 load → 全默认节流参数（move_threshold=1.5 / active=400ms / idle=1500ms） */
    private MapExtConfig defaults() throws IOException {
        return MapExtConfig.load(dir.resolve("defaults.yml"));
    }

    private static PlayerSnapshot snap(String uuid, double x) {
        PlayerSnapshot s = new PlayerSnapshot();
        s.uuid = uuid;
        s.name = "p-" + uuid;
        s.x = x;
        s.y = 64;
        s.z = 0;
        s.yaw = 0f;
        s.dimension = "minecraft:overworld";
        return s;
    }

    @Test
    @Tag("REQ-SAMP-001")
    @DisplayName("首见玩家立即上报（upsert）；位移 <1.5 格且未到心跳 → 不上报")
    void smallMoveNotSent() throws IOException {
        PositionSampler sampler = new PositionSampler(defaults());
        assertEquals(1, sampler.sample(List.of(snap("u1", 0)), 10_000).size(), "首见必报");
        List<PlayerPosModel> out = sampler.sample(List.of(snap("u1", 1.0)), 10_100);
        assertTrue(out.isEmpty(), "位移 1.0 < 阈值 1.5 且未到 1500ms 心跳 → 不报");
    }

    @Test
    @Tag("REQ-SAMP-001")
    @DisplayName("位移恰等于阈值 1.5 → 视作静止（源码 distSq > thresholdSq 为严格大于）")
    void thresholdBoundaryCountsAsIdle() throws IOException {
        PositionSampler sampler = new PositionSampler(defaults());
        assertEquals(1, sampler.sample(List.of(snap("u1", 0)), 0).size());
        assertTrue(sampler.sample(List.of(snap("u1", 1.5)), 400).isEmpty(),
                "恰 1.5 → 不算移动；未到 1500ms 心跳 → 不报");
    }

    @Test
    @Tag("REQ-SAMP-002")
    @DisplayName("静止心跳：不动时按 move_idle_interval_ms=1500ms 每跳一次（约 0.67Hz）")
    void idleHeartbeatCadence() throws IOException {
        PositionSampler sampler = new PositionSampler(defaults());
        assertEquals(1, sampler.sample(List.of(snap("u1", 0)), 0).size());
        assertTrue(sampler.sample(List.of(snap("u1", 0)), 1_499).isEmpty(), "1499ms 未到 → 不报");
        assertEquals(1, sampler.sample(List.of(snap("u1", 0)), 1_500).size(), "1500ms 到点 → 心跳上报");
        assertTrue(sampler.sample(List.of(snap("u1", 0)), 2_999).isEmpty(), "第二跳间隔 1499ms → 不报");
    }

    @Test
    @Tag("REQ-SAMP-003")
    @DisplayName("移动限速：位移 >1.5 格也仅按 move_active_interval_ms=400ms 每报一次（约 2.5Hz）")
    void movingRateLimited() throws IOException {
        PositionSampler sampler = new PositionSampler(defaults());
        assertEquals(1, sampler.sample(List.of(snap("u1", 0)), 0).size());
        assertTrue(sampler.sample(List.of(snap("u1", 5)), 399).isEmpty(), "位移 5 格但距上次仅 399ms → 限流不报");
        assertEquals(1, sampler.sample(List.of(snap("u1", 10)), 400).size(), "400ms 到点 → 上报");
        assertTrue(sampler.sample(List.of(snap("u1", 15)), 799).isEmpty(), "再隔 399ms → 仍限流");
    }

    @Test
    @Tag("REQ-SAMP-004")
    @DisplayName("玩家离线 → 陈旧状态自动清理；重连按首见全量补报一次")
    void offlineStateAutoCleaned() throws IOException {
        PositionSampler sampler = new PositionSampler(defaults());
        assertEquals(1, sampler.sample(List.of(snap("u1", 0)), 0).size());
        assertTrue(sampler.sample(List.of(), 100).isEmpty(), "本轮无人 → 空批");
        assertEquals(1, sampler.sample(List.of(snap("u1", 0)), 200).size(),
                "离线已清状态 → 重连首见立即上报（若状态残留，200ms 会被 1500ms 心跳挡住）");
    }
}
