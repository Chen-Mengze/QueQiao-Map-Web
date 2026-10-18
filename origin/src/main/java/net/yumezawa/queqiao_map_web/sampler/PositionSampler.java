package net.yumezawa.queqiao_map_web.sampler;

import net.yumezawa.queqiao_map_web.config.MapExtConfig;
import net.yumezawa.queqiao_map_web.model.PlayerPosModel;
import net.yumezawa.queqiao_map_web.platform.PlatformAdapter.PlayerSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 位置差分采样：节流 + last-value 合并。
 * 禁止每 tick 全量广播——移动中 moveActiveIntervalMs，静止退到 moveIdleIntervalMs 心跳，
 * 位移小于阈值且未到心跳时间则完全跳过。主线程调用。
 */
public final class PositionSampler {

    private static final class Last {
        final PlayerPosModel model;
        final long sentAtMs;

        Last(PlayerPosModel model, long sentAtMs) {
            this.model = model;
            this.sentAtMs = sentAtMs;
        }
    }

    private final MapExtConfig config;
    private final Map<String, Last> state = new HashMap<>();

    public PositionSampler(MapExtConfig config) {
        this.config = config;
    }

    /** 返回本 tick 应上报的批量（空表 = 什么都不发） */
    public List<PlayerPosModel> sample(List<PlayerSnapshot> snapshots, long nowMs) {
        List<PlayerPosModel> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        double thresholdSq = config.moveThreshold * config.moveThreshold;

        for (PlayerSnapshot s : snapshots) {
            if (s == null || s.uuid == null) continue;
            seen.add(s.uuid);
            Last last = state.get(s.uuid);
            boolean moved = last == null || distSq(last.model, s) > thresholdSq;
            long since = last == null ? Long.MAX_VALUE : nowMs - last.sentAtMs;
            boolean due = last == null
                    || (moved && since >= config.moveActiveIntervalMs)
                    || (!moved && since >= config.moveIdleIntervalMs);
            if (!due) continue;

            PlayerPosModel m = new PlayerPosModel(s.uuid, s.name, s.x, s.y, s.z, s.yaw, s.dimension);
            out.add(m);
            state.put(s.uuid, new Last(m, nowMs));
        }
        // 离线玩家清除陈旧状态，重连后按首见全量上报一次
        state.keySet().retainAll(seen);
        return out;
    }

    private static double distSq(PlayerPosModel last, PlayerSnapshot now) {
        double dx = now.x - last.getX();
        double dy = now.y - last.getY();
        double dz = now.z - last.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
