package net.yumezawa.queqiao_map_web.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.yumezawa.queqiao_map_web.ModLifecycle;
import net.yumezawa.queqiao_map_web.fabric.platform.FabricPlatformAdapter;

/**
 * Fabric 入口（阶段三接入编译：settings.gradle 放开 include + 补 fabric/build.gradle（fabric-loom））。
 * 时序铁律：SERVER_STARTED 之后才 start（鹊桥 GlobalContext 就绪后才有流量）；
 * 停服顺序与 forge / neoforge 入口同构——先 adapter.shutdown 再 ModLifecycle.stop（AGENT.md §4.7）。
 */
public final class QueqiaoMapWeb implements ModInitializer {

    private FabricPlatformAdapter adapter;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            adapter = new FabricPlatformAdapter(server);
            ModLifecycle.get().start(adapter, FabricLoader.getInstance().getConfigDir());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (adapter == null) return; // 兜底：SERVER_STARTED 未触发时未装配，无需 stop
            // 停服顺序铁律：先 adapter.shutdown（挡掉回调 + 后台池收尾）再 stop 排空队列，
            // 保证 drain 时唯一发送者是主线程
            adapter.shutdown();
            ModLifecycle.get().stop();
            adapter = null;
        });
    }
}
