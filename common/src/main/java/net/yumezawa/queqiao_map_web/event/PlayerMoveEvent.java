package net.yumezawa.queqiao_map_web.event;

import com.github.theword.queqiao.tool.event.base.BaseEvent;
import net.yumezawa.queqiao_map_web.model.PlayerPosModel;

import java.util.List;

/**
 * 玩家位置批量事件（非逐人逐条）。
 * server_name / server_version / server_type 由鹊桥在发送路径自动填充，勿手写。
 */
public final class PlayerMoveEvent extends BaseEvent {

    private final List<PlayerPosModel> players;

    public PlayerMoveEvent(List<PlayerPosModel> players) {
        super("PlayerMoveEvent", "notice", "player_move");
        this.players = players;
    }

    public List<PlayerPosModel> getPlayers() {
        return players;
    }
}
