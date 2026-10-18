package net.yumezawa.queqiao_map_web.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.yumezawa.queqiao_map_web.MapWeb;
import net.yumezawa.queqiao_map_web.forge.platform.ForgePlatformAdapter;

/**
 * Forge 1.20.1 入口。加载器入口只做两件事：接加载器生命周期 + 交出适配器。
 * 全部业务在 origin（MapWeb / PlatformAdapter 契约内）。
 *
 * 时序铁律：必须在 ServerStartedEvent 之后才 start —— 鹊桥本体的 GlobalContext
 * 在更早的服务端启动阶段 init，过早发送会被 send() 探针挡掉。
 */
@Mod(MapWeb.MOD_ID)
public final class MapWebForge {

    private ForgePlatformAdapter adapter;

    public MapWebForge() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void onServerStarted(ServerStartedEvent event) {
        adapter = new ForgePlatformAdapter(event.getServer());
        adapter.registerHooks();
        MapWeb.get().start(adapter, FMLPaths.CONFIGDIR.get());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        MapWeb.get().stop();
        if (adapter != null) {
            adapter.shutdown();
            adapter = null;
        }
    }
}
