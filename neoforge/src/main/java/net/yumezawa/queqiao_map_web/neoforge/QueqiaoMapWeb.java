package net.yumezawa.queqiao_map_web.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.yumezawa.queqiao_map_web.ModLifecycle;
import net.yumezawa.queqiao_map_web.neoforge.platform.NeoForgePlatformAdapter;

/**
 * NeoForge 入口（阶段三接入编译）。1.21.1 起：@Mod 在 net.neoforged.fml.common，
 * 游戏事件走 NeoForge.EVENT_BUS（mod 总线只管自己的注册）。
 * 停服顺序与 forge / fabric 入口同构——先 adapter.shutdown 再 ModLifecycle.stop（AGENT.md §4.7）。
 */
@Mod(ModLifecycle.MOD_ID)
public final class QueqiaoMapWeb {

    private NeoForgePlatformAdapter adapter;

    public QueqiaoMapWeb(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void onServerStarted(ServerStartedEvent event) {
        adapter = new NeoForgePlatformAdapter(event.getServer());
        adapter.registerHooks();
        ModLifecycle.get().start(adapter, FMLPaths.CONFIGDIR.get());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (adapter == null) return; // 兜底：ServerStarted 未触发时未装配，无需 stop
        // 停服顺序铁律：先摘总线监听 + 后台池收尾，再排空队列
        adapter.shutdown();
        ModLifecycle.get().stop();
        adapter = null;
    }
}
