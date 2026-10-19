package net.yumezawa.queqiao_map_web.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.yumezawa.queqiao_map_web.ModLifecycle;
import net.yumezawa.queqiao_map_web.forge.platform.ForgePlatformAdapter;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Forge 1.20.1 入口。加载器入口只做两件事：接加载器生命周期 + 交出适配器。
 * 全部业务在 origin（ModLifecycle / PlatformAdapter 契约内）。
 *
 * 时序铁律：必须在 ServerStartedEvent 之后才 start —— 鹊桥本体的 GlobalContext
 * 在更早的服务端启动阶段 init，过早发送会被 send() 探针挡掉。
 *
 * 鹊桥缺席门：origin 的事件类直接 extends 鹊桥 BaseEvent（MapPaletteEvent 等）。
 * 服务端没装 queqiao-tool 时 start() 装配会触发 NoClassDefFoundError 打断服务端，
 * 违反「默认零行为、绝不抛异常」铁律——ServerStarted 先用 Class.forName 探针，
 * 桥不在就整体不装配（日志一次 info），适配器与总线钩子都不挂。
 */
@Mod(ModLifecycle.MOD_ID)
public final class QueqiaoMapWeb {

    private static final Logger LOG = Logger.getLogger("queqiao_map_web");
    /** 鹊桥 Tool 的入口事件基类；存在即桥已安装（Forge 默认共享 mod 类加载器可见） */
    private static final String QUEQIAO_PROBE = "com.github.theword.queqiao.tool.event.base.BaseEvent";

    private ForgePlatformAdapter adapter;

    public QueqiaoMapWeb() {
        // addListener(方法引用) 无需 @SubscribeEvent —— eventbus 6.2.33 字节码 javap 实证：
        // addListener→checkNotGeneric→getEventClass→addToListeners 全链无注解校验，
        // isAnnotationPresent(SubscribeEvent) 只存在于 register(Object) 扫描路径；
        // 且该版本 jar 内根本没有 InvalidSubscriptionException 类。此为 MDK 标准写法。
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void onServerStarted(ServerStartedEvent event) {
        if (!queqiaoPresent()) return;
        adapter = new ForgePlatformAdapter(event.getServer());
        adapter.registerHooks();
        ModLifecycle.get().start(adapter, FMLPaths.CONFIGDIR.get());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (adapter == null) return; // 未装配：无需 stop
        // 停服顺序铁律：先静默（摘总线监听 + 后台池排空并等待），再排空队列。
        // 静默后后台 flush 线程已不存在，drain 的主线程同步发送是唯一发送者，
        // 不会再与池内残留任务并发调用 GlobalContext.sendEvent（其线程安全未验证）。
        adapter.shutdown();
        ModLifecycle.get().stop();
        adapter = null;
    }

    private static boolean queqiaoPresent() {
        try {
            Class.forName(QUEQIAO_PROBE);
            return true;
        } catch (Throwable t) { // 缺席或加载异常都按「桥不在」处理：本 mod 空转
            LOG.info("未检测到鹊桥 QueQiao Tool，queqiao_map_web 本次会话不启动（零行为）。");
            return false;
        }
    }
}
