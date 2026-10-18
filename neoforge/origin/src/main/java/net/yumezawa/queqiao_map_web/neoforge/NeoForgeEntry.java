package net.yumezawa.queqiao_map_web.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.yumezawa.queqiao_map_web.QmwLifecycle;
import net.yumezawa.queqiao_map_web.neoforge.platform.PlatNeoForgeAdapter;

/**
 * NeoForge 入口（M4 里程碑接入编译）。1.21.1 起：@Mod 在 net.neoforged.fml.common，
 * 游戏事件走 NeoForge.EVENT_BUS（mod 总线只管自己的注册）。
 */
@Mod(QmwLifecycle.MOD_ID)
public final class NeoForgeEntry {

    private PlatNeoForgeAdapter adapter;

    public NeoForgeEntry(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void onServerStarted(ServerStartedEvent event) {
        adapter = new PlatNeoForgeAdapter(event.getServer());
        adapter.registerHooks();
        QmwLifecycle.get().start(adapter, FMLPaths.CONFIGDIR.get());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        QmwLifecycle.get().stop();
        if (adapter != null) {
            adapter.shutdown();
            adapter = null;
        }
    }
}
