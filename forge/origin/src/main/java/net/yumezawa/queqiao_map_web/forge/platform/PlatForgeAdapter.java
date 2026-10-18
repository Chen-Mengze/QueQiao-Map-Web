package net.yumezawa.queqiao_map_web.forge.platform;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.yumezawa.queqiao_map_web.platform.PlatAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Forge 1.20.1（official mappings）实现 PlatAdapter。
 * forge/origin 是本分支唯一接触 net.minecraft.* 的地方；origin 主结构不 import 任何 MC 类。
 */
public final class PlatForgeAdapter implements PlatAdapter {

    private final MinecraftServer server;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "queqiao-map-web");
        t.setDaemon(true);
        return t;
    });

    private Consumer<List<PlayerSnapshot>> tickConsumer;
    private ChunkDirtyListener chunkDirty;

    public PlatForgeAdapter(MinecraftServer server) {
        this.server = server;
    }

    /** 入口在 ServerStartedEvent 里调用一次：把加载器事件接到本适配器 */
    public void registerHooks() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(this::onBlockBreak);
        MinecraftForge.EVENT_BUS.addListener(this::onEntityPlace);
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    // ---------------- PlatAdapter ----------------

    @Override
    public void onTick(Consumer<List<PlayerSnapshot>> consumer) {
        this.tickConsumer = consumer;
    }

    @Override
    public void onChunkDirty(ChunkDirtyListener cb) {
        this.chunkDirty = cb;
    }

    /**
     * 主线程采样顶层方块（内存取，不读 region 文件）。
     * 返回 16*16*3 RGB24，行优先；区块未加载返回 null。
     */
    @Override
    public byte[] renderChunk(String dimension, ChunkPos pos) {
        ServerLevel level = levelOf(dimension);
        if (level == null) return null;
        LevelChunk chunk = level.getChunkSource().getChunk(pos.x, pos.z, false); // false：不为采样生成区块
        if (chunk == null) return null;

        byte[] rgb = new byte[16 * 16 * 3];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = (pos.x << 4) + lx;
                int wz = (pos.z << 4) + lz;
                int topY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, wx, wz); // 最高非空气固体方块
                BlockState state = level.getBlockState(new net.minecraft.core.BlockPos(wx, topY, wz));
                if (state.isAir()) continue; // 保持 0 = palette 下标 0（未设置）
                int color = state.getMapColor(level, new net.minecraft.core.BlockPos(wx, topY, wz)).col & 0xFFFFFF;
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
        for (ResourceKey<Level> key : server.levelKeys()) {
            out.add(key.location().toString());
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
        return "forge";
    }

    // ---------------- 加载器事件 → 契约回调 ----------------

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || tickConsumer == null) return;
        List<PlayerSnapshot> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlayerSnapshot s = new PlayerSnapshot();
            s.uuid = p.getUUID().toString();
            s.name = p.getGameProfile().getName();
            s.x = p.getX();
            s.y = p.getY();
            s.z = p.getZ();
            s.yaw = p.getYRot();
            s.dimension = p.level().dimension().location().toString(); // registry key，绝不用世界文件夹名
            out.add(s);
        }
        tickConsumer.accept(out);
    }

    private void onChunkLoad(ChunkEvent.Load event) {
        markDirtyFromLevel(event.getLevel(), event.getChunk().getPos().x, event.getChunk().getPos().z);
    }

    private void onBlockBreak(BlockEvent.BreakEvent event) {
        markDirtyFromLevel(event.getLevel(),
                event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
    }

    private void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        markDirtyFromLevel(event.getLevel(),
                event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
    }

    /** 事件基类拿到的是 LevelAccessor；只有 ServerLevel 才发脏区块 */
    private void markDirtyFromLevel(LevelAccessor level, int chunkX, int chunkZ) {
        if (chunkDirty == null || level == null || level.isClientSide()) return;
        if (!(level instanceof ServerLevel sl)) return;
        chunkDirty.onChunkDirty(sl.dimension().location().toString(), new ChunkPos(chunkX, chunkZ));
    }

    /** dimension registry key → ServerLevel；未知/未加载返回 null */
    private ServerLevel levelOf(String dimension) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimension));
        return server.getLevel(key);
    }
}
