package net.yumezawa.queqiao_map_web.fabric.platform;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.world.ServerChunkEvents;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.yumezawa.queqiao_map_web.platform.PlatAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Fabric 1.20.1（yarn mappings）实现 PlatAdapter。
 * 注意 yarn 与 mojmap 命名差异：getYaw（非 getYRot）、getRegistryKey().getValue()（非 dimension().location()）。
 * 1.21.x 用条件块或覆盖文件处理（见 AGENT.md）。
 */
public final class PlatFabricAdapter implements PlatAdapter {

    private final MinecraftServer server;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "queqiao-map-web");
        t.setDaemon(true);
        return t;
    });

    private Consumer<List<PlayerSnapshot>> tickConsumer;
    private ChunkDirtyListener chunkDirty;

    public PlatFabricAdapter(MinecraftServer server) {
        this.server = server;
        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) ->
                markDirty(level, chunk.getPos().x, chunk.getPos().z));
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state) ->
                markDirty(level, pos.getX() >> 4, pos.getZ() >> 4));
        // TODO(M2): 方块放置脏标记 —— fabric 无等价 EntityPlaceEvent，评估 BlockEntityInsertEvents 或改用区块级近似
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    @Override
    public void onTick(Consumer<List<PlayerSnapshot>> consumer) {
        this.tickConsumer = consumer;
    }

    @Override
    public void onChunkDirty(ChunkDirtyListener cb) {
        this.chunkDirty = cb;
    }

    @Override
    public byte[] renderChunk(String dimension, ChunkPos pos) {
        ServerLevel level = levelOf(dimension);
        if (level == null) return null;
        LevelChunk chunk = level.getChunkSource().getWorldChunk(pos.x, pos.z, false); // yarn: WorldChunk, false=不生成
        if (chunk == null) return null;

        byte[] rgb = new byte[16 * 16 * 3];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = (pos.x << 4) + lx;
                int wz = (pos.z << 4) + lz;
                int topY = level.getHeight(Heightmap.Type.OCEAN_FLOOR, wx, wz);
                BlockState state = level.getBlockState(new BlockPos(wx, topY, wz));
                if (state.isAir()) continue;
                int color = state.getMapColor(level, new BlockPos(wx, topY, wz)).color & 0xFFFFFF;
                int o = (lz * 16 + lx) * 3;
                rgb[o] = (byte) ((color >> 16) & 0xFF);
                rgb[o + 1] = (byte) ((color >> 8) & 0xFF);
                rgb[o + 2] = (byte) (color & 0xFF);
            }
        }
        return rgb;
    }

    @Override
    public List<String> listDimensions() {
        List<String> out = new ArrayList<>();
        for (ServerLevel level : server.getWorlds()) {
            out.add(level.getWorldKey().getValue().toString()); // yarn: getWorldKey/getValue
        }
        out.sort(String::compareTo);
        return out;
    }

    @Override
    public void runAsync(Runnable task) {
        pool.execute(task);
    }

    @Override
    public String platformName() {
        return "fabric";
    }

    private void onEndTick(MinecraftServer server) {
        if (tickConsumer == null) return;
        List<PlayerSnapshot> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerManager().getPlayerList()) {
            PlayerSnapshot s = new PlayerSnapshot();
            s.uuid = p.getUuid().toString(); // yarn: getUuid
            s.name = p.getName().getString();
            s.x = p.getX();
            s.y = p.getY();
            s.z = p.getZ();
            s.yaw = p.getYaw(); // yarn: getYaw（Forge 为 getYRot）
            s.dimension = p.getWorld().getRegistryKey().getValue().toString(); // registry key
            out.add(s);
        }
        tickConsumer.accept(out);
    }

    /** dimension registry key -> ServerLevel（yarn: RegistryKey.of(Registry.DIMENSION_KEY, Identifier)）；未知返回 null */
    private ServerLevel levelOf(String dimension) {
        return server.getWorld(RegistryKey.of(Registry.DIMENSION_KEY, new Identifier(dimension)));
    }

    private void markDirty(Level level, int chunkX, int chunkZ) {
        if (chunkDirty == null || level == null || level.isClient) return; // yarn: isClient
        if (!(level instanceof ServerLevel sl)) return;
        chunkDirty.onChunkDirty(sl.getRegistryKey().getValue().toString(), new ChunkPos(chunkX, chunkZ));
    }
}
