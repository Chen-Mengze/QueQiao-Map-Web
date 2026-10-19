package net.yumezawa.queqiao_map_web.forge.platform;

import net.minecraft.core.BlockPos;
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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.yumezawa.queqiao_map_web.platform.PlatformAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Forge 1.20.1（official mappings, forge 47.4.x）实现 PlatformAdapter。
 * forge/origin 是本分支唯一接触 net.minecraft.* 的地方；origin 主结构不 import 任何 MC 类。
 *
 * API 实况核对（javap forge-1.20.1-47.4.28_mapped_official_1.20.1，2026-10）：
 * Heightmap 枚举是 Types（复数；1.20.1 official 即如此，勿与 yarn 的 Type 混用）；
 * ResourceLocation 单参构造器在 47.4.x 已标 deprecated-for-removal，统一走 tryParse；
 * tryParse 对非法 registry key 返回 null，正好对应「未知/未加载维度」的静默语义。
 */
public final class ForgePlatformAdapter implements PlatformAdapter {

    private static final Logger LOG = Logger.getLogger("queqiao_map_web.forge"); // §8：Logger 名 = queqiao_map_web.<platform>

    private final MinecraftServer server;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "queqiao-map-web");
        t.setDaemon(true);
        return t;
    });

    private Consumer<List<PlayerSnapshot>> tickConsumer;
    private ChunkDirtyListener chunkDirty;

    public ForgePlatformAdapter(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 入口在 ServerStartedEvent 里调用一次：把本适配器注册到游戏总线。
     * register/unregister 按适配器实例成对进行——服务端重启后新适配器只注册一次，
     * 不会出现同一 tick 被两个旧适配器重复采样的监听器泄漏。
     */
    public void registerHooks() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** 入口在 ServerStoppingEvent 里调用：摘掉总线监听，温和收尾后台线程 */
    public void shutdown() {
        MinecraftForge.EVENT_BUS.unregister(this);
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---------------- PlatformAdapter ----------------

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
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(); // 256 次取样共用一个可变坐标，避免逐像素分配
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = (pos.x << 4) + lx;
                int wz = (pos.z << 4) + lz;
                // heightmap 存储值是「最高匹配块 Y + 1」（javap Heightmap：update 里 setHeight(y+1)、getFirstBlockY 返回 sample-1）
                // 直接拿 getHeight 取块会读到表面空气——必须 -1
                int topY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, wx, wz) - 1;
                if (topY < level.getMinBuildHeight()) continue; // 该列没有任何非空气块
                cursor.set(wx, topY, wz);
                BlockState state = level.getBlockState(cursor);
                if (state.isAir()) continue; // 保持 0 = palette 下标 0（未设置）
                int color = state.getMapColor(level, cursor).col & 0xFFFFFF;
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

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // §4.3 铁律：所有事件钩子外层 catch Throwable —— 本 mod 的任何异常绝不冒到总线打断服务端
        try {
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
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.FINE, "玩家采样失败", t);
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        try {
            markDirtyFromLevel(event.getLevel(), event.getChunk().getPos().x, event.getChunk().getPos().z);
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.FINE, "区块加载钩子失败", t);
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        try {
            markDirtyFromLevel(event.getLevel(),
                    event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.FINE, "方块破坏钩子失败", t);
        }
    }

    @SubscribeEvent
    public void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        try {
            markDirtyFromLevel(event.getLevel(),
                    event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.FINE, "方块放置钩子失败", t);
        }
    }

    /** 事件基类拿到的是 LevelAccessor；只有 ServerLevel 才发脏区块 */
    private void markDirtyFromLevel(LevelAccessor level, int chunkX, int chunkZ) {
        if (chunkDirty == null || level == null || level.isClientSide()) return;
        if (!(level instanceof ServerLevel sl)) return;
        chunkDirty.onChunkDirty(sl.dimension().location().toString(), new ChunkPos(chunkX, chunkZ));
    }

    /** dimension registry key → ServerLevel；非法 key / 未知维度返回 null（tryParse 非法值不抛异常） */
    private ServerLevel levelOf(String dimension) {
        ResourceLocation rl = ResourceLocation.tryParse(dimension);
        if (rl == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }
}
