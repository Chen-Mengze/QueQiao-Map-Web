package net.yumezawa.queqiao_map_web.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.yumezawa.queqiao_map_web.QmwLifecycle;
import net.yumezawa.queqiao_map_web.fabric.platform.PlatFabricAdapter;

/**
 * Fabric 入口（M2 里程碑接入编译：settings.gradle 放开 include + 写 fabric/origin/build.gradle）。
 * 时序铁律：SERVER_STARTED 之后才 start（鹊桥 GlobalContext 就绪后才有流量）。
 */
public final class FabricEntry implements ModInitializer {

    private PlatFabricAdapter adapter;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            adapter = new PlatFabricAdapter(server);
            QmwLifecycle.get().start(adapter, FabricLoader.getInstance().getConfigDir());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            QmwLifecycle.get().stop();
            if (adapter != null) {
                adapter.shutdown();
                adapter = null;
            }
        });
    }
}
