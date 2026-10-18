package net.yumezawa.queqiao_map_web.neoforge.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityPlaceEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yumezawa.queqiao_map_web.platform.PlatAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * NeoForge 1.21.1（official mappings）实现 PlatAdapter。
 * 与 Forge 1.20.1 的主要差异：ServerTickEvent.Post（无 phase 字段）、
 * Heightmap.Types（复数）、ResourceLocation.parse（构造器私有化）。
 */
public final class PlatNeoForgeAdapter implements PlatAdapter {

    private final MinecraftServer server;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "queqiao-map-web");
        t.setDaemon(true);
        return t;
    });

    private Consumer<List<PlayerSnapshot>> tickConsumer;
    private ChunkDirtyListener chunkDirty;

    public PlatNeoForgeAdapter(MinecraftServer server) {
        this.server = server;
    }

    public void registerHooks() {
        NeoForge.EVENT_BUS.register(this);
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
        LevelChunk chunk = level.getChunkSource().getChunk(pos.x, pos.z, false);
        if (chunk == null) return null;

        byte[] rgb = new byte[16 * 16 * 3];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = (pos.x << 4) + lx;
                int wz = (pos.z << 4) + lz;
                int topY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, wx, wz); // 1.21.1: Types（复数）
                BlockPos blockPos = new BlockPos(wx, topY, wz);
                BlockState state = level.getBlockState(blockPos);
                if (state.isAir()) continue;
                int color = state.getMapColor(level, blockPos).color() & 0xFFFFFF; // 1.21.1: MapColor 访问器
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
        return "neoforge";
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) { // 1.21.1：Post 子类，无 phase
        if (tickConsumer == null) return;
        List<PlayerSnapshot> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlayerSnapshot s = new PlayerSnapshot();
            s.uuid = p.getUUID().toString();
            s.name = p.getGameProfile().getName();
            s.x = p.getX();
            s.y = p.getY();
            s.z = p.getZ();
            s.yaw = p.getYRot();
            s.dimension = p.level().dimension().location().toString();
            out.add(s);
        }
        tickConsumer.accept(out);
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        markDirtyFromLevel(event.getLevel(), event.getChunk().getPos().x, event.getChunk().getPos().z);
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        markDirtyFromLevel(event.getLevel(), event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
    }

    @SubscribeEvent
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getBlock() == null) return;
        markDirtyFromLevel(event.getBlock().getLevel(),
                event.getBlock().getBlockPos().getX() >> 4, event.getBlock().getBlockPos().getZ() >> 4);
    }

    private void markDirtyFromLevel(Level level, int chunkX, int chunkZ) {
        if (chunkDirty == null || level == null || level.isClientSide()) return;
        if (!(level instanceof ServerLevel sl)) return;
        chunkDirty.onChunkDirty(sl.dimension().location().toString(), new ChunkPos(chunkX, chunkZ));
    }

    private ServerLevel levelOf(String dimension) {
        // 1.21.1：ResourceLocation 构造器私有化，用 parse
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension));
        return server.getLevel(key);
    }
}
