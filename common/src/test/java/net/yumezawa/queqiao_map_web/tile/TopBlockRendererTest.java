package net.yumezawa.queqiao_map_web.tile;

import net.yumezawa.queqiao_map_web.model.TilePayloadModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/** REQ-TILE-002/003：zlib-pal8 编码链契约（16px/块 索引阵 → zlib → base64+sha1）。 */
class TopBlockRendererTest {

    private static byte[] solid(int rgb) {
        byte[] px = new byte[16 * 16 * 3];
        for (int i = 0; i < px.length; i += 3) {
            px[i] = (byte) ((rgb >> 16) & 0xFF);
            px[i + 1] = (byte) ((rgb >> 8) & 0xFF);
            px[i + 2] = (byte) (rgb & 0xFF);
        }
        return px;
    }

    /** 消费端契约：base64 → zlib inflate → (size*16)^2 字节的索引流 */
    static byte[] inflate(String b64) throws IOException {
        Inflater in = new Inflater();
        in.setInput(Base64.getDecoder().decode(b64));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            while (!in.finished()) {
                int n = in.inflate(buf);
                if (n == 0) break;
                bos.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            in.end();
        }
        return bos.toByteArray();
    }

    private static String sha1Hex(byte[] data) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder(40);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    @Tag("REQ-TILE-002")
    @DisplayName("zlib-pal8 往返：解出 16*16 索引阵、每像素=纯色调色板下标；sha1=压缩字节小写 hex")
    void singleChunkRoundTrip() throws Exception {
        Palette palette = new Palette();
        int rgb = 0x3f76e4;
        int idx = palette.indexOf(rgb); // 预注册，pack 复用同一下标
        assertTrue(idx >= 1);
        TilePayloadModel t = TopBlockRenderer.pack(0, 0, 1,
                List.of(new TopBlockRenderer.Sample(0, 0, solid(rgb))), palette);
        assertEquals("zlib-pal8", t.getFormat());
        assertEquals(1, t.getSize());
        byte[] pixels = inflate(t.getB64());
        assertEquals(16 * 16, pixels.length);
        for (byte b : pixels) assertEquals((byte) idx, b, "每像素都应等于该色的调色板下标");
        byte[] compressed = Base64.getDecoder().decode(t.getB64());
        assertEquals(sha1Hex(compressed), t.getSha1(), "sha1 对压缩字节计算（INTEGRATION §4.3）");
        assertTrue(t.getSha1().matches("[0-9a-f]{40}"), "小写 hex");
    }

    @Test
    @Tag("REQ-TILE-002")
    @DisplayName("size=8 瓦片：side=128，被采样块按区域偏移落位，未 picked 块保持下标 0（未设置）")
    void mergedTileFillAndHoles() throws Exception {
        Palette palette = new Palette();
        int idx = palette.indexOf(0x00ff00);
        TilePayloadModel t = TopBlockRenderer.pack(0, 0, 8,
                List.of(new TopBlockRenderer.Sample(0, 0, solid(0x00ff00)),
                        new TopBlockRenderer.Sample(7, 7, solid(0x00ff00))), palette);
        assertEquals(8, t.getSize());
        byte[] px = inflate(t.getB64());
        int side = 128;
        assertEquals(side * side, px.length);
        assertEquals((byte) idx, px[0], "(0,0) 块落在区域原点");
        assertEquals((byte) idx, px[(7 * 16) * side + 7 * 16], "(7,7) 块落在偏移 112");
        assertEquals((byte) idx, px[(7 * 16 + 15) * side + 7 * 16 + 15], "(7,7) 块内右下角像素");
        assertEquals(0, px[16 * side], "缺失的 (0,1) 块保持下标 0（未设置）");
    }

    @Test
    @Tag("REQ-TILE-003")
    @DisplayName("sha1 内容寻址：相同内容两次 pack → sha1 与 b64 完全一致（消费端同位置跳过重发的基础）")
    void sha1IsContentAddressed() throws Exception {
        TilePayloadModel a = TopBlockRenderer.pack(3, -2, 1,
                List.of(new TopBlockRenderer.Sample(3, -2, solid(0x22aa88))), new Palette());
        TilePayloadModel b = TopBlockRenderer.pack(3, -2, 1,
                List.of(new TopBlockRenderer.Sample(3, -2, solid(0x22aa88))), new Palette());
        assertEquals(a.getSha1(), b.getSha1());
        assertEquals(a.getB64(), b.getB64());
    }
}
