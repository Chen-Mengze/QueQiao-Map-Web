package net.yumezawa.queqiao_map_web.sender;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-SEND-001 静态守卫（与 CI 契约守卫同口径）：common/src/main 里 GlobalContext 的
 * 「真引用」—— import com.github.theword.queqiao.tool.GlobalContext; 完整字面量，或行内含
 * GlobalContext. 点调用 —— 只允许出现在 sender/QueQiaoSink.java。
 * 行首为 //、*、/* 的注释/javadoc 提及不算引用。
 */
class GlobalContextIsolationTest {

    private static final String IMPORT_LINE = "import com.github.theword.queqiao.tool.GlobalContext;";
    private static final String SINK = "net/yumezawa/queqiao_map_web/sender/QueQiaoSink.java";

    @Test
    @Tag("REQ-SEND-001")
    @DisplayName("扫描 common/src/main 全部 .java（排除注释行）：GlobalContext 真引用只出现在 sender/QueQiaoSink.java")
    void globalContextOnlyReferencedInSender() throws IOException {
        Path root = findMainRoot();
        List<String> offenders = new ArrayList<>();
        boolean sinkScanned = false;
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> files = s.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path p : files) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                List<String> refs = realReferences(Files.readString(p, StandardCharsets.UTF_8));
                if (rel.equals(SINK)) {
                    sinkScanned = true;
                    assertTrue(refs.contains("import GlobalContext"), "QueQiaoSink.java 自身必须 import GlobalContext");
                } else if (!refs.isEmpty()) {
                    offenders.add(rel + " -> " + refs);
                }
            }
        }
        assertTrue(sinkScanned, "未找到 " + SINK + "，扫描根不对？root=" + root);
        assertEquals(List.of(), offenders, "GlobalContext 真引用泄漏到 QueQiaoSink 之外");
    }

    /** 真引用 = 完整 import 字面量 或 含 "GlobalContext." 的可执行行；注释/javadoc 行排除 */
    private static List<String> realReferences(String text) {
        List<String> refs = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
            if (line.contains(IMPORT_LINE)) refs.add("import GlobalContext");
            else if (line.contains("GlobalContext.")) refs.add("GlobalContext. 调用");
        }
        return refs;
    }

    /** 兼容多种测试执行工作目录：Gradle 以 common/ 为 project dir，也可能在仓库根 */
    private static Path findMainRoot() {
        String[] candidates = {"src/main/java", "..\\src\\main\\java", "common\\src\\main\\java"};
        for (String c : candidates) {
            Path p = Paths.get(c).toAbsolutePath().normalize();
            if (Files.isDirectory(p.resolve("net/yumezawa/queqiao_map_web"))) return p;
        }
        throw new IllegalStateException("找不到 common/src/main/java，cwd=" + Paths.get("").toAbsolutePath());
    }
}
