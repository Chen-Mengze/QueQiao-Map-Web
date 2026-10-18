package net.yumezawa.queqiao_map_web.event;

import com.github.theword.queqiao.tool.event.base.BaseEvent;
import net.yumezawa.queqiao_map_web.model.MdlPlayerPos;

import java.util.List;

/**
 * 玩家位置批量事件（非逐人逐条）。
 * server_name / server_version / server_type 由鹊桥在发送路径自动填充，勿手写。
 */
public final class EvtPlayerMove extends BaseEvent {

    private final List<MdlPlayerPos> players;

    public EvtPlayerMove(List<MdlPlayerPos> players) {
        super("EvtPlayerMove", "notice", "player_move");
        this.players = players;
    }

    public List<MdlPlayerPos> getPlayers() {
        return players;
    }
}
