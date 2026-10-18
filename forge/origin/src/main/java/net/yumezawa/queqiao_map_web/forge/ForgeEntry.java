package net.yumezawa.queqiao_map_web.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.yumezawa.queqiao_map_web.QmwLifecycle;
import net.yumezawa.queqiao_map_web.forge.platform.PlatForgeAdapter;

/**
 * Forge 1.20.1 入口。加载器入口只做两件事：接加载器生命周期 + 交出适配器。
 * 全部业务在 origin（QmwLifecycle / PlatAdapter 契约内）。
 *
 * 时序铁律：必须在 ServerStartedEvent 之后才 start —— 鹊桥本体的 GlobalContext
 * 在更早的服务端启动阶段 init，过早发送会被 send() 探针挡掉。
 */
@Mod(QmwLifecycle.MOD_ID)
public final class ForgeEntry {

    private PlatForgeAdapter adapter;

    public ForgeEntry() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void onServerStarted(ServerStartedEvent event) {
        adapter = new PlatForgeAdapter(event.getServer());
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
