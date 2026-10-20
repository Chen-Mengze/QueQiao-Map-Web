package net.yumezawa.queqiao_map_web.mapstore;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立 NBT 读取器（零 MC 依赖，蓝图 §2.2；big-endian，TAG 0–12）。
 *
 * <p>返回树约定：Compound → {@code Map<String,Object>}；List → {@code List<Object>}；
 * TAG_Byte/Short/Int → {@code Integer} 或 {@code Byte/Short}（保持原包装：Byte/Short/Integer/Long/Float/Double）；
 * TAG_ByteArray → {@code byte[]}；TAG_IntArray → {@code int[]}；TAG_LongArray → {@code long[]}；
 * TAG_String → {@code String}。</p>
 *
 * <p>说明：支持 TAG 0–12——1.16+ 的 BlockStates 必需 TAG_LongArray(12)。List 元素类型取列表头
 * 声明的类型（NBT 规范如此，异质表无法表示，读出不匹配自然抛 IOException）。
 * 所有解析异常向上抛 IOException，由调用方吞掉记 FINE。</p>
 */
public final class NbtReader {

    /** TAG_End */
    static final int TAG_END = 0;
    /** TAG_Byte */
    static final int TAG_BYTE = 1;
    /** TAG_Short */
    static final int TAG_SHORT = 2;
    /** TAG_Int */
    static final int TAG_INT = 3;
    /** TAG_Long */
    static final int TAG_LONG = 4;
    /** TAG_Float */
    static final int TAG_FLOAT = 5;
    /** TAG_Double */
    static final int TAG_DOUBLE = 6;
    /** TAG_ByteArray */
    static final int TAG_BYTE_ARRAY = 7;
    /** TAG_String */
    static final int TAG_STRING = 8;
    /** TAG_List */
    static final int TAG_LIST = 9;
    /** TAG_Compound */
    static final int TAG_COMPOUND = 10;
    /** TAG_IntArray */
    static final int TAG_INT_ARRAY = 11;
    /** TAG_LongArray（1.16+ BlockStates 必需） */
    static final int TAG_LONG_ARRAY = 12;

    /** 嵌套深度上限，防恶意/损坏数据栈溢出 */
    private static final int MAX_DEPTH = 512;
    /** 单数组元素数上限，防损坏长度字段导致 OOM */
    private static final int MAX_ARRAY = 1 << 24;

    private NbtReader() {
    }

    /**
     * 读取一个完整 NBT 文档（根 TAG_Compound + 名称 + 负载）。
     *
     * @throws IOException 根类型不是 Compound、流损坏或提前结束
     */
    public static Map<String, Object> readCompound(InputStream in) throws IOException {
        DataInputStream din = in instanceof DataInputStream d ? d : new DataInputStream(in);
        int type = din.read();
        if (type < 0) {
            throw new IOException("NBT 流为空");
        }
        if (type != TAG_COMPOUND) {
            throw new IOException("NBT 根不是 TAG_Compound: " + type);
        }
        din.readUTF(); // 根名称（通常为空串），丢弃
        Object payload = readPayload(TAG_COMPOUND, din, 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) payload;
        return root;
    }

    /** 读取一个名为 name 的 TAG 的负载（type 已知） */
    private static Object readPayload(int type, DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT 嵌套超过 " + MAX_DEPTH + " 层");
        }
        switch (type) {
            case TAG_BYTE:
                return in.readByte();
            case TAG_SHORT:
                return in.readShort();
            case TAG_INT:
                return in.readInt();
            case TAG_LONG:
                return in.readLong();
            case TAG_FLOAT:
                return in.readFloat();
            case TAG_DOUBLE:
                return in.readDouble();
            case TAG_BYTE_ARRAY: {
                int len = checkedLen(in.readInt(), "ByteArray");
                byte[] a = new byte[len];
                in.readFully(a);
                return a;
            }
            case TAG_STRING:
                return in.readUTF();
            case TAG_LIST: {
                int elemType = in.readUnsignedByte();
                int size = checkedLen(in.readInt(), "List");
                List<Object> list = new ArrayList<>(Math.min(size, 1024));
                for (int i = 0; i < size; i++) {
                    list.add(readPayload(elemType, in, depth + 1));
                }
                return list;
            }
            case TAG_COMPOUND: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.read();
                    if (t < 0) {
                        throw new IOException("Compound 未闭合（提前 EOF）");
                    }
                    if (t == TAG_END) {
                        return map;
                    }
                    String name = in.readUTF();
                    map.put(name, readPayload(t, in, depth + 1));
                }
            }
            case TAG_INT_ARRAY: {
                int len = checkedLen(in.readInt(), "IntArray");
                int[] a = new int[len];
                for (int i = 0; i < len; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case TAG_LONG_ARRAY: {
                int len = checkedLen(in.readInt(), "LongArray");
                long[] a = new long[len];
                for (int i = 0; i < len; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default:
                throw new IOException("未知 TAG 类型: " + type);
        }
    }

    private static int checkedLen(int len, String what) throws IOException {
        if (len < 0 || len > MAX_ARRAY) {
            throw new IOException(what + " 长度非法: " + len);
        }
        return len;
    }
}
