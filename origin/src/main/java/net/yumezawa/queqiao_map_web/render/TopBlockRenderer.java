package net.yumezawa.queqiao_map_web.render;

import net.yumezawa.queqiao_map_web.model.TilePayloadModel;
import net.yumezawa.queqiao_map_web.platform.PlatformAdapter.ChunkPos;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

/**
 * zlib + sha1 + base64 打包（后台线程调用，禁止在主线程跑）。
 * 输入是平台适配层在主线程从内存采到的 RGB24；这里做量化（RGB→palette 下标）、
 * zlib 压缩（format=zlib-pal8）、sha1（压缩字节的小写 hex）与 base64。
 * 绝不用小尺寸 PNG：16x16 PNG 要 938 B，是 zlib 索引的 9 倍。
 */
public final class TopBlockRenderer {

    public static final String FORMAT = "zlib-pal8";
    private static final int SIDE_CHUNKS = 16;

    private TopBlockRenderer() {
    }

    /**
     * 把若干区块打包成一块瓦片。
     *
     * @param originCx 批量区域左上角的区块 x
     * @param originCz 批量区域左上角的区块 z
     * @param size     每边区块数（1 或 8）；网格 (size*16)^2 像素，缺失区块填下标 0
     * @param samples  区域内已采到的区块，chunk 与其 16*16*3 RGB24 数据
     */
    public static TilePayloadModel pack(int originCx, int originCz, int size, List<Sample> samples, Palette palette) {
        int side = size * SIDE_CHUNKS;
        byte[] pal = new byte[side * side]; // 默认 0 = 未设置

        for (Sample s : samples) {
            int baseX = (s.cx() - originCx) * SIDE_CHUNKS;
            int baseZ = (s.cz() - originCz) * SIDE_CHUNKS;
            if (baseX < 0 || baseZ < 0 || baseX + SIDE_CHUNKS > side || baseZ + SIDE_CHUNKS > side) continue;
            byte[] rgb = s.rgb24();
            for (int z = 0; z < SIDE_CHUNKS; z++) {
                int row = (baseZ + z) * side + baseX;
                int src = z * SIDE_CHUNKS * 3;
                for (int x = 0; x < SIDE_CHUNKS; x++) {
                    int i = src + x * 3;
                    int color = ((rgb[i] & 0xFF) << 16) | ((rgb[i + 1] & 0xFF) << 8) | (rgb[i + 2] & 0xFF);
                    pal[row + x] = (byte) palette.indexOf(color);
                }
            }
        }

        byte[] deflated = zlib(pal);
        return new TilePayloadModel(originCx, originCz, size, FORMAT, sha1Hex(deflated), Base64.getEncoder().encodeToString(deflated));
    }

    /** 一个已采样区块：区块坐标 + 16*16*3 RGB24（行优先，左上原点） */
    public record Sample(int cx, int cz, byte[] rgb24) {
    }

    private static byte[] zlib(byte[] raw) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(bos)) {
            deflater.write(raw);
        } catch (Exception e) {
            throw new IllegalStateException("zlib 压缩失败", e);
        }
        return bos.toByteArray();
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }
}
