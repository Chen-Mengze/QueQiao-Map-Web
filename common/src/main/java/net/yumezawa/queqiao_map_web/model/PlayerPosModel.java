package net.yumezawa.queqiao_map_web.model;

/**
 * 含 dimension 的玩家位置模型（补鹊桥 PlayerModel 的缺口）。
 * 全部单词字段名：Gson 反射序列化后与协议 JSON 完全一致，不受命名策略影响。
 */
public final class PlayerPosModel {

    private final String uuid;
    private final String nickname;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final String dimension;

    public PlayerPosModel(String uuid, String nickname, double x, double y, double z, float yaw, String dimension) {
        this.uuid = uuid;
        this.nickname = nickname;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.dimension = dimension;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public String getDimension() {
        return dimension;
    }
}
