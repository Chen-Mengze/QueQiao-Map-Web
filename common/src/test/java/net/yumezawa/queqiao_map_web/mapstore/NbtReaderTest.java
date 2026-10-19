package net.yumezawa.queqiao_map_web.mapstore;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-MS-002：NBT big-endian 解码（TAG 0–12）与畸形输入抛 IOException */
class NbtReaderTest {

    @Test
    @Tag("REQ-MS-002")
    void readAllTagTypesRoundtrip() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("b", (byte) 7);
        root.put("s", (short) -9);
        root.put("i", 42);
        root.put("l", 100000000000L);
        root.put("f", 1.5f);
        root.put("d", 2.5d);
        root.put("ba", new byte[]{1, -2, 3});
        root.put("str", "hello 世界");
        root.put("ints", new int[]{5, -6});
        root.put("longs", new long[]{1L, -2L, 3000000000L});
        root.put("listInt", List.of(1, 2, 3));
        root.put("listStr", List.of("a", "b"));
        Map<String, Object> el1 = new LinkedHashMap<>();
        el1.put("x", 1);
        Map<String, Object> el2 = new LinkedHashMap<>();
        el2.put("x", 2);
        root.put("listComp", List.of(el1, el2));
        Map<String, Object> deep = new LinkedHashMap<>();
        deep.put("z", (byte) 1);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("inner", "v");
        nested.put("deep", deep);
        root.put("nested", nested);
        // 空 list 也应解码为空 List
        root.put("emptyList", List.of());

        byte[] bytes = MapFixtures.nbtRoot(root);
        Map<String, Object> out = NbtReader.readCompound(new ByteArrayInputStream(bytes));

        assertEquals((byte) 7, ((Number) out.get("b")).byteValue());
        assertEquals((short) -9, ((Number) out.get("s")).shortValue());
        assertEquals(42, ((Number) out.get("i")).intValue());
        assertEquals(100000000000L, ((Number) out.get("l")).longValue());
        assertEquals(1.5f, ((Number) out.get("f")).floatValue());
        assertEquals(2.5d, ((Number) out.get("d")).doubleValue());
        assertArrayEquals(new byte[]{1, -2, 3}, (byte[]) out.get("ba"));
        assertEquals("hello 世界", out.get("str"));
        assertArrayEquals(new int[]{5, -6}, (int[]) out.get("ints"));
        assertArrayEquals(new long[]{1L, -2L, 3000000000L}, (long[]) out.get("longs"));
        assertEquals(List.of(1, 2, 3), out.get("listInt"));
        assertEquals(List.of("a", "b"), out.get("listStr"));
        List<?> comps = (List<?>) out.get("listComp");
        assertEquals(2, comps.size());
        assertEquals(1, ((Number) ((Map<?, ?>) comps.get(0)).get("x")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) comps.get(1)).get("x")).intValue());
        Map<?, ?> nestedOut = (Map<?, ?>) out.get("nested");
        assertEquals("v", nestedOut.get("inner"));
        assertEquals((byte) 1, ((Number) ((Map<?, ?>) nestedOut.get("deep")).get("z")).byteValue());
        assertTrue(out.get("emptyList") instanceof List<?> && ((List<?>) out.get("emptyList")).isEmpty());
    }

    @Test
    @Tag("REQ-MS-002")
    void malformedRootThrows() throws IOException {
        // 根不是 TAG_Compound → IOException
        byte[] intRoot = {3, 0, 0, 0, 0, 1};
        assertThrows(IOException.class, () -> NbtReader.readCompound(new ByteArrayInputStream(intRoot)));
        // 空流 → IOException
        assertThrows(IOException.class, () -> NbtReader.readCompound(new ByteArrayInputStream(new byte[0])));
        // 截断流 → IOException（EOFException 是其子类）
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("str", "0123456789");
        byte[] full = MapFixtures.nbtRoot(root);
        byte[] cut = Arrays.copyOf(full, full.length - 5);
        assertThrows(IOException.class, () -> NbtReader.readCompound(new ByteArrayInputStream(cut)));
    }

    @Test
    @Tag("REQ-MS-002")
    void byteArrayTag12DecodesForBlockStates() throws IOException {
        // 块 section 常用 TAG_LongArray(12)，确认解码为 long[]
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("BlockStates", new long[]{-1L, 0L, Long.MIN_VALUE});
        byte[] bytes = MapFixtures.nbtRoot(root);
        Map<String, Object> out = NbtReader.readCompound(new ByteArrayInputStream(bytes));
        assertArrayEquals(new long[]{-1L, 0L, Long.MIN_VALUE}, (long[]) out.get("BlockStates"));
    }
}
