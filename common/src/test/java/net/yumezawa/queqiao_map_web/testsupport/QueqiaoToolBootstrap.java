package net.yumezawa.queqiao_map_web.testsupport;

import com.github.theword.queqiao.tool.GlobalContext;
import com.github.theword.queqiao.tool.config.Config;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * 鹊桥运行时引导。鹊桥 BaseEvent 构造器硬读 GlobalContext.getConfig().getServerName()：
 * GlobalContext.init() 之前构造任何事件 DTO 必 NPE（生产上由 QueQiaoSink.isReady() 门保证先后可）。
 * JVM 测试注入哑 Config：Config(boolean, Logger) 为轻量构造器（默认值 + 子配置 new，不读盘）。
 * serverVersion/serverType 是 private static 且无 setter，Gson 直出事件 JSON 时 null 字段被省略——
 * 用反射注入哑值，保证协议基线核对（含这两个基字段）不误报。
 */
public final class QueqiaoToolBootstrap {

    private QueqiaoToolBootstrap() {}

    /** 幂等：已有配置则跳过（GlobalContext 是 JVM 全局静态，测试类间共享） */
    public static synchronized void ensureDummyConfig() {
        if (GlobalContext.getConfig() != null) return;
        Config c = new Config(false, LoggerFactory.getLogger("queqiao-map-web-test"));
        c.setServerName("test-server");
        GlobalContext.setConfig(c);
        try {
            setStatic("serverVersion", "1.20.1");
            setStatic("serverType", "forge");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入 serverVersion/serverType 失败", e);
        }
    }

    /** 清回「鹊桥未 init」态（未就绪探针用例）；用例结束前须 ensureDummyConfig() 恢复，避免跨类泄漏 */
    public static synchronized void clearConfig() {
        GlobalContext.setConfig(null);
    }

    private static void setStatic(String name, String value) throws ReflectiveOperationException {
        Field f = GlobalContext.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
}
